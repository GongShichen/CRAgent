#!/usr/bin/env python3
"""SFT training entrypoint.

The Java agent exports SFT data to datasets/SFT/sft.jsonl.
This script trains with Python/Transformers/TRL and saves weights under model/<model_name>/.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from datasets import load_dataset
from peft import LoraConfig
from transformers import AutoModelForCausalLM, AutoTokenizer
from trl import SFTConfig, SFTTrainer


def format_messages(example: dict) -> str:
    messages = example.get("messages", [])
    parts: list[str] = []
    for msg in messages:
        role = msg.get("role", "unknown")
        content = msg.get("content")
        if content:
            parts.append(f"<|{role}|>\n{content}")
        if msg.get("tool_calls"):
            parts.append(f"<|assistant_tool_calls|>\n{msg['tool_calls']}")
        if role == "tool":
            parts.append(f"<|tool_result:{msg.get('tool_call_id', '')}|>\n{content or ''}")
    parts.append("<|end|>")
    return "\n".join(parts)


def main() -> None:
    parser = argparse.ArgumentParser(description="Run SFT training for CR-Agent.")
    parser.add_argument("--dataset", type=Path, default=Path("datasets/SFT/sft.jsonl"))
    parser.add_argument("--base-model", required=True, help="HF model id or local path.")
    parser.add_argument("--model-name", default=None, help="Directory name under model/. Defaults to base model basename.")
    parser.add_argument("--output-root", type=Path, default=Path("model"))
    parser.add_argument("--epochs", type=float, default=2.0)
    parser.add_argument("--lr", type=float, default=2e-5)
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--grad-accum", type=int, default=8)
    parser.add_argument("--max-seq-length", type=int, default=4096)
    parser.add_argument("--no-lora", action="store_true")
    args = parser.parse_args()

    model_name = args.model_name or Path(args.base_model.rstrip("/")).name
    output_dir = args.output_root / model_name / "sft"
    output_dir.mkdir(parents=True, exist_ok=True)

    dataset = load_dataset("json", data_files=str(args.dataset), split="train")
    dataset = dataset.map(lambda ex: {"text": format_messages(ex)})

    tokenizer = AutoTokenizer.from_pretrained(args.base_model, trust_remote_code=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    model = AutoModelForCausalLM.from_pretrained(args.base_model, trust_remote_code=True, device_map="auto")
    peft_config = None if args.no_lora else LoraConfig(
        r=32,
        lora_alpha=64,
        lora_dropout=0.05,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj"],
        task_type="CAUSAL_LM",
    )

    config = SFTConfig(
        output_dir=str(output_dir),
        num_train_epochs=args.epochs,
        learning_rate=args.lr,
        per_device_train_batch_size=args.batch_size,
        gradient_accumulation_steps=args.grad_accum,
        max_seq_length=args.max_seq_length,
        logging_steps=10,
        save_strategy="epoch",
        dataset_text_field="text",
    )
    trainer = SFTTrainer(
        model=model,
        args=config,
        train_dataset=dataset,
        processing_class=tokenizer,
        peft_config=peft_config,
    )
    trainer.train()
    trainer.save_model(str(output_dir))
    tokenizer.save_pretrained(str(output_dir))
    print(f"SFT model saved to {output_dir}")


if __name__ == "__main__":
    main()

