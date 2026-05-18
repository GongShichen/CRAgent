# CR-Agent Training

Java exports datasets:

```bash
cd cr_agent
./gradlew run --args="export-sft"
./gradlew run --args="export-dpo"
```

The exported files are:

- `datasets/SFT/sft.jsonl`
- `datasets/DPO/dpo.jsonl`

Training remains Python-based:

```bash
pip install -r train/requirements.txt
python train/sft_train.py --base-model Qwen/Qwen2.5-Coder-7B-Instruct --model-name qwen2.5-coder-7b
python train/dpo_train.py --base-model model/qwen2.5-coder-7b/sft --model-name qwen2.5-coder-7b
```

Weights are saved under:

- `model/<model_name>/sft/`
- `model/<model_name>/dpo/`

