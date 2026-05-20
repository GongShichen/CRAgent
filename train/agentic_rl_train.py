#!/usr/bin/env python3
"""Offline AgenticRL training entrypoint for CR-Agent.

The Java agent exports rollout data to:

- datasets/RL/episodes.jsonl
- datasets/RL/rewards.jsonl

This script implements a practical offline AgenticRL warmup objective:
reward-weighted policy learning over full agent steps: samples are weighted by
terminal and step-level rewards, and bad rollouts contribute little or no
gradient.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any

import torch
from datasets import Dataset
from peft import LoraConfig
from torch.utils.data import DataLoader
from transformers import AutoModelForCausalLM, AutoTokenizer, get_linear_schedule_with_warmup


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        raise FileNotFoundError(f"missing dataset: {path}")
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as fh:
        for line_no, line in enumerate(fh, start=1):
            if not line.strip():
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid JSONL at {path}:{line_no}: {exc}") from exc
    if not rows:
        raise ValueError(f"dataset is empty: {path}")
    return rows


def reward_by_session(rewards_path: Path | None) -> dict[str, float]:
    if rewards_path is None or not rewards_path.exists():
        return {}
    out: dict[str, float] = {}
    for row in read_jsonl(rewards_path):
        session_id = str(row.get("session_id", ""))
        if session_id:
            out[session_id] = float(row.get("terminal_reward", 0.0) or 0.0)
    return out


def message_text(messages: list[dict[str, Any]]) -> str:
    parts: list[str] = []
    for msg in messages:
        role = msg.get("role", "unknown")
        content = msg.get("content")
        if content is not None:
            parts.append(f"<|{role}|>\n{content}")
        if msg.get("tool_calls"):
            parts.append(f"<|assistant_tool_calls|>\n{json.dumps(msg['tool_calls'], ensure_ascii=False)}")
        if role == "tool":
            parts.append(f"<|tool_result:{msg.get('tool_call_id', '')}|>\n{content or ''}")
    return "\n".join(parts)


def action_text(action: dict[str, Any]) -> str:
    message = action.get("message")
    if isinstance(message, dict):
        content = message.get("content")
        if action.get("type") == "tool_call" or message.get("tool_calls"):
            return "<|assistant_tool_calls|>\n" + json.dumps(message.get("tool_calls", []), ensure_ascii=False)
        return f"<|assistant|>\n{content or ''}"
    return "<|assistant|>\n" + json.dumps(action, ensure_ascii=False)


def build_training_rows(episodes: list[dict[str, Any]], rewards: dict[str, float], min_weight: float) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for episode in episodes:
        session_id = str(episode.get("session_id", ""))
        terminal_reward = rewards.get(session_id, float(episode.get("terminal_reward", 0.0) or 0.0))
        for step in episode.get("steps", []):
            state = step.get("state") if isinstance(step.get("state"), dict) else {}
            messages = state.get("messages") if isinstance(state.get("messages"), list) else []
            prompt = message_text(messages)
            target = action_text(step.get("action", {}) if isinstance(step.get("action"), dict) else {})
            if not prompt.strip() or not target.strip():
                continue
            step_reward = float(step.get("reward", 0.0) or 0.0)
            # Convert [-1, 1] style rewards into a non-negative policy weight.
            raw_weight = max(0.0, terminal_reward + 0.25 * step_reward)
            weight = max(min_weight, raw_weight) if terminal_reward > 0 else raw_weight
            if weight <= 0:
                continue
            rows.append({
                "session_id": session_id,
                "task_id": episode.get("task_id", ""),
                "phase": step.get("phase", ""),
                "prompt": prompt,
                "target": target,
                "weight": float(min(2.0, weight)),
            })
    if not rows:
        raise ValueError("no positive-reward agent steps survived conversion")
    return rows


def tokenize_row(row: dict[str, Any], tokenizer: AutoTokenizer, max_length: int) -> dict[str, Any]:
    prompt = row["prompt"].rstrip() + "\n"
    target = row["target"].rstrip()
    full = prompt + target + tokenizer.eos_token
    encoded = tokenizer(full, truncation=True, max_length=max_length)
    prompt_ids = tokenizer(prompt, truncation=True, max_length=max_length)["input_ids"]
    labels = list(encoded["input_ids"])
    mask_upto = min(len(prompt_ids), len(labels))
    labels[:mask_upto] = [-100] * mask_upto
    encoded["labels"] = labels
    encoded["weight"] = row["weight"]
    return encoded


def collate(batch: list[dict[str, Any]], tokenizer: AutoTokenizer) -> dict[str, torch.Tensor]:
    max_len = max(len(item["input_ids"]) for item in batch)
    input_ids, attention_mask, labels, weights = [], [], [], []
    pad_id = tokenizer.pad_token_id
    for item in batch:
        pad = max_len - len(item["input_ids"])
        input_ids.append(item["input_ids"] + [pad_id] * pad)
        attention_mask.append(item["attention_mask"] + [0] * pad)
        labels.append(item["labels"] + [-100] * pad)
        weights.append(float(item["weight"]))
    return {
        "input_ids": torch.tensor(input_ids, dtype=torch.long),
        "attention_mask": torch.tensor(attention_mask, dtype=torch.long),
        "labels": torch.tensor(labels, dtype=torch.long),
        "weights": torch.tensor(weights, dtype=torch.float),
    }


def resolve_device(name: str) -> torch.device:
    requested = name.lower()
    if requested == "auto":
        if torch.cuda.is_available():
            return torch.device("cuda")
        if torch.backends.mps.is_available():
            return torch.device("mps")
        return torch.device("cpu")
    if requested == "cuda":
        if not torch.cuda.is_available():
            raise RuntimeError("CUDA was requested but torch.cuda.is_available() is false.")
        return torch.device("cuda")
    if requested == "mps":
        if not torch.backends.mps.is_available():
            raise RuntimeError("MPS was requested but torch.backends.mps.is_available() is false.")
        return torch.device("mps")
    if requested == "cpu":
        return torch.device("cpu")
    raise ValueError(f"unsupported device: {name}")


def resolve_dtype(name: str, device: torch.device) -> torch.dtype:
    requested = name.lower()
    if requested == "auto":
        if device.type == "cuda":
            return torch.float16
        return torch.float32
    mapping = {
        "float16": torch.float16,
        "fp16": torch.float16,
        "bfloat16": torch.bfloat16,
        "bf16": torch.bfloat16,
        "float32": torch.float32,
        "fp32": torch.float32,
    }
    if requested not in mapping:
        raise ValueError(f"unsupported dtype: {name}")
    if device.type == "mps" and mapping[requested] == torch.bfloat16:
        raise ValueError("MPS training does not reliably support bfloat16; use --dtype float16 or float32.")
    return mapping[requested]


def model_load_kwargs(base_model: str, device: torch.device, dtype: torch.dtype, device_map: str) -> dict[str, Any]:
    kwargs: dict[str, Any] = {
        "trust_remote_code": True,
        "torch_dtype": dtype,
    }
    if device.type == "cuda" and device_map != "none":
        kwargs["device_map"] = device_map
    return kwargs


def main() -> None:
    parser = argparse.ArgumentParser(description="Train CR-Agent with offline AgenticRL rollouts.")
    parser.add_argument("--episodes", type=Path, default=Path("datasets/RL/episodes.jsonl"))
    parser.add_argument("--rewards", type=Path, default=Path("datasets/RL/rewards.jsonl"))
    parser.add_argument("--base-model", required=True, help="HF model id or local path.")
    parser.add_argument("--model-name", default=None)
    parser.add_argument("--output-root", type=Path, default=Path("model"))
    parser.add_argument("--epochs", type=float, default=1.0)
    parser.add_argument("--lr", type=float, default=1e-5)
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--grad-accum", type=int, default=8)
    parser.add_argument("--max-length", type=int, default=4096)
    parser.add_argument("--warmup-ratio", type=float, default=0.03)
    parser.add_argument("--min-positive-weight", type=float, default=0.05)
    parser.add_argument("--no-lora", action="store_true")
    parser.add_argument("--device", choices=["auto", "cuda", "mps", "cpu"], default="auto")
    parser.add_argument("--dtype", choices=["auto", "float16", "fp16", "bfloat16", "bf16", "float32", "fp32"], default="auto")
    parser.add_argument("--device-map", default="auto", help="CUDA-only transformers device_map value. Use 'none' to disable.")
    args = parser.parse_args()

    device = resolve_device(args.device)
    dtype = resolve_dtype(args.dtype, device)
    model_name = args.model_name or Path(args.base_model.rstrip("/")).name
    output_dir = args.output_root / model_name / "agentic_rl"
    output_dir.mkdir(parents=True, exist_ok=True)
    print(f"Training device={device.type} dtype={str(dtype).removeprefix('torch.')}")

    tokenizer = AutoTokenizer.from_pretrained(args.base_model, trust_remote_code=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    rows = build_training_rows(read_jsonl(args.episodes), reward_by_session(args.rewards), args.min_positive_weight)
    dataset = Dataset.from_list(rows).map(lambda row: tokenize_row(row, tokenizer, args.max_length), remove_columns=list(rows[0].keys()))

    model = AutoModelForCausalLM.from_pretrained(
        args.base_model,
        **model_load_kwargs(args.base_model, device, dtype, args.device_map),
    )
    if not args.no_lora:
        from peft import get_peft_model

        model = get_peft_model(model, LoraConfig(
            r=32,
            lora_alpha=64,
            lora_dropout=0.05,
            target_modules=["q_proj", "k_proj", "v_proj", "o_proj"],
            task_type="CAUSAL_LM",
        ))
    if device.type != "cuda" or args.device_map == "none":
        model = model.to(device)

    loader = DataLoader(dataset, batch_size=args.batch_size, shuffle=True, collate_fn=lambda b: collate(b, tokenizer))
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr)
    total_steps = max(1, math.ceil(len(loader) * args.epochs / max(1, args.grad_accum)))
    scheduler = get_linear_schedule_with_warmup(optimizer, int(total_steps * args.warmup_ratio), total_steps)
    model.train()
    input_device = next(model.parameters()).device

    global_step = 0
    optimizer.zero_grad(set_to_none=True)
    for epoch in range(math.ceil(args.epochs)):
        for batch_idx, batch in enumerate(loader, start=1):
            batch = {k: v.to(input_device) for k, v in batch.items()}
            weights = batch.pop("weights")
            outputs = model(**batch)
            loss = outputs.loss * weights.mean()
            (loss / args.grad_accum).backward()
            if batch_idx % args.grad_accum == 0:
                optimizer.step()
                scheduler.step()
                optimizer.zero_grad(set_to_none=True)
                global_step += 1
                if global_step % 10 == 0:
                    print(f"step={global_step} loss={loss.item():.4f} weight={weights.mean().item():.4f}")
        if len(loader) % args.grad_accum != 0:
            optimizer.step()
            scheduler.step()
            optimizer.zero_grad(set_to_none=True)
            global_step += 1

    model.save_pretrained(output_dir)
    tokenizer.save_pretrained(output_dir)
    (output_dir / "agentic_rl_config.json").write_text(json.dumps({
        "episodes": str(args.episodes),
        "rewards": str(args.rewards),
        "rows": len(rows),
        "objective": "reward_weighted_policy_learning",
        "device": device.type,
        "dtype": str(dtype).removeprefix("torch."),
    }, indent=2), encoding="utf-8")
    print(f"AgenticRL model saved to {output_dir}")


if __name__ == "__main__":
    main()
