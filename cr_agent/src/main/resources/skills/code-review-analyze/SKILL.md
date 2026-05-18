---
name: code-review-analyze
description: |
  并发采集 PR 分析所需的所有数据：diff、安全告警、提交历史、Memory 规则。
  内部 skill，由 code-review 调用。也可单独触发：
  "fetch PR data", "get PR diff and alerts", "analyze PR #N data",
  "获取 PR 数据", "采集 PR 分析信息", "获取代码变更和安全告警"。
  Make sure to use this skill whenever the user asks to fetch or collect
  PR data, diff, security alerts, or commit history for analysis.
---

# Code Review Analyze

并发采集 PR 分析所需的全部数据，为 code-review-act 准备输入。**0 LLM 调用**。

## 输入

- `owner/repo`：仓库标识
- `pr_number`：PR 编号
- `files_to_review`：来自 triage 的优先文件列表
- `focus_areas`：来自 triage 的关注领域

## 执行流程

### Step 1：并发数据采集

同时发起以下请求（全部并发）：

```
并发任务 A: mcp__github__get_pull_request
  → 获取 PR 元信息（标题、描述、作者、base/head 分支、head.sha）

并发任务 B: mcp__github__get_pull_request_files
  → 获取所有变更文件的 diff（patch 字段）

并发任务 C: mcp__github__list_commits(owner, repo, sha=head_branch)
  → 获取 head 分支的提交历史（最多 10 条）
  → 注意：list_commits 接受 sha 参数（分支名或 commit sha），不是 PR 编号
  → head_branch 从任务 A 的结果中获取（pr.head.ref）

并发任务 D: code-review-memory get_all
  → 获取团队规则摘要（review_rules + known_patterns + false_positives，最多 3000 tokens）
```

**注意**：任务 C 依赖任务 A 的结果（需要 head branch name），若并发执行则先用 PR 编号触发任务 A，等任务 A 返回后再发起任务 C。或直接串行执行任务 A → 任务 BCD 并发。

### Step 2：安全核心文件完整内容获取

对 `files_to_review` 中匹配以下模式的文件，额外获取完整文件内容：
- `*/auth/*`、`*authentication*`、`*authorization*`
- `*/security/*`、`*permission*`

调用 `mcp__github__get_file_contents` 获取 head 分支的完整内容。

### Step 3：Token 预算管理与 diff 截断

总预算：**32,000 tokens**

| 区域 | 预算 | 优先级 |
|------|------|--------|
| System Prompt | 3,000 | - |
| PR 基本信息 | 1,000 | - |
| Memory 规则 | 3,000 | - |
| 代码 diff | 20,000 | 按优先级截断 |
| 安全告警 | 2,000 | - |
| 提交历史 | 1,000 | - |
| 历史轮次 | 2,000 | - |

**Diff 截断策略**（当总 diff 超过 20,000 tokens）：

文件优先级分类：
1. **Priority 1**（不截断）：安全核心文件（auth/security/permission）
2. **Priority 2**（部分截断）：业务逻辑文件（views/models/controllers）
3. **Priority 3**（优先跳过）：测试文件、配置文件、文档

截断算法：
1. 先包含所有 Priority 1 文件的完整 diff
2. 剩余预算分配给 Priority 2 文件（按变更行数从小到大优先）
3. 若还有剩余预算，包含 Priority 3 文件
4. 超出预算的文件在输出中标注 `[TRUNCATED: 文件名, 原因]`

### Step 3.5：尝试获取安全告警（可选，失败时忽略）

调用 `mcp__github__get_pull_request_status` 获取 CI 状态（包含安全扫描结果）。
若仓库未启用 Code Scanning，此步骤返回空，`security_alerts` 设为 `[]`。

**注意**：GitHub Code Scanning API（`list_code_scanning_alerts`）在 GitHub MCP 中不可用，
使用 PR status checks 作为替代，从 check runs 中提取安全相关信息。

### Step 4：组装 AnalysisData

```json
{
  "pr_info": {
    "title": "...",
    "description": "...",
    "author": "...",
    "base_branch": "main",
    "head_branch": "feature/xxx",
    "changed_files_count": 5,
    "additions": 120,
    "deletions": 30
  },
  "diffs": [
    {
      "filename": "app/views.py",
      "status": "modified",
      "additions": 50,
      "deletions": 10,
      "patch": "...",
      "full_content": "...",
      "priority": 1,
      "truncated": false
    }
  ],
  "commits": [
    {"sha": "abc123", "message": "...", "author": "..."}
  ],
  "memory_rules": {
    "review_rules": "...",
    "known_patterns": "...",
    "false_positives": "..."
  },
  "token_usage": {
    "total_estimated": 18500,
    "budget": 32000,
    "truncated_files": []
  }
}
```

## 输出

向用户展示数据采集摘要：

```
## 数据采集完成

**PR**: #{pr_number} - {title}
**变更文件**: {n} 个（{additions}+ / {deletions}-）
**提交数**: {n}
**Token 使用**: {used}/{budget}
**截断文件**: {truncated_files 或 "无"}
**Memory 规则**: {rules_count} 条规则已加载
```

读取 `references/context-budget.md` 了解详细的 Token 预算策略和截断算法。
