#!/usr/bin/env python3
"""DPO training entrypoint.

The Java agent exports preference data to datasets/DPO/dpo.jsonl.
This script trains with Python/Transformers/TRL and saves weights under model/<model_name>/.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from datasets import load_dataset
from peft import LoraConfig
from transformers import AutoModelForCausalLM, AutoTokenizer
from trl import DPOConfig, DPOTrainer


def main() -> None:
    parser = argparse.ArgumentParser(description="Run DPO training for CR-Agent.")
    parser.add_argument("--dataset", type=Path, default=Path("datasets/DPO/dpo.jsonl"))
    parser.add_argument("--base-model", required=True, help="HF model id or local path.")
    parser.add_argument("--model-name", default=None, help="Directory name under model/. Defaults to base model basename.")
    parser.add_argument("--output-root", type=Path, default=Path("model"))
    parser.add_argument("--epochs", type=float, default=1.0)
    parser.add_argument("--lr", type=float, default=5e-6)
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--grad-accum", type=int, default=8)
    parser.add_argument("--beta", type=float, default=0.1)
    parser.add_argument("--max-length", type=int, default=4096)
    parser.add_argument("--max-prompt-length", type=int, default=2048)
    parser.add_argument("--no-lora", action="store_true")
    args = parser.parse_args()

    model_name = args.model_name or Path(args.base_model.rstrip("/")).name
    output_dir = args.output_root / model_name / "dpo"
    output_dir.mkdir(parents=True, exist_ok=True)

    dataset = load_dataset("json", data_files=str(args.dataset), split="train")
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

    config = DPOConfig(
        output_dir=str(output_dir),
        num_train_epochs=args.epochs,
        learning_rate=args.lr,
        per_device_train_batch_size=args.batch_size,
        gradient_accumulation_steps=args.grad_accum,
        beta=args.beta,
        max_length=args.max_length,
        max_prompt_length=args.max_prompt_length,
        logging_steps=10,
        save_strategy="epoch",
    )
    trainer = DPOTrainer(
        model=model,
        args=config,
        train_dataset=dataset,
        processing_class=tokenizer,
        peft_config=peft_config,
    )
    trainer.train()
    trainer.save_model(str(output_dir))
    tokenizer.save_pretrained(str(output_dir))
    print(f"DPO model saved to {output_dir}")


if __name__ == "__main__":
    main()

