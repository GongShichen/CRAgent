---
name: code-review-java-runtime
description: |
  Java implementation runtime contract for the code review agent. This skill
  overrides legacy Claude/MCP wording in the reference skills so model output
  matches the Java agent parser, tool names, trace format, and dry-run behavior.
---

# Java Code Review Runtime Contract

This Java agent already performs `Triage -> Analyze -> Review -> Act -> Report`
outside the Review model call. During the Review phase, focus on producing
precise review findings from the provided `triage` and `analysis` JSON. Do not
restate the workflow or produce a human-facing final report; the dedicated
Report node loads `code-review-report` and writes the final Markdown report.

## Output Contract

Return exactly one valid JSON object and nothing else. Do not wrap it in
Markdown fences, tables, headings, or explanations.

Invalid output examples: ```json fenced blocks, Markdown headings, prose before
the opening `{`, prose after the closing `}`, or a final report template.

Canonical schema:

```json
{
  "summary": "2-3 concise sentences about the PR risk and main findings.",
  "issues": [
    {
      "severity": "critical|high|medium|low|info",
      "category": "security|bug|performance|maintainability|tests|style|general",
      "file": "path/from/diff",
      "line": 1,
      "body": "Specific problem supported by the diff.",
      "evidence": "Exact changed line, config, check result, or memory rule that supports the finding.",
      "impact": "Concrete production failure mode or security/operational consequence.",
      "suggestion": "Concrete fix direction.",
      "autoFixable": false,
      "fixCode": null,
      "confidence": 0.0
    }
  ],
  "shouldComment": true,
  "shouldCreateFixPr": false,
  "shouldUpdateMemory": true
}
```

Use the exact Java field names above. The parser accepts snake_case aliases, but
camelCase is preferred.

## Few-Shot Output Examples

These examples show the exact assistant message shape. In real output, return
only the JSON object. Do not include Markdown fences around it.

### Example 1: no actionable issue

Input situation: the diff only updates documentation wording and does not change
runtime behavior.

Assistant output:

{
  "summary": "This PR only clarifies documentation wording for an existing option. No executable code, API contract, security boundary, or test behavior changes were found.",
  "issues": [],
  "shouldComment": false,
  "shouldCreateFixPr": false,
  "shouldUpdateMemory": true
}

### Example 2: valid JSON when suggestion contains quotes

Input situation: a frontend loader reports a module resolution error, but the
new error message should name the failing specifier. The suggestion needs to
include quoted text.

Assistant output:

{
  "summary": "The PR adds a new frontend loader path. The main behavior is sound, but one error path should produce a clearer diagnostic.",
  "issues": [
    {
      "severity": "medium",
      "category": "maintainability",
      "file": "packages/app/src/loaders/client-loader.ts",
      "line": 41,
      "body": "A failed injected-module resolution currently bubbles up as a generic bundler error.",
      "evidence": "The changed code resolves every configured specifier with Promise.all(...) and does not wrap the failing specifier in the resulting error.",
      "impact": "Users who mistype the new config may see a confusing build failure that does not identify the config option or the failing module.",
      "suggestion": "Wrap the error with a message such as \"Failed to resolve instrumentationClientInject specifier \\\"${spec}\\\": ${err.message}\".",
      "autoFixable": false,
      "fixCode": null,
      "confidence": 0.78
    }
  ],
  "shouldComment": true,
  "shouldCreateFixPr": false,
  "shouldUpdateMemory": true
}

Important: the inner quotes around `${spec}` are escaped as `\\\"${spec}\\\"`.
Never emit raw unescaped quotes inside a JSON string.

### Example 3: concrete regression test gap

Input situation: the diff changes user-visible behavior in a backend encoder and
adds no related tests.

Assistant output:

{
  "summary": "The PR changes iterable encoding behavior in a shared backend utility. The implementation looks plausible, but the changed behavior lacks a focused regression test.",
  "issues": [
    {
      "severity": "medium",
      "category": "tests",
      "file": "fastapi/encoders.py",
      "line": 152,
      "body": "The encoder now accepts additional iterable-like values, but there is no regression test for a custom iterable that is not already covered by the existing list/tuple/set cases.",
      "evidence": "The diff changes the fallback iterable branch in fastapi/encoders.py, while the changed files do not include a test asserting a custom iterable object is encoded correctly.",
      "impact": "Future refactors could accidentally drop support for the newly accepted iterable shape without failing tests.",
      "suggestion": "Add a focused test with a small custom iterable class and assert jsonable_encoder returns the expected list-like output.",
      "autoFixable": false,
      "fixCode": null,
      "confidence": 0.82
    }
  ],
  "shouldComment": true,
  "shouldCreateFixPr": false,
  "shouldUpdateMemory": true
}

## Evidence Rules

- Only report issues evidenced by the PR diff, full file content, checks,
  commits, or memory rules included in the user message.
- Inline `line` must be a changed line from the diff when known. Use `null` if
  the exact changed line is not available.
- Avoid broad best-practice comments unless they point to a concrete regression
  or risk in this PR.
- Do not report test gaps when the changed code is documentation/config only, or
  when triage says the PR was skipped.
- Keep `confidence < 0.6` for uncertain findings; uncertain findings should be
  `low` or `info`.

## Deep Review Protocol

For each candidate finding, reason through these gates before including it:

1. **Changed behavior**: What behavior, data flow, security boundary, transaction
   boundary, concurrency behavior, or API contract changed?
2. **Executable failure mode**: What concrete bad outcome can happen in
   production or tests? Avoid comments that are only taste or generic hygiene.
3. **Evidence**: Which exact diff line, full-content snippet, CI/check signal,
   commit message, or memory rule supports the finding?
4. **Minimal fix**: What is the smallest correct fix or design adjustment?
5. **False-positive check**: Could this be test code, generated code, config-only
   change, intentionally documented behavior, or a local-only sample?
6. **Severity calibration**:
   - `critical`: exploitable security issue, data loss, auth bypass, secret leak,
     or outage-class regression with clear evidence.
   - `high`: likely production bug/security flaw affecting real users or data.
   - `medium`: plausible correctness, reliability, performance, or test gap that
     should be fixed before merge.
   - `low`: maintainability issue with clear local evidence.
   - `info`: non-blocking observation. Prefer omitting info findings unless useful.

Do not include a finding unless it passes the changed behavior, executable
failure mode, and evidence gates. It is better to return zero issues than to
invent shallow checklist comments.

## Four Review Sub-Strategies

The runtime provides these strategy outputs in the user message under
`review_strategy`, `context_engine`, and `analysis`:

1. **Context Expansion**: extra repository context, including dependency
   manifests, sensitive paths, related tests, security-sensitive file contents,
   and surrounding source lines.
2. **Risk Modeling**: explicit risk types such as `security/auth`,
   `data/migration`, `concurrency/async`, `api/contract`,
   `dependency/build`, `test-only`, `docs-only`, and
   `behavior-without-test-change`. Use these to prioritize what to inspect.
3. **Regression/Test Reasoning**: a structured assessment of behavior changes,
   test changes, related tests, likely test gaps, and files needing test
   consideration.
4. **Evidence Validation**: a runtime gate after model output that filters
   unsupported findings. Make that gate easy to pass by providing exact
   `evidence`, concrete `impact`, calibrated `severity`, and a valid changed
   `line` or `null`.

## Context Engine Protocol

Prefer `context_engine.context_pack.items` over raw, bulky context. Each context
item has an `id`, `type`, `path`, `reason`, `score`, and compact `content`.
When a finding relies on non-diff context, cite the context item id in
`evidence`, for example `ctx-4 shows the related test only covers the success
path`.

Use `context_engine.context_index` for orientation and file/symbol discovery,
not as standalone proof. Use `context_engine.context_ledger` to understand which
items were selected and which were compressed. Compressed items are weak
orientation only; request or use stronger evidence before reporting a finding.

## Bounded Tool-Use Nodes

Some non-primary review nodes have limited ReAct-style tool access:

- **Zero-Issue Recovery** may use search, surrounding lines, related tests, LSP,
  and evidence bundle tools to confirm high-risk probes after an empty first
  pass.
- **Candidate Verifier** may use evidence bundle, LSP, surrounding lines, tests,
  or code search once to decide `KEEP`, `DROP`, or `DEMOTE`.
- **Repo Audit Batch Review** may use local repo, LSP, static/security,
  contract, migration, CI, Docker, lockfile, and license tools for the current
  batch only.

These nodes must keep tool use narrow. Tool results are supporting evidence, not
permission to review unrelated files or invent findings outside the active
target.

## Depth Targets

Actively inspect for:

- broken auth/authz assumptions and user-controlled data crossing trust
  boundaries
- injection, XSS, path traversal, SSRF, insecure deserialization, weak crypto,
  and accidental secret/token exposure
- transaction boundaries, partial writes, idempotency, retries, duplicate side
  effects, and race conditions
- null/empty/error paths, pagination/limits, timeouts, cancellation, and resource
  cleanup
- API compatibility, schema migrations, serialization contracts, and feature flag
  rollout behavior
- missing tests only when the diff adds meaningful behavior or fixes a bug and
  there is no corresponding test evidence

## Tool Contract

If tool calls are needed, use the tool names exactly as exposed by the Java
runtime. Do not prefix names with `mcp__github__`, `github__`, or slash commands.

Available GitHub tools include:

- `get_pull_request`
- `get_pull_request_files`
- `list_changed_files`
- `get_pr_diff`
- `list_commits`
- `get_file_contents`
- `list_review_comments`
- `list_checks`
- `get_pull_request_status`
- `search_code`
- `list_repository_tree`
- `get_surrounding_lines`
- `find_related_tests`
- `get_dependency_manifests`
- `scan_sensitive_paths`
- `create_pull_request_review`
- `submit_review_comments`
- `create_branch`
- `create_or_update_file`
- `create_pull_request`
- `add_issue_comment`
- `detect_test_framework`
- `lsp_detect_servers`
- `lsp_capabilities`
- `lsp_changed_symbols`
- `lsp_symbol_at_position`
- `lsp_call_graph`
- `lsp_related_tests_by_symbol`
- `lsp_evidence_bundle`
- `lsp_diagnostics`

Available memory and test tools include:

- `memory_get_all`
- `memory_add_rule`
- `memory_add_pattern`
- `memory_add_false_positive`
- `memory_get_developer_profile`
- `memory_update_developer_profile`
- `memory_aggregate_patterns`
- `memory_health_report`
- `infer_test_path`
- `generate_tests_for_changes`

Available advanced review tools include:

- `run_codeql_scan`
- `run_semgrep_scan`
- `run_secret_scan`
- `run_dependency_vulnerability_scan`
- `candidate_evidence_bundle`
- `git_blame_context`
- `git_recent_file_changes`
- `git_churn_hotspots`
- `git_bugfix_history_search`
- `detect_public_api_changes`
- `route_contract_diff`
- `openapi_schema_diff`
- `protobuf_schema_diff`
- `db_migration_risk_analyzer`
- `select_impacted_tests`
- `run_targeted_tests_readonly`
- `coverage_for_changed_symbols`
- `mutation_probe_plan`
- `github_actions_permission_audit`
- `dockerfile_risk_scan`
- `lockfile_diff_summary`
- `sbom_generate`
- `license_policy_check`

Prefer answering from the supplied analysis context when it is enough. The Java
agent already collects PR metadata, changed files, diff, comments, checks,
commits, test framework, dependency manifests, sensitive paths, related tests,
security-sensitive file contents, and memory before asking for a review.

Use extra read tools when a finding depends on context outside the diff:

- `get_surrounding_lines`: verify surrounding control flow, resource cleanup,
  transaction boundaries, or line-specific evidence.
- `find_related_tests`: determine whether a behavior change has nearby tests.
- `search_code`: find callers, duplicated patterns, config keys, or API contract
  usages.
- `get_dependency_manifests`: inspect dependency/test/build changes when the
  diff touches manifests or imports new libraries.
- `scan_sensitive_paths`: orient around auth, security, payment, migration, and
  config-heavy repositories.
- `lsp_capabilities`: check which read-only LSP servers/tools are available
  before requesting symbol-level evidence.
- `lsp_changed_symbols`: map changed diff lines to enclosing classes,
  functions, methods, or modules.
- `lsp_symbol_at_position`: find the nearest symbol when an issue only has
  `file` + `line`.
- `lsp_call_graph`: collect hover, definition, and references for API contract
  or call-site impact claims.
- `lsp_related_tests_by_symbol`: check whether changed behavior has symbol-level
  tests.
- `lsp_evidence_bundle`: package source excerpt, symbol, hover, definition,
  references, and related tests for a candidate issue.
- `candidate_evidence_bundle`: before publishing an uncertain candidate, gather
  the hunk, source excerpt, related tests, blame/churn, and static evidence in
  one compact bundle.
- `run_codeql_scan`, `run_semgrep_scan`, `run_secret_scan`, and
  `run_dependency_vulnerability_scan`: use scanner output as supporting
  evidence for security, dependency, and data-flow findings. Do not turn every
  scanner alert into a review comment; correlate it with changed behavior first.
- `detect_public_api_changes`, `route_contract_diff`, `openapi_schema_diff`,
  `protobuf_schema_diff`, and `db_migration_risk_analyzer`: use these when the
  diff changes public contracts, routes, schemas, or migrations.
- `select_impacted_tests`, `run_targeted_tests_readonly`,
  `coverage_for_changed_symbols`, and `mutation_probe_plan`: use these to make
  test-gap findings concrete and to identify the smallest meaningful regression
  checks.
- `git_blame_context`, `git_recent_file_changes`, `git_churn_hotspots`, and
  `git_bugfix_history_search`: use history to distinguish risky hot paths from
  low-impact edits, not to assign blame.
- `github_actions_permission_audit`, `dockerfile_risk_scan`,
  `lockfile_diff_summary`, `sbom_generate`, and `license_policy_check`: use
  these for CI, container, dependency, and supply-chain reviews.

All LSP and advanced scanner tools are read-only. External CLIs may be missing
or fail in a specific repository; treat `unavailable`, `skipped`, and `failed`
tool results as non-blocking context and continue using diff, repository
context, static checks, and language skills.

When reviewing language-specific code, apply the dedicated checklist sections
for Java/Spring/JVM, Rust, JavaScript/TypeScript frontend, Go, Swift/iOS,
C/C++, Objective-C/Cocoa, Ruby/Rails, PHP, and C#/.NET. Keep findings specific
to the changed lines rather than turning those checklists into generic style
advice.

## Runtime Differences From Reference Skills

- Memory is stored under `CR_AGENT_MEMORY_DIR`, not `~/.claude/review_memory.jsonl`.
- Write tools may be intercepted in dry-run mode and return structured
  `dry_run=true` results instead of mutating GitHub.
- The Java agent records JSONL trace events such as `session_start`,
  `phase_start`, `llm_request`, `llm_response`, `tool_call`, `tool_result`,
  `issue_found`, `action_taken`, `memory_update`, and `session_end`.
- Do not include emoji-heavy final report templates in model output; the CLI
  formats the final summary itself.
