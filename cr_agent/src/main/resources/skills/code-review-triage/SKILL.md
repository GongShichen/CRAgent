---
name: code-review-triage
description: |
  对 GitHub PR 进行分类，判断是否可以自动 Review 还是需要人工介入。
  内部 skill，由 code-review 调用。也可单独触发：
  "classify this PR", "should this PR be auto-reviewed", "triage PR #N",
  "判断这个 PR 是否需要人工 review", "PR 分类", "评估 PR 复杂度"。
  Make sure to use this skill whenever the user asks to classify, triage,
  or assess whether a PR needs human review.
---

# Code Review Triage

对 PR 进行快速分类，决定走自动 Review 路径还是需要人工介入。

## 输入

- `owner/repo`：仓库标识
- `pr_number`：PR 编号
- Memory 规则摘要（可选，由 code-review-memory 提供）

## 执行流程

### Step 1：获取 PR 基本信息（0 LLM 调用）

并发调用：
- `mcp__github__get_pull_request`：获取标题、描述、状态
- `mcp__github__get_pull_request_files`：获取变更文件列表

### Step 2：硬规则检查（0 LLM 调用）

以下任一条件满足 → 立即返回 `human_required`，跳过 LLM 分类：

| 规则 | 条件 | 原因 |
|------|------|------|
| 超大 PR | 变更行数 > 500 | 自动 review 效果差 |
| Breaking Change | 标题/描述含 "breaking change"、"BREAKING" | 影响范围大，需人工评估 |
| RFC/设计文档 | 标题含 "RFC"、"[RFC]"、"design doc" | 需要架构讨论 |
| 安全核心文件 | 涉及 3+ 个安全核心文件 | 安全变更需人工确认 |
| Draft PR | PR 状态为 draft | 尚未完成 |

**安全核心文件判断**：按**文件**计数，每个匹配以下任意模式的文件算1个（不是按模式组计数）：
- `*/auth/*`、`*authentication*`、`*authorization*`
- `*/security/*`、`*permission*`、`*access_control*`
- `*secret*`、`*credential*`、`*password*`、`*token*`（排除路径含 `test` 或 `mock` 的文件）

例：`auth/login.py`（1个）+ `security/permissions.py`（1个）+ `utils/token_helper.py`（1个）= 3个 → 触发 human_required

### Step 3：LLM 分类（1 次 LLM 调用）

若硬规则未触发，调用 LLM 进行语义分类。

**Prompt 结构**：
```
你是一个代码 Review 分类器。根据以下 PR 信息，判断是否可以自动 Review。

PR 标题：{title}
PR 描述：{description}
变更文件：{file_list}
团队规则摘要：{memory_rules_summary}

请输出 JSON：
{
  "review_type": "auto" | "human_required",
  "confidence": 0.0-1.0,
  "focus_areas": ["security", "style", "logic", "performance"],
  "files_to_review": ["优先审查的文件路径列表"],
  "estimated_complexity": "simple" | "moderate" | "complex",
  "skip_reason": null | "原因说明"
}

human_required 的判断标准：
- 涉及核心业务逻辑重构
- 多个模块之间有复杂依赖变更
- 变更影响数据库 schema
- 涉及第三方集成变更
- 置信度 < 0.6 时自动降级
```

**置信度处理**：
- confidence < 0.6 → 强制设置 `review_type = "human_required"`，`skip_reason = "置信度不足，建议人工确认"`

### Step 4：输出 ReviewPlan

```json
{
  "review_type": "auto",
  "focus_areas": ["security", "style"],
  "files_to_review": ["app/views.py", "app/models.py"],
  "estimated_complexity": "simple",
  "skip_reason": null,
  "hard_rule_triggered": false,
  "confidence": 0.85
}
```

## 输出格式

向用户展示分类结果：

```
## PR Triage 结果

**PR**: #{pr_number} - {title}
**分类**: ✅ 自动 Review | ⚠️ 需要人工介入
**复杂度**: simple/moderate/complex
**关注领域**: security, style
**优先审查文件**:
  - app/views.py
  - app/models.py

{若 human_required: "跳过原因: {skip_reason}"}
```

读取 `references/triage-rules.md` 了解详细的分类规则和边界情况。
