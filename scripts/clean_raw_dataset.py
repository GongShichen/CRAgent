#!/usr/bin/env python3
"""Clean CR-Agent raw manifests and AgenticRL rollout exports."""

from __future__ import annotations

import argparse
import fnmatch
import glob
import json
from collections import Counter
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
RAW_ROOT = REPO_ROOT / "datasets" / "raw"
DEFAULT_TASKS = RAW_ROOT / "tasks.jsonl"
DEFAULT_DENYLIST = RAW_ROOT / "denylist.json"
DEFAULT_OUTPUT = REPO_ROOT / "datasets" / "clean" / "tasks.clean.jsonl"
DEFAULT_RESULTS_OUTPUT = REPO_ROOT / "datasets" / "clean" / "results.clean.jsonl"
DEFAULT_EPISODES = REPO_ROOT / "datasets" / "RL" / "episodes.jsonl"
DEFAULT_REWARDS = REPO_ROOT / "datasets" / "RL" / "rewards.jsonl"
DEFAULT_EPISODES_OUTPUT = REPO_ROOT / "datasets" / "clean" / "RL" / "episodes.clean.jsonl"
DEFAULT_REWARDS_OUTPUT = REPO_ROOT / "datasets" / "clean" / "RL" / "rewards.clean.jsonl"
DEFAULT_REPORT = REPO_ROOT / "datasets" / "clean" / "clean_report.json"

SUPPORTED_LANGUAGES = {
    "typescript",
    "javascript",
    "python",
    "java",
    "kotlin",
    "go",
    "rust",
    "ruby",
    "c",
    "cpp",
    "csharp",
    "php",
    "swift",
    "objective-c",
}

VALID_MODES = {"pr", "commits", "repo_latest", "repo_audit"}
DOC_EXTENSIONS = {".md", ".rst", ".txt", ".adoc"}
LOCK_NAMES = {
    "package-lock.json",
    "pnpm-lock.yaml",
    "yarn.lock",
    "Gemfile.lock",
    "Cargo.lock",
    "composer.lock",
    "poetry.lock",
    "uv.lock",
}


def main() -> int:
    parser = argparse.ArgumentParser(description="Clean CR-Agent raw dataset manifests.")
    parser.add_argument("--tasks", type=Path, default=DEFAULT_TASKS)
    parser.add_argument("--denylist", type=Path, default=DEFAULT_DENYLIST)
    parser.add_argument("--results-glob", default=str(RAW_ROOT / "runs" / "*" / "results.jsonl"))
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--results-output", type=Path, default=DEFAULT_RESULTS_OUTPUT)
    parser.add_argument("--episodes", type=Path, default=DEFAULT_EPISODES)
    parser.add_argument("--rewards", type=Path, default=DEFAULT_REWARDS)
    parser.add_argument("--episodes-output", type=Path, default=DEFAULT_EPISODES_OUTPUT)
    parser.add_argument("--rewards-output", type=Path, default=DEFAULT_REWARDS_OUTPUT)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--min-changed-lines", type=int, default=20)
    parser.add_argument("--max-changed-lines", type=int, default=1200)
    parser.add_argument("--require-success", action="store_true", help="Keep only tasks that have a completed dataset-run result.")
    parser.add_argument("--allow-empty", action="store_true", help="Write empty outputs instead of failing when no tasks survive.")
    args = parser.parse_args()

    tasks = read_jsonl(args.tasks)
    denylist = load_denylist(args.denylist)
    results = read_results(args.results_glob)
    completed_results = {row.get("task_id"): row for row in results if row.get("status") == "completed"}

    cleaned: list[dict[str, Any]] = []
    cleaned_results: list[dict[str, Any]] = []
    seen_keys: set[str] = set()
    reject_reasons: Counter[str] = Counter()
    kept_by_language: Counter[str] = Counter()
    kept_by_mode: Counter[str] = Counter()

    for task in tasks:
        reason = reject_reason(task, denylist, args.min_changed_lines, args.max_changed_lines)
        task_id = str(task.get("id", ""))
        if reason is None and args.require_success and task_id not in completed_results:
            reason = "missing_completed_result"
        key = dedupe_key(task)
        if reason is None and key in seen_keys:
            reason = "duplicate"
        if reason is not None:
            reject_reasons[reason] += 1
            continue
        seen_keys.add(key)
        normalized = normalize_task(task)
        cleaned.append(normalized)
        kept_by_language[str(normalized.get("language", "unknown"))] += 1
        kept_by_mode[str(normalized.get("mode", "unknown"))] += 1
        if task_id in completed_results:
            cleaned_results.append(completed_results[task_id])

    if not cleaned and not args.allow_empty:
        raise SystemExit("No tasks survived cleaning. Use --allow-empty to write empty outputs intentionally.")

    write_jsonl(args.output, cleaned)
    write_jsonl(args.results_output, cleaned_results)
    cleaned_episodes, episode_rejects = clean_rl_episodes(read_jsonl(args.episodes))
    cleaned_rewards, reward_rejects = clean_rl_rewards(read_jsonl(args.rewards), {str(row.get("session_id", "")) for row in cleaned_episodes})
    write_jsonl(args.episodes_output, cleaned_episodes)
    write_jsonl(args.rewards_output, cleaned_rewards)
    report = {
        "tasks_input": str(args.tasks),
        "results_glob": args.results_glob,
        "tasks_read": len(tasks),
        "results_read": len(results),
        "tasks_kept": len(cleaned),
        "results_kept": len(cleaned_results),
        "episodes_read": len(read_jsonl(args.episodes)),
        "episodes_kept": len(cleaned_episodes),
        "rewards_read": len(read_jsonl(args.rewards)),
        "rewards_kept": len(cleaned_rewards),
        "require_success": args.require_success,
        "reject_reasons": dict(sorted(reject_reasons.items())),
        "episode_reject_reasons": dict(sorted(episode_rejects.items())),
        "reward_reject_reasons": dict(sorted(reward_rejects.items())),
        "kept_by_language": dict(sorted(kept_by_language.items())),
        "kept_by_mode": dict(sorted(kept_by_mode.items())),
        "outputs": {
            "tasks": str(args.output),
            "results": str(args.results_output),
            "episodes": str(args.episodes_output),
            "rewards": str(args.rewards_output),
            "report": str(args.report),
        },
    }
    write_json(args.report, report)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


def clean_rl_episodes(rows: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], Counter[str]]:
    cleaned: list[dict[str, Any]] = []
    rejects: Counter[str] = Counter()
    seen_sessions: set[str] = set()
    for row in rows:
        reason = rl_episode_reject_reason(row)
        session_id = str(row.get("session_id", ""))
        if reason is None and session_id in seen_sessions:
            reason = "duplicate_session"
        if reason is not None:
            rejects[reason] += 1
            continue
        seen_sessions.add(session_id)
        cleaned.append(normalize_episode(row))
    return cleaned, rejects


def clean_rl_rewards(rows: list[dict[str, Any]], allowed_sessions: set[str]) -> tuple[list[dict[str, Any]], Counter[str]]:
    cleaned: list[dict[str, Any]] = []
    rejects: Counter[str] = Counter()
    seen_sessions: set[str] = set()
    for row in rows:
        reason = rl_reward_reject_reason(row, allowed_sessions)
        session_id = str(row.get("session_id", ""))
        if reason is None and session_id in seen_sessions:
            reason = "duplicate_session"
        if reason is not None:
            rejects[reason] += 1
            continue
        seen_sessions.add(session_id)
        cleaned.append(normalize_reward(row))
    return cleaned, rejects


def rl_episode_reject_reason(row: dict[str, Any]) -> str | None:
    if row.get("schema_version") != "agentic_rl_episode_v1":
        return "unsupported_schema"
    if not row.get("session_id"):
        return "missing_session_id"
    if not row.get("task_id"):
        return "missing_task_id"
    steps = row.get("steps")
    if not isinstance(steps, list) or not steps:
        return "missing_steps"
    if not any(bool(step.get("done")) for step in steps if isinstance(step, dict)):
        return "missing_done_step"
    if not isinstance(row.get("terminal_reward"), (int, float)):
        return "missing_terminal_reward"
    return None


def rl_reward_reject_reason(row: dict[str, Any], allowed_sessions: set[str]) -> str | None:
    if row.get("schema_version") != "agentic_rl_reward_v1":
        return "unsupported_schema"
    session_id = str(row.get("session_id", ""))
    if not session_id:
        return "missing_session_id"
    if allowed_sessions and session_id not in allowed_sessions:
        return "missing_matching_episode"
    if not isinstance(row.get("terminal_reward"), (int, float)):
        return "missing_terminal_reward"
    if not isinstance(row.get("components"), dict):
        return "missing_components"
    return None


def normalize_episode(row: dict[str, Any]) -> dict[str, Any]:
    keys = ["schema_version", "session_id", "task_id", "task", "status", "done", "terminal_reward", "reward_components", "steps", "trace_events"]
    return {key: row[key] for key in keys if key in row}


def normalize_reward(row: dict[str, Any]) -> dict[str, Any]:
    keys = ["schema_version", "session_id", "task_id", "task", "status", "terminal_reward", "components", "counts", "source"]
    return {key: row[key] for key in keys if key in row}


def reject_reason(task: dict[str, Any], denylist: dict[str, Any], min_changed: int, max_changed: int) -> str | None:
    repo = str(task.get("repo", ""))
    mode = str(task.get("mode", ""))
    language = str(task.get("language", ""))
    if not task.get("id"):
        return "missing_id"
    if mode not in VALID_MODES:
        return "unsupported_mode"
    if language not in SUPPORTED_LANGUAGES:
        return "unsupported_language"
    if denied_repo(repo, denylist):
        return "denylisted_repo"
    if denied_url(str(task.get("url", "")), denylist):
        return "denylisted_url"
    if denied_sha(str(task.get("base", "")), denylist) or denied_sha(str(task.get("head", "")), denylist):
        return "denylisted_sha"
    if mode in {"pr", "commits", "repo_latest"}:
        changed = int_value(task.get("changed_lines"))
        if changed < min_changed:
            return "too_small"
        if changed > max_changed:
            return "too_large"
        files = [str(item) for item in task.get("supported_files", [])]
        if not files:
            return "missing_supported_files"
        if docs_or_dependency_only(files):
            return "docs_or_dependency_only"
    return None


def normalize_task(task: dict[str, Any]) -> dict[str, Any]:
    keys = [
        "id",
        "mode",
        "repo",
        "language",
        "source",
        "url",
        "title",
        "pr",
        "base",
        "head",
        "changed_lines",
        "supported_files",
        "audit_tier",
        "merged_at",
        "pushed_at",
        "size_kb",
        "stargazers_count",
        "root_markers",
        "build_markers",
    ]
    return {key: task[key] for key in keys if key in task}


def dedupe_key(task: dict[str, Any]) -> str:
    mode = str(task.get("mode", ""))
    repo = str(task.get("repo", ""))
    if mode == "pr":
        return f"pr:{repo}:{task.get('pr')}"
    if mode in {"commits", "repo_latest"}:
        return f"commits:{repo}:{task.get('base')}:{task.get('head')}"
    if mode == "repo_audit":
        return f"repo_audit:{repo}"
    return json.dumps(task, sort_keys=True)


def docs_or_dependency_only(files: list[str]) -> bool:
    meaningful = 0
    for file in files:
        lower = file.lower()
        name = Path(lower).name
        if Path(lower).suffix in DOC_EXTENSIONS or name in LOCK_NAMES or "/docs/" in lower or lower.startswith("docs/"):
            continue
        meaningful += 1
    return meaningful == 0


def read_results(pattern: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for path in sorted(glob.glob(pattern)):
        rows.extend(read_jsonl(Path(path)))
    # Keep latest row per task id while preserving deterministic order.
    by_task: dict[str, dict[str, Any]] = {}
    for row in rows:
        task_id = row.get("task_id")
        if task_id:
            by_task[str(task_id)] = row
    return list(by_task.values())


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as fh:
        for line_no, line in enumerate(fh, start=1):
            if not line.strip():
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise ValueError(f"Invalid JSONL at {path}:{line_no}: {exc}") from exc
    return rows


def load_denylist(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    data.setdefault("repos", [])
    data.setdefault("urls", [])
    data.setdefault("shas", [])
    expand_benchmark_denylist(data)
    return data


def expand_benchmark_denylist(data: dict[str, Any]) -> None:
    for root in [REPO_ROOT / "eval" / "vendor" / "code-review-benchmark", REPO_ROOT / "eval" / "martian" / "results"]:
        if not root.exists():
            continue
        for path in root.rglob("*.json"):
            if path.stat().st_size > 5_000_000:
                continue
            text = path.read_text(encoding="utf-8", errors="ignore")
            marker = "https://github.com/"
            start = 0
            while True:
                idx = text.find(marker, start)
                if idx < 0:
                    break
                end_candidates = [text.find(char, idx) for char in ['"', "\\n", " ", ")"]]
                end = min([pos for pos in end_candidates if pos > idx] or [len(text)])
                data["urls"].append(text[idx:end].rstrip("\\,.)]"))
                start = end


def denied_repo(repo: str, denylist: dict[str, Any]) -> bool:
    repo_lower = repo.lower()
    return any(fnmatch.fnmatch(repo_lower, str(pattern).lower()) for pattern in denylist.get("repos", []))


def denied_url(url: str, denylist: dict[str, Any]) -> bool:
    lower = url.lower()
    return bool(lower) and any(str(item).lower() in lower for item in denylist.get("urls", []))


def denied_sha(sha: str, denylist: dict[str, Any]) -> bool:
    return bool(sha) and any(str(item).lower() == sha.lower() for item in denylist.get("shas", []))


def int_value(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        for row in rows:
            fh.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
