---
name: code-review
description: |
  自动化代码 Review 系统。对 GitHub PR 进行代码质量检查、安全漏洞扫描、
  规范审查，并对明确问题自动提交修复 PR。
  触发场景：用户提到 PR review、代码审查、review PR #N、check this PR、
  代码检查、安全扫描、自动修复代码问题、帮我看看这个 PR。
  关键词：PR、pull request、review、代码审查、安全漏洞、自动修复、代码检查。
  Make sure to use this skill whenever the user mentions reviewing a PR,
  checking code quality, scanning for security issues, or auto-fixing code,
  even if they don't explicitly say "code review".
---

# Code Review

自动化代码 Review 系统的主入口。路由到各子 skill 完成完整的 Review 流程。

## 快速开始

**用法**：`/code-review owner/repo PR #42`
**或**：直接说 "帮我 review 一下 github.com/owner/repo/pull/42"

## 整体流程

```
用户输入
  ↓
[Step 1] 解析输入 → 提取 owner/repo + pr_number
  ↓
[Step 2] /code-review-triage → ReviewPlan
  ↓ human_required?
  ├── YES → 在 PR 添加说明 comment → 结束
  └── NO ↓
[Step 3] /code-review-analyze → AnalysisData
  ↓
[Step 4] /code-review-act → Review + 可选 Fix PR
  ↓
[Step 5] /code-review-memory → 更新知识图谱
  ↓
输出 Review 摘要
```

## Step 1：解析输入

从用户输入中提取：
- `owner`：仓库所有者（用户名或组织）
- `repo`：仓库名称
- `pr_number`：PR 编号

支持的输入格式：
- `owner/repo #42`
- `owner/repo PR 42`
- `github.com/owner/repo/pull/42`
- `https://github.com/owner/repo/pull/42`

若无法解析，询问用户：
```
请提供 PR 信息，格式：owner/repo #PR编号
例如：anthropics/claude-code #123
```

## Step 2：Triage（分类）

调用 `/code-review-triage`，传入：
- `owner/repo`
- `pr_number`

**若返回 `human_required`**：
```python
mcp__github__add_issue_comment(
  owner=owner,
  repo=repo,
  issue_number=pr_number,
  body=f"""## 🤖 自动 Review 已跳过

**原因**：{skip_reason}

此 PR 需要人工 Review，原因如下：
- {human_required_details}

请团队成员手动进行代码审查。"""
)
```
然后结束流程，向用户展示跳过原因。

## Step 3：数据采集

调用 `/code-review-analyze`，传入：
- `owner/repo`
- `pr_number`
- `files_to_review`（来自 triage）
- `focus_areas`（来自 triage）

## Step 4：Review + 行动

调用 `/code-review-act`，传入：
- `analysis_data`（来自 analyze）
- `review_plan`（来自 triage）
- `owner/repo`
- `pr_number`

## Step 5：更新 Memory

调用 `/code-review-memory` 更新知识图谱（由 code-review-act 内部调用，无需额外步骤）。

## 最终输出格式

```
## ✅ Code Review 完成

**仓库**: owner/repo
**PR**: #42 - {PR 标题}
**作者**: @{author}

### Review 结果
- **决策**: ✅ APPROVE | 🔄 REQUEST_CHANGES | 💬 COMMENT
- **发现问题**: {n} 个
  - 🔴 严重: {n}
  - 🟠 高危: {n}
  - 🟡 中等: {n}
  - 🟢 低危: {n}

### 自动修复
{有修复: "已创建 Fix PR: #{fix_pr_number}" | 无修复: "无可自动修复的问题"}

### 摘要
{review_summary}
```

## 参考文档

- `references/review-checklist.md`：完整的代码审查清单（安全/规范/逻辑/性能）
- `references/escalation-rules.md`：人工介入规则详情

## 子 Skill 说明

| Skill | 职责 | LLM 调用 |
|-------|------|----------|
| `code-review-triage` | PR 分类 | 1次 |
| `code-review-analyze` | 数据采集 | 0次 |
| `code-review-act` | Review + 修复 | 1次 |
| `code-review-memory` | 知识图谱 | 0次 |

**总计：≤ 2 次 LLM 调用/PR**（triage 1次 + review 1次）
