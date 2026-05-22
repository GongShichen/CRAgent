#!/usr/bin/env bash
set -e

# Parse arguments
LIMIT_ARG=""
WORKERS_ARG=""
while [[ "$#" -gt 0 ]]; do
    case $1 in
        --limit) LIMIT_ARG="--limit $2"; shift ;;
        --workers) WORKERS_ARG="--workers $2"; shift ;;
        *) echo "Unknown parameter passed: $1"; exit 1 ;;
    esac
    shift
done

# Define run directory
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
RUN_DIR="$(pwd)/eval/martian/results/run_${TIMESTAMP}"

# Load .env if exists
if [ -f .env ]; then
    export $(grep -v '^#' .env | xargs)
    export MARTIAN_API_KEY="${MARTIAN_API_KEY:-$OPENAI_API_KEY}"
    export MARTIAN_BASE_URL="${MARTIAN_BASE_URL:-$OPENAI_BASE_URL}"
    export MARTIAN_MODEL="${MARTIAN_MODEL:-$OPENAI_MODEL}"
fi

echo "============================================================"
echo "🚀 Starting Full CR-Agent Evaluation Pipeline"
echo "📂 Output Directory: ${RUN_DIR}"
echo "============================================================"

# Step 1: Run Offline Agent Evaluation
echo ""
echo "▶️  [Step 1/3] Running CR-Agent on Benchmark Dataset..."
python3 eval/martian/run_cr_agent_offline.py --output-dir "${RUN_DIR}" ${LIMIT_ARG} ${WORKERS_ARG}
echo "✅ Step 1 Complete."

# Step 2: Run LLM Judge
echo ""
echo "▶️  [Step 2/3] Running LLM Judge..."
# The step3_judge_comments.py script might expect to run from its own directory or takes arguments.
# Let's write a python wrapper to run the evaluation logic using the newly generated data.
export PYTHONPATH="$(pwd)/eval/vendor/code-review-benchmark/offline/code_review_benchmark:$(pwd)"
python3 - "${RUN_DIR}" << 'PYTHON_EOF'
import sys
import json
import asyncio
from pathlib import Path

run_dir = Path(sys.argv[1])
benchmark_file = run_dir / "benchmark_data_with_cr_agent.json"

if not benchmark_file.exists():
    print(f"Error: Could not find {benchmark_file}")
    sys.exit(1)

# Import the judge components
sys.path.append(str(Path("eval/vendor/code-review-benchmark/offline/code_review_benchmark").resolve()))
from step3_judge_comments import LLMJudge, evaluate_review

async def main():
    print("Loading benchmark data...")
    with open(benchmark_file) as f:
        data = json.load(f)
    
    judge = LLMJudge(structured_output=True)
    evaluations = {}
    
    for url, entry in data.items():
        golden_comments = entry.get("golden_comments", [])
        reviews = entry.get("reviews", [])
        cr_agent_review = next((r for r in reviews if r.get("tool") == "cr-agent"), None)
        
        if not cr_agent_review:
            continue
            
        print(f"Evaluating {url}...")
        candidates = [c.get("body") for c in cr_agent_review.get("review_comments", []) if c.get("body")]
        
        # Deduplication logic is simplified here; rely on judge
        eval_result = await evaluate_review(judge, golden_comments, candidates)
        
        if url not in evaluations:
            evaluations[url] = {}
        evaluations[url]["cr-agent"] = eval_result
        
    eval_file = run_dir / "evaluations.json"
    with open(eval_file, "w") as f:
        json.dump(evaluations, f, indent=2)
    print(f"Evaluations saved to {eval_file}")

asyncio.run(main())
PYTHON_EOF
echo "✅ Step 2 Complete."

# Step 3: Summarize Results
echo ""
echo "▶️  [Step 3/3] Summarizing Results..."
python3 eval/martian/summarize_results.py --run-dir "${RUN_DIR}" --evaluations-file "${RUN_DIR}/evaluations.json" > "${RUN_DIR}/summary.json"
echo "✅ Step 3 Complete."

echo ""
echo "📊 Evaluation Summary:"
cat "${RUN_DIR}/summary.json" | grep -A 10 '"micro"'
echo ""
echo "Full summary available at: ${RUN_DIR}/summary.json"
echo "============================================================"
