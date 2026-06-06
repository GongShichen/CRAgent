---
name: code-review-act
description: |
  执行代码 Review：分析代码问题，提交 Review 注释，对可自动修复的问题
  创建 Fix PR。内部 skill，由 code-review 调用。也可单独触发：
  "review this code", "submit review for PR #N", "auto-fix PR issues",
  "create fix PR", "提交代码审查", "自动修复代码", "review PR 并修复问题",
  "对 PR 提交 review 意见"。
  Make sure to use this skill whenever the user asks to review code,
  submit review comments, auto-fix issues, or create a fix PR.
---

# Code Review Act

执行 Review 分析并采取行动（提交 Review + 可选创建 Fix PR）。

## 输入

- `analysis_data`：来自 code-review-analyze 的完整分析数据
- `review_plan`：来自 code-review-triage 的 ReviewPlan
- `owner/repo`：仓库标识
- `pr_number`：PR 编号

## Phase 2.5：读取开发者成长画像

在开始 Review 分析之前，调用 `memory_get_developer_profile` 获取 PR 作者的历史画像：

```
memory_get_developer_profile(author=pr_info.author)
```

根据画像调整 Review 风格：
- **新手**（`pr_count < 5`）：comment 附带详细解释和示例代码，语气友善鼓励
- **中级**（`5 ≤ pr_count < 20`）：comment 简洁说明问题和修复方向
- **资深**（`pr_count ≥ 20`）：直接指出问题，无需过多解释
- **有历史问题**（`issue_history` 中某类别 count > 3）：在 Review summary 中提及成长建议

## Phase 3：REVIEW 分析（1 次 LLM 调用）

### Prompt 结构

```
你是一个资深代码审查员。请对以下 PR 进行全面的代码审查。

## PR 信息
{pr_info}

## 代码变更
{diffs}

## 安全告警
{security_alerts}

## 团队规则
{memory_rules}

## 提交历史
{commits}

请按以下优先级发现问题：
1. 🔴 安全漏洞（SQL注入、XSS、硬编码密钥、权限绕过）
2. 🟠 深层逻辑 Bug（架构缺陷、分布式状态问题、核心业务逻辑错误、并发问题、不符合框架规范的用法）
3. 🟡 明显的运行时 Bug（空指针、严重的边界条件错误）
4. 🟢 性能优化建议

【极其重要】:
- 严格忽略（DO NOT REPORT）无关紧要的代码风格、命名规范、单行注释等格式问题。
- 重点关注隐藏在修改行背后的“系统性影响”（例如：未被测试覆盖的分支、破坏原有的接口契约）。

输出 JSON：
{
  "pr_analysis": "简要分析这个 PR 的核心目的、主要修改了哪些逻辑，以及潜在的深层风险点（约 100-200 字，必须在 issues 之前输出以辅助推理）",
  "issues": [
    {
      "severity": "critical|high|medium|low",
      "category": "security|bug|style|performance",
      "file": "文件路径",
      "line": 行号（必须是 diff 中实际变更的行，否则填 null）,
      "evidence": "【防幻觉必填】从 diff 中原封不动复制导致该问题的 1-2 行核心代码（严格一字不差。如果代码中根本不存在这段逻辑，说明是你的幻觉，请直接放弃提出该 issue）",
      "description": "问题描述",
      "suggestion": "修复建议",
      "confidence": 0.9,
      "auto_fixable": true|false,
      "fix_code": "【重要】若 auto_fixable=true，提供修复后的【完整文件内容】（不是代码片段）。若 auto_fixable=false，填 null。"
    }
  ],
  "decision": "APPROVE|REQUEST_CHANGES|COMMENT",
  "summary": "总体评价（2-3句话）",
  "confidence": 0.0-1.0
}

注意：auto_fixable 只能对以下情况设为 true：
- SQL 注入（有明确参数化查询替换模式）
- 硬编码密钥（替换为 os.environ.get()）
- 简单空值检查（添加 None/null 判断）
- 代码格式问题（不改变逻辑）
对于 category=logic 或 category=architecture 的问题，auto_fixable 必须为 false。
```

### 决策逻辑

| 条件 | Decision |
|------|----------|
| 有 critical/high severity 安全漏洞 | REQUEST_CHANGES |
| 有 high severity Bug | REQUEST_CHANGES |
| 只有 medium/low 问题 | COMMENT |
| 无问题 | APPROVE |
| confidence < 0.6 | 强制降级为 COMMENT |

### 自动修复判断标准

**可自动修复**（`auto_fixable: true`）：
- 明确安全漏洞：SQL 注入、硬编码密钥（有明确的修复模式）
- 格式/规范问题：不改变业务逻辑的代码风格修复
- 简单 Bug：明确的空值检查、边界条件补充

**不可自动修复**（`auto_fixable: false`）：
- 逻辑错误：需要理解业务含义
- 架构问题：涉及多文件重构
- 模糊问题：修复方案不唯一

## Phase 4：ACT 执行（0 LLM 调用）

### 分支 A：提交 GitHub Review

调用 `mcp__github__create_pull_request_review`：

```json
{
  "owner": "...",
  "repo": "...",
  "pull_number": 42,
  "event": "APPROVE|REQUEST_CHANGES|COMMENT",
  "body": "## Code Review 摘要\n\n{summary}\n\n### 发现问题\n{issues_summary}",
  "comments": [
    {
      "path": "app/views.py",
      "line": 42,
      "body": "🔴 **安全漏洞**: {description}\n\n**建议**: {suggestion}\n\n```python\n{fix_code}\n```"
    }
  ]
}
```

### 分支 B：创建 Fix PR（仅当有 auto_fixable 问题时）

**前置检查**：对每个 `auto_fixable: true` 的 issue，验证：
- `category` 不是 `logic` 或 `architecture`（这类问题强制设为 `auto_fixable: false`）
- `fix_code` 字段非空

1. **创建修复分支**（基于原始 PR 的 head 分支）：
   ```
   mcp__github__create_branch(
     owner=owner,
     repo=repo,
     branch="auto-fix/pr-{pr_number}-{date}",
     from_branch=analysis_data.pr_info.head_branch  # 从 PR head 分支创建
   )
   ```

2. **逐个获取文件内容并写入修复**：
   对每个 `auto_fixable: true` 的 issue：
   ```
   # Step 2a: 获取当前文件内容和 sha（create_or_update_file 更新文件时必须提供 sha）
   file_info = mcp__github__get_file_contents(
     owner=owner, repo=repo,
     path=issue.file,
     branch="auto-fix/pr-{pr_number}-{date}"
   )

   # Step 2b: 将 fix_code 应用到文件
   # fix_code 是完整的修复后文件内容（LLM 在 prompt 中被要求提供完整文件）
   mcp__github__create_or_update_file(
     owner=owner, repo=repo,
     path=issue.file,
     content=issue.fix_code,          # 完整文件内容
     sha=file_info.sha,               # 必须提供当前文件的 sha
     message="fix({category}): {issue.description}\n\nAuto-fix for PR #{pr_number}",
     branch="auto-fix/pr-{pr_number}-{date}"
   )
   ```

3. **创建 Fix PR**：
   读取 `references/fix-pr-template.md` 生成 PR 标题和 Body，然后：
   ```
   mcp__github__create_pull_request(
     owner=owner, repo=repo,
     title="[Auto Fix] PR #{pr_number}: {issues_summary}",
     body="{fix_pr_body}",
     head="auto-fix/pr-{pr_number}-{date}",
     base=analysis_data.pr_info.head_branch  # Fix PR 合并回原始 PR 的 feature 分支
   )
   ```

### 分支 C：CRITICAL 安全漏洞处理

若有 `severity: "critical"` 的安全漏洞：
1. Review decision 强制设为 `REQUEST_CHANGES`
2. 在 Review body 顶部添加 `🚨 CRITICAL SECURITY ISSUE` 警告
3. 在 PR 添加 comment，标注需要人工确认：
   ```
   mcp__github__add_issue_comment(
     body="⚠️ **需要人工安全审查**\n\n发现 {n} 个严重安全漏洞，自动修复已创建但需要人工确认后才能合并。\n\n{issues_list}"
   )
   ```

### 分支 D：更新 Memory

调用 `/code-review-memory` 更新知识图谱：
- 将本次发现的新问题模式添加为 `known_pattern`
- 将确认的误报添加为 `false_positive`

### 分支 E：更新开发者成长画像

Review 完成后，调用 `memory_update_developer_profile` 更新开发者画像：

```
memory_update_developer_profile(
  author=pr_info.author,
  new_issues=[{"category": issue.category, "severity": issue.severity} for issue in issues],
  strengths=<从本次 review 中识别的优点，如"测试覆盖完整"、"命名规范">,
  growth_areas=<需要改进的领域，如"SQL 安全"、"错误处理">
)
```

### 分支 F：聚合跨 PR 模式统计

Review 完成后，调用 `memory_aggregate_patterns` 更新仓库级别的问题统计：

```
memory_aggregate_patterns(
  repo="{owner}/{repo}",
  issues=[{"category": issue.category, "severity": issue.severity,
           "file": issue.file, "description": issue.description}
          for issue in issues]
)
```

### 分支 G：自动生成测试用例（当满足条件时）

**触发条件**：`changed_files` 中包含业务逻辑文件（非 `test_*.py`/`*_test.*`）且 `additions > 20`

1. 调用 `detect_test_framework` 检测项目测试框架：
   ```
   detect_test_framework(owner=owner, repo=repo)
   ```

2. 根据 diff 中新增的函数/方法，识别未被现有测试覆盖的代码路径（边界条件、异常路径）

3. 生成符合项目测试框架的测试代码，并将测试文件包含在 Fix PR 中（或单独创建 test PR）

参考 `skills/code-review-test-gen/SKILL.md` 了解测试生成详细规范。

## 输出

```
## Code Review 完成

**PR**: #{pr_number} - {title}
**决策**: ✅ APPROVE | 🔄 REQUEST_CHANGES | 💬 COMMENT
**发现问题**: {total} 个（🔴 {critical} 严重 / 🟠 {high} 高 / 🟡 {medium} 中 / 🟢 {low} 低）
**自动修复**: {auto_fixable_count} 个问题已创建 Fix PR: #{fix_pr_number}

### 主要问题
{issues_list}

### 总结
{summary}
```

读取 `references/review-patterns.md` 了解常见问题模式和修复示例。
读取 `references/fix-pr-template.md` 了解 Fix PR 的格式规范。
