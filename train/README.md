# CR-Agent AgenticRL Training

CR-Agent now uses AgenticRL as the training path. The training workflow is built
around rollout episodes and reward labels.

## Export Rollouts

Java runs the agent and exports RL datasets from trace:

```bash
cd cr_agent
./gradlew run --args="export-rl"
./gradlew run --args="export-rewards"
```

The exported files are:

- `datasets/RL/episodes.jsonl`
- `datasets/RL/rewards.jsonl`

`episodes.jsonl` stores full agent rollouts: task metadata, ordered steps,
state messages, available tools, assistant actions, tool observations, rewards,
and `done`.

`rewards.jsonl` stores matching terminal reward labels. The default source is
`heuristic_trace_v1`; benchmark TP/FP/FN or human feedback can replace the
reward labels later while preserving the episode format.

## Collect Raw Rollouts

For fresh non-benchmark training rollouts:

```bash
scripts/run_raw_dataset.sh --limit 1000
```

This collects source tasks, runs the Java agent, and exports:

- `datasets/raw/tasks.jsonl`
- `datasets/raw/runs/<run_id>/manifest.json`
- `cr_agent/data/traces/*.jsonl`
- `datasets/RL/episodes.jsonl`
- `datasets/RL/rewards.jsonl`

To export from existing traces without running new tasks:

```bash
scripts/run_raw_dataset.sh --export-only
```

## Clean RL Data

Clean raw task manifests and RL rollouts before training:

```bash
scripts/clean_raw_dataset.sh --allow-empty
```

Cleaned outputs:

- `datasets/clean/tasks.clean.jsonl`
- `datasets/clean/results.clean.jsonl`
- `datasets/clean/RL/episodes.clean.jsonl`
- `datasets/clean/RL/rewards.clean.jsonl`
- `datasets/clean/clean_report.json`

## Python Environment

Python training uses `uv` and the repository-level virtual environment.

For macOS CPU/MPS or generic CPU environments:

```bash
uv sync
```

For CUDA training, create the same uv environment and then replace PyTorch with
the CUDA wheel that matches your driver/runtime. Example for CUDA 12.4:

```bash
uv sync
uv pip install --upgrade torch --index-url https://download.pytorch.org/whl/cu124
```

The project pins Python through `.python-version` and stores the virtual
environment in `.venv/`.

## Train

Run offline AgenticRL warmup:

```bash
uv run python train/agentic_rl_train.py \
  --base-model Qwen/Qwen2.5-Coder-7B-Instruct \
  --model-name qwen2.5-coder-7b \
  --episodes datasets/clean/RL/episodes.clean.jsonl \
  --rewards datasets/clean/RL/rewards.clean.jsonl
```

Device selection defaults to `auto`: CUDA first, then Apple MPS, then CPU. You
can force a backend explicitly:

```bash
uv run python train/agentic_rl_train.py \
  --base-model Qwen/Qwen2.5-Coder-7B-Instruct \
  --model-name qwen2.5-coder-7b \
  --episodes datasets/clean/RL/episodes.clean.jsonl \
  --rewards datasets/clean/RL/rewards.clean.jsonl \
  --device mps
```

CUDA example:

```bash
uv run python train/agentic_rl_train.py \
  --base-model Qwen/Qwen2.5-Coder-7B-Instruct \
  --model-name qwen2.5-coder-7b \
  --episodes datasets/clean/RL/episodes.clean.jsonl \
  --rewards datasets/clean/RL/rewards.clean.jsonl \
  --device cuda \
  --dtype float16
```

Weights are saved under:

- `model/<model_name>/agentic_rl/`
