#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIMIT="1000"
RUN_ID="$(date -u +%Y%m%d-%H%M%S)"
RESUME="--resume"
LIVE=""
COLLECT_ONLY="false"
RUN_ONLY="false"
EXPORT_ONLY="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --limit)
      LIMIT="$2"
      shift 2
      ;;
    --run-id)
      RUN_ID="$2"
      shift 2
      ;;
    --no-resume)
      RESUME=""
      shift
      ;;
    --live)
      LIVE="--live"
      shift
      ;;
    --collect-only)
      COLLECT_ONLY="true"
      shift
      ;;
    --run-only)
      RUN_ONLY="true"
      shift
      ;;
    --export-only)
      EXPORT_ONLY="true"
      RUN_ONLY="true"
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

mkdir -p "$ROOT/datasets/raw/runs/$RUN_ID"

if [[ "$EXPORT_ONLY" != "true" && "$RUN_ONLY" != "true" ]]; then
  DISCOVERY="auto"
  PER_REPO="$LIMIT"
  if [[ "$PER_REPO" -gt 60 ]]; then
    PER_REPO="60"
  fi
  if [[ -z "${GITHUB_TOKEN:-}" ]]; then
    echo "GITHUB_TOKEN is not set; source collection will use git-first discovery." >&2
    DISCOVERY="git"
  fi
  python3 "$ROOT/scripts/collect_raw_sources.py" \
    --queries "$ROOT/datasets/raw/source_queries.jsonl" \
    --denylist "$ROOT/datasets/raw/denylist.json" \
    --output "$ROOT/datasets/raw/tasks.jsonl" \
    --limit "$LIMIT" \
    --per-repo "$PER_REPO" \
    --discovery "$DISCOVERY"
fi

if [[ "$COLLECT_ONLY" == "true" ]]; then
  exit 0
fi

cd "$ROOT/cr_agent"
if [[ "$EXPORT_ONLY" != "true" ]]; then
  ./gradlew run --args="run-dataset --tasks ../datasets/raw/tasks.jsonl --limit $LIMIT --run-id $RUN_ID $RESUME $LIVE"
fi
./gradlew run --args="export-rl"
./gradlew run --args="export-rewards"
