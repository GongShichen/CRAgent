#!/usr/bin/env python3
"""Collect fresh non-benchmark GitHub sources for CR-Agent raw dataset runs."""

from __future__ import annotations

import argparse
import datetime as dt
import fnmatch
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
RAW_ROOT = REPO_ROOT / "datasets" / "raw"
DEFAULT_QUERIES = RAW_ROOT / "source_queries.jsonl"
DEFAULT_DENYLIST = RAW_ROOT / "denylist.json"
DEFAULT_OUTPUT = RAW_ROOT / "tasks.jsonl"

PRIMARY_LANGUAGES = {
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
}
SECONDARY_LANGUAGES = {"csharp", "php", "swift"}
CONFIG_LANGUAGES = {"yaml", "json", "dockerfile"}
SUPPORTED_LANGUAGES = PRIMARY_LANGUAGES | SECONDARY_LANGUAGES | CONFIG_LANGUAGES | {"objective-c"}

LANGUAGE_EXTENSIONS = {
    "typescript": {".ts", ".tsx"},
    "javascript": {".js", ".jsx", ".mjs", ".cjs"},
    "python": {".py"},
    "java": {".java"},
    "kotlin": {".kt", ".kts"},
    "go": {".go"},
    "rust": {".rs"},
    "ruby": {".rb", ".gemspec"},
    "csharp": {".cs"},
    "php": {".php"},
    "swift": {".swift"},
    "objective-c": {".m", ".mm"},
    "c": {".c", ".h"},
    "cpp": {".cc", ".cpp", ".cxx", ".hh", ".hpp", ".hxx"},
    "yaml": {".yml", ".yaml"},
    "json": {".json"},
    "dockerfile": {"dockerfile", ".dockerfile"},
}

BUILD_MARKERS = {
    "typescript": {"package.json", "pnpm-lock.yaml", "yarn.lock"},
    "javascript": {"package.json", "pnpm-lock.yaml", "yarn.lock"},
    "python": {"pyproject.toml", "requirements.txt", "setup.cfg", "tox.ini", "pytest.ini"},
    "java": {"pom.xml", "build.gradle", "build.gradle.kts"},
    "kotlin": {"build.gradle", "build.gradle.kts", "settings.gradle.kts"},
    "go": {"go.mod"},
    "rust": {"Cargo.toml"},
    "ruby": {"Gemfile", "Rakefile"},
    "csharp": {".csproj", ".sln", "Directory.Packages.props"},
    "php": {"composer.json"},
    "swift": {"Package.swift", "Podfile", ".xcodeproj", ".xcworkspace"},
    "objective-c": {"Podfile", ".xcodeproj", ".xcworkspace"},
    "c": {"CMakeLists.txt", "Makefile", "compile_commands.json"},
    "cpp": {"CMakeLists.txt", "Makefile", "compile_commands.json"},
}

DOC_EXTENSIONS = {".md", ".rst", ".txt", ".adoc"}
LOCK_PATTERNS = {
    "package-lock.json",
    "pnpm-lock.yaml",
    "yarn.lock",
    "Gemfile.lock",
    "Cargo.lock",
    "composer.lock",
    "poetry.lock",
    "uv.lock",
}
BOT_MARKERS = {"[bot]", "dependabot", "renovate", "github-actions"}


class GitHubClient:
    def __init__(self, token: str, sleep_seconds: float = 0.15) -> None:
        self.token = token
        self.sleep_seconds = sleep_seconds

    def get(self, path: str, params: dict[str, Any] | None = None, accept: str = "application/vnd.github+json") -> Any:
        if params:
            path = path + "?" + urllib.parse.urlencode(params)
        url = "https://api.github.com" + path
        req = urllib.request.Request(url)
        req.add_header("Accept", accept)
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        if self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                body = resp.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            if exc.code == 403 and "rate limit" in detail.lower():
                raise RateLimitError(f"GitHub rate limit exceeded for {path}. Set GITHUB_TOKEN for collection runs.") from exc
            raise RuntimeError(f"GitHub HTTP {exc.code} for {path}: {detail[:500]}") from exc
        finally:
            time.sleep(self.sleep_seconds)
        return json.loads(body)


def main() -> int:
    parser = argparse.ArgumentParser(description="Collect CR-Agent raw dataset source tasks.")
    parser.add_argument("--queries", type=Path, default=DEFAULT_QUERIES)
    parser.add_argument("--denylist", type=Path, default=DEFAULT_DENYLIST)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--limit", type=int, default=1000)
    parser.add_argument("--diff-ratio", type=float, default=0.8)
    parser.add_argument("--since", default=(dt.date.today() - dt.timedelta(days=90)).isoformat())
    parser.add_argument("--until", default=dt.date.today().isoformat())
    parser.add_argument("--per-repo", type=int, default=60)
    parser.add_argument("--token", default=os.environ.get("GITHUB_TOKEN", ""))
    parser.add_argument("--discovery", choices=["auto", "git", "github"], default="auto")
    parser.add_argument("--strict", action="store_true", help="Fail if GitHub collection errors occur.")
    args = parser.parse_args()

    queries = read_jsonl(args.queries)
    denylist = load_denylist(args.denylist)
    client = GitHubClient(args.token)
    discovery = args.discovery
    if discovery == "auto":
        discovery = "github" if args.token else "git"
    diff_target = int(args.limit * args.diff_ratio)
    audit_target = args.limit - diff_target

    diff_candidates: list[dict[str, Any]] = []
    audit_candidates: list[dict[str, Any]] = []
    errors: list[str] = []

    for query in queries:
        if len(diff_candidates) >= diff_target and len(audit_candidates) >= audit_target:
            break
        repo = query["repo"]
        language = query["language"]
        modes = set(query.get("allowed_modes", ["diff"]))
        if denied_repo(repo, denylist):
            continue
        try:
            if discovery == "git":
                if "diff" in modes or "both" in modes:
                    diff_candidates.extend(collect_commit_tasks_git(query, denylist, args.since, args.until, args.per_repo))
                if "repo_audit" in modes or "both" in modes:
                    task = collect_repo_audit_task_git(query, denylist)
                    if task:
                        audit_candidates.append(task)
                continue
            if "diff" in modes or "both" in modes:
                diff_candidates.extend(collect_pr_tasks(client, query, denylist, args.since, args.until, args.per_repo))
            if "repo_audit" in modes or "both" in modes:
                task = collect_repo_audit_task(client, query, denylist)
                if task:
                    audit_candidates.append(task)
        except Exception as exc:  # noqa: BLE001
            message = f"{repo} ({language}): {exc}"
            errors.append(message)
            print(f"warning: {message}", file=sys.stderr)
            if isinstance(exc, RateLimitError):
                break
            if args.strict:
                raise

    if discovery == "github" and len(audit_candidates) < audit_target and not rate_limited(errors):
        audit_candidates.extend(discover_extra_audit_repos(client, queries, denylist, audit_target - len(audit_candidates), args.until, errors, args.strict))

    selected = select_tasks(diff_candidates, audit_candidates, diff_target, audit_target)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as fh:
        for task in selected[: args.limit]:
            fh.write(json.dumps(task, ensure_ascii=False, sort_keys=True) + "\n")

    summary = {
        "output": str(args.output),
        "limit": args.limit,
        "written": len(selected[: args.limit]),
        "diff_candidates": len(diff_candidates),
        "audit_candidates": len(audit_candidates),
        "discovery": discovery,
        "errors": errors[:20],
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def collect_commit_tasks_git(query: dict[str, Any], denylist: dict[str, Any], since: str, until: str, per_repo: int) -> list[dict[str, Any]]:
    repo = query["repo"]
    language = query["language"]
    repo_path = ensure_git_cache(repo)
    heads = git(repo_path, "log", f"--since={since}", f"--until={until}", "--format=%H", f"-n{per_repo}").splitlines()
    out: list[dict[str, Any]] = []
    for head in heads:
        parents = git(repo_path, "rev-list", "--parents", "-n", "1", head).split()
        if len(parents) != 2:
            continue
        base = parents[1]
        files = changed_files_git(repo_path, base, head)
        supported = supported_files(files, language)
        if len(supported) < int(query.get("min_supported_files", 1)):
            continue
        if docs_or_dependency_only(files):
            continue
        changed_lines = sum(int(item.get("additions", 0)) + int(item.get("deletions", 0)) for item in files)
        if changed_lines < int(query.get("min_changed_lines", 20)) or changed_lines > int(query.get("max_changed_lines", 1200)):
            continue
        title = git(repo_path, "log", "-1", "--format=%s", head)
        task = {
            "id": stable_id("commits", repo, base, head),
            "mode": "commits",
            "repo": repo,
            "base": base,
            "head": head,
            "language": language,
            "source": "git_default_branch_commit",
            "url": f"https://github.com/{repo}/compare/{base}...{head}",
            "title": title,
            "changed_lines": changed_lines,
            "supported_files": supported[:30],
            "audit_tier": query.get("audit_tier", "diff_only"),
        }
        if not denied_task(task, denylist):
            out.append(task)
    return out


def collect_repo_audit_task_git(query: dict[str, Any], denylist: dict[str, Any]) -> dict[str, Any] | None:
    repo = query["repo"]
    language = query["language"]
    if denied_repo(repo, denylist):
        return None
    repo_path = ensure_git_cache(repo)
    root = set(git(repo_path, "ls-tree", "--name-only", "HEAD").splitlines())
    build_markers = matched_build_markers(root, language)
    if not build_markers:
        return None
    head = git(repo_path, "rev-parse", "HEAD")
    pushed_at = git(repo_path, "log", "-1", "--format=%cI", "HEAD")
    return {
        "id": stable_id("repo_audit", repo),
        "mode": "repo_audit",
        "repo": repo,
        "language": language,
        "source": "git_repo_seed",
        "url": f"https://github.com/{repo}",
        "head": head,
        "pushed_at": pushed_at,
        "audit_tier": query.get("audit_tier", "small"),
        "root_markers": sorted(root)[:200],
        "build_markers": build_markers,
    }


def collect_pr_tasks(client: GitHubClient, query: dict[str, Any], denylist: dict[str, Any], since: str, until: str, per_repo: int) -> list[dict[str, Any]]:
    repo = query["repo"]
    language = query["language"]
    search = f"repo:{repo} is:pr is:merged merged:{since}..{until}"
    items = client.get("/search/issues", {"q": search, "sort": "updated", "order": "desc", "per_page": min(per_repo, 100)}).get("items", [])
    out: list[dict[str, Any]] = []
    for item in items:
        pr_number = int(item["number"])
        if denied_url(item.get("html_url", ""), denylist) or bot_user(item.get("user", {})):
            continue
        pull = client.get(f"/repos/{repo}/pulls/{pr_number}")
        if not pull.get("merged_at"):
            continue
        if bot_user(pull.get("user", {})):
            continue
        changed_lines = int(pull.get("additions", 0)) + int(pull.get("deletions", 0))
        if changed_lines < int(query.get("min_changed_lines", 20)) or changed_lines > int(query.get("max_changed_lines", 1200)):
            continue
        files = client.get(f"/repos/{repo}/pulls/{pr_number}/files", {"per_page": 100})
        supported = supported_files(files, language)
        if len(supported) < int(query.get("min_supported_files", 1)):
            continue
        if docs_or_dependency_only(files):
            continue
        task = {
            "id": stable_id("pr", repo, pr_number),
            "mode": "pr",
            "repo": repo,
            "pr": pr_number,
            "language": language,
            "source": "github_merged_pr",
            "url": pull.get("html_url", item.get("html_url", "")),
            "title": pull.get("title", item.get("title", "")),
            "merged_at": pull.get("merged_at", ""),
            "changed_lines": changed_lines,
            "supported_files": supported[:30],
            "audit_tier": query.get("audit_tier", "diff_only"),
        }
        if not denied_task(task, denylist):
            out.append(task)
    return out


def collect_repo_audit_task(client: GitHubClient, query: dict[str, Any], denylist: dict[str, Any]) -> dict[str, Any] | None:
    repo = query["repo"]
    language = query["language"]
    meta = client.get(f"/repos/{repo}")
    if meta.get("archived") or meta.get("fork") or denied_repo(repo, denylist):
        return None
    if meta.get("size", 0) > max_repo_size(query):
        return None
    root = root_names(client, repo)
    build_markers = matched_build_markers(root, language)
    if not build_markers:
        return None
    return {
        "id": stable_id("repo_audit", repo),
        "mode": "repo_audit",
        "repo": repo,
        "language": language,
        "source": "github_repo_seed",
        "url": meta.get("html_url", f"https://github.com/{repo}"),
        "pushed_at": meta.get("pushed_at", ""),
        "size_kb": meta.get("size", 0),
        "stargazers_count": meta.get("stargazers_count", 0),
        "audit_tier": query.get("audit_tier", "small"),
        "root_markers": sorted(root)[:200],
        "build_markers": build_markers,
    }


def discover_extra_audit_repos(
    client: GitHubClient,
    queries: list[dict[str, Any]],
    denylist: dict[str, Any],
    needed: int,
    until: str,
    errors: list[str],
    strict: bool,
) -> list[dict[str, Any]]:
    by_language = sorted({q["language"] for q in queries if q.get("audit_tier") != "diff_only"})
    out: list[dict[str, Any]] = []
    for language in by_language:
        if len(out) >= needed:
            break
        gh_language = github_language(language)
        q = f"language:{gh_language} archived:false fork:false pushed:>{older_than(until, 90)} stars:>500"
        try:
            items = client.get("/search/repositories", {"q": q, "sort": "updated", "order": "desc", "per_page": 30}).get("items", [])
        except Exception as exc:  # noqa: BLE001
            message = f"repo search {language}: {exc}"
            errors.append(message)
            print(f"warning: {message}", file=sys.stderr)
            if isinstance(exc, RateLimitError):
                break
            if strict:
                raise
            continue
        for item in items:
            repo = item.get("full_name", "")
            if not repo or denied_repo(repo, denylist) or item.get("size", 0) > max_repo_size({"language": language, "audit_tier": "small"}):
                continue
            try:
                root = root_names(client, repo)
            except Exception:
                continue
            build_markers = matched_build_markers(root, language)
            if not build_markers:
                continue
            out.append({
                "id": stable_id("repo_audit", repo),
                "mode": "repo_audit",
                "repo": repo,
                "language": language,
                "source": "github_repo_search",
                "url": item.get("html_url", f"https://github.com/{repo}"),
                "pushed_at": item.get("pushed_at", ""),
                "size_kb": item.get("size", 0),
                "stargazers_count": item.get("stargazers_count", 0),
                "audit_tier": "small",
                "root_markers": sorted(root)[:200],
                "build_markers": build_markers,
            })
            if len(out) >= needed:
                break
    return out


def select_tasks(diff: list[dict[str, Any]], audit: list[dict[str, Any]], diff_target: int, audit_target: int) -> list[dict[str, Any]]:
    selected = round_robin(diff, diff_target) + round_robin(audit, audit_target)
    seen: set[str] = set()
    deduped: list[dict[str, Any]] = []
    for task in selected:
        if task["id"] not in seen:
            seen.add(task["id"])
            deduped.append(task)
    return deduped


def round_robin(tasks: list[dict[str, Any]], limit: int) -> list[dict[str, Any]]:
    buckets: dict[str, list[dict[str, Any]]] = {}
    for task in tasks:
        buckets.setdefault(task.get("language", "unknown"), []).append(task)
    out: list[dict[str, Any]] = []
    while len(out) < limit and any(buckets.values()):
        for language in sorted(buckets):
            bucket = buckets[language]
            if bucket:
                out.append(bucket.pop(0))
                if len(out) >= limit:
                    break
    return out


def supported_files(files: list[dict[str, Any]], language: str) -> list[str]:
    out = []
    for item in files:
        path = item.get("filename", "")
        detected = detect_language(path)
        if detected == language or detected in alias_languages(language):
            out.append(path)
    return out


def docs_or_dependency_only(files: list[dict[str, Any]]) -> bool:
    meaningful = 0
    for item in files:
        path = item.get("filename", "")
        lower = path.lower()
        suffix = Path(lower).suffix
        name = Path(path).name
        if suffix in DOC_EXTENSIONS or name in LOCK_PATTERNS or "/docs/" in lower or lower.startswith("docs/"):
            continue
        if detect_language(path) in SUPPORTED_LANGUAGES:
            meaningful += 1
    return meaningful == 0


def changed_files_git(repo_path: Path, base: str, head: str) -> list[dict[str, Any]]:
    statuses: dict[str, str] = {}
    for line in git(repo_path, "diff", "--name-status", "--find-renames", base, head).splitlines():
        parts = line.split("\t")
        if len(parts) >= 2:
            path = parts[-1]
            statuses[path] = parts[0][:1]
    files = []
    for line in git(repo_path, "diff", "--numstat", "--find-renames", base, head).splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        path = parts[-1]
        files.append({
            "filename": path,
            "status": status_name(statuses.get(path, "M")),
            "additions": parse_numstat(parts[0]),
            "deletions": parse_numstat(parts[1]),
        })
    return files


def ensure_git_cache(repo: str) -> Path:
    cache_root = RAW_ROOT / ".cache" / "repos"
    cache_root.mkdir(parents=True, exist_ok=True)
    target = cache_root / repo.replace("/", "__")
    url = f"https://github.com/{repo}.git"
    if not (target / ".git").exists():
        run(["git", "clone", "--quiet", "--filter=blob:none", url, str(target)], timeout=180)
    else:
        run(["git", "-C", str(target), "fetch", "--quiet", "--prune", "--filter=blob:none"], timeout=120)
    return target


def git(repo_path: Path, *args: str) -> str:
    return run(["git", "-C", str(repo_path), *args], timeout=60)


def run(command: list[str], timeout: int) -> str:
    completed = subprocess.run(command, check=False, capture_output=True, text=True, timeout=timeout)
    if completed.returncode != 0:
        raise RuntimeError(f"{' '.join(command)} failed: {completed.stderr.strip() or completed.stdout.strip()}")
    return completed.stdout.strip()


def status_name(status: str) -> str:
    return {"A": "added", "D": "removed", "R": "renamed"}.get(status, "modified")


def parse_numstat(value: str) -> int:
    return 0 if value == "-" else int(value)


def detect_language(path: str) -> str:
    lower = path.lower()
    name = Path(lower).name
    if name == "dockerfile" or lower.endswith(".dockerfile"):
        return "dockerfile"
    if name == "gemfile" or lower.endswith(".gemspec"):
        return "ruby"
    for language, extensions in LANGUAGE_EXTENSIONS.items():
        if any(lower.endswith(ext) for ext in extensions):
            return language
    return "text"


def alias_languages(language: str) -> set[str]:
    if language == "typescript":
        return {"javascript"}
    if language == "cpp":
        return {"c", "c-header", "objective-c"}
    if language == "swift":
        return {"objective-c"}
    return set()


def root_names(client: GitHubClient, repo: str) -> set[str]:
    items = client.get(f"/repos/{repo}/contents", {"per_page": 100})
    return {item.get("name", "") for item in items if isinstance(item, dict)}


def has_build_marker(root: set[str], language: str) -> bool:
    return bool(matched_build_markers(root, language))


def matched_build_markers(root: set[str], language: str) -> list[str]:
    markers = BUILD_MARKERS.get(language, set())
    matched: list[str] = []
    for marker in markers:
        if marker.startswith("."):
            matched.extend(sorted(name for name in root if name.endswith(marker)))
        elif marker in root:
            matched.append(marker)
    return sorted(set(matched))


def max_repo_size(query: dict[str, Any]) -> int:
    if query.get("max_repo_size_kb"):
        return int(query["max_repo_size_kb"])
    if query.get("language") in SECONDARY_LANGUAGES:
        return 80_000
    tier = query.get("audit_tier", "medium")
    return {"small": 40_000, "medium": 120_000, "large": 250_000}.get(tier, 120_000)


def github_language(language: str) -> str:
    return {
        "typescript": "TypeScript",
        "javascript": "JavaScript",
        "python": "Python",
        "java": "Java",
        "kotlin": "Kotlin",
        "go": "Go",
        "rust": "Rust",
        "ruby": "Ruby",
        "c": "C",
        "cpp": "C++",
        "csharp": "C#",
        "php": "PHP",
        "swift": "Swift",
    }.get(language, language)


def older_than(until: str, days: int) -> str:
    value = dt.date.fromisoformat(until)
    return (value - dt.timedelta(days=days)).isoformat()


def bot_user(user: dict[str, Any]) -> bool:
    login = str(user.get("login", "")).lower()
    user_type = str(user.get("type", "")).lower()
    return user_type == "bot" or any(marker in login for marker in BOT_MARKERS)


def denied_task(task: dict[str, Any], denylist: dict[str, Any]) -> bool:
    return denied_repo(task.get("repo", ""), denylist) or denied_url(task.get("url", ""), denylist)


def rate_limited(errors: list[str]) -> bool:
    return any("rate limit" in error.lower() for error in errors)


def denied_repo(repo: str, denylist: dict[str, Any]) -> bool:
    repo_lower = repo.lower()
    for pattern in denylist.get("repos", []):
        if fnmatch.fnmatch(repo_lower, pattern.lower()):
            return True
    return False


def denied_url(url: str, denylist: dict[str, Any]) -> bool:
    lowered = url.lower()
    return any(str(item).lower() in lowered for item in denylist.get("urls", []))


def stable_id(*parts: Any) -> str:
    raw = ":".join(str(part) for part in parts)
    digest = hashlib.sha1(raw.encode("utf-8")).hexdigest()[:12]
    return f"{parts[0]}-{digest}"


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    with path.open("r", encoding="utf-8") as fh:
        for line in fh:
            if line.strip():
                rows.append(json.loads(line))
    return rows


class RateLimitError(RuntimeError):
    pass


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
            try:
                text = path.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            for marker in ["https://github.com/"]:
                start = 0
                while True:
                    idx = text.find(marker, start)
                    if idx < 0:
                        break
                    end = min([pos for pos in [text.find('"', idx), text.find("\\n", idx), text.find(" ", idx)] if pos > idx] or [len(text)])
                    data["urls"].append(text[idx:end].rstrip("\\,.)]"))
                    start = end


if __name__ == "__main__":
    raise SystemExit(main())
