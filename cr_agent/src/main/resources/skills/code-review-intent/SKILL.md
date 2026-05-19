---
name: code-review-intent
description: Classify natural language chat input into a code review target intent.
---

# Code Review Intent Router

Classify the user's chat input for CR-Agent. Return exactly one valid JSON object and nothing else.

Schema:

```json
{
  "type": "REPO_AUDIT|COMMITS|PR|REPO_LATEST|HELP|EXIT|UNKNOWN",
  "repo": "owner/name",
  "pr": 123,
  "base": "sha-or-ref",
  "head": "sha-or-ref",
  "dry_run": null,
  "confidence": 0.0,
  "reason": "short reason"
}
```

Rules:

- `REPO_AUDIT`: user explicitly asks to review the whole repository, entire repo, all code, 全量, 全仓库, 整个仓库, 全部代码, repo audit, review whole repo.
- `COMMITS`: user gives two commits, two refs, base/head, from/to, 从/到, or a GitHub compare URL.
- `PR`: user gives a pull request URL, PR number, or `owner/repo #123`.
- `REPO_LATEST`: user only gives a repository without whole-repo language; this means review the latest default-branch commit diff.
- `HELP`: user asks for help.
- `EXIT`: user asks to exit.
- `UNKNOWN`: missing repository or ambiguous target.

Priority:

`PR` > `COMMITS` > `REPO_AUDIT` > `REPO_LATEST`.

If the user says whole/entire/all repo and does not provide PR or two refs, choose `REPO_AUDIT`.

Examples:

Input: `对整个 https://github.com/acme/app.git 做 CR`
Output: `{"type":"REPO_AUDIT","repo":"acme/app","pr":null,"base":null,"head":null,"dry_run":null,"confidence":0.96,"reason":"User requested whole repository review."}`

Input: `review acme/app 从 abc1234 到 def5678`
Output: `{"type":"COMMITS","repo":"acme/app","pr":null,"base":"abc1234","head":"def5678","dry_run":null,"confidence":0.95,"reason":"User provided two refs."}`

Input: `帮我 review https://github.com/acme/app`
Output: `{"type":"REPO_LATEST","repo":"acme/app","pr":null,"base":null,"head":null,"dry_run":null,"confidence":0.82,"reason":"Repository only, no whole-repo wording."}`
