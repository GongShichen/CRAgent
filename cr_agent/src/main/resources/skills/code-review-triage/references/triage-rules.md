# Triage Rules — 分类规则详情

## 硬规则详细说明

### HR-001：超大 PR（> 500 行）

**计算方式**：`sum(file.additions + file.deletions for file in pr_files)`

**边界情况**：
- 自动生成文件（`*_generated.go`, `*.pb.go`, `schema.graphql`）不计入行数
- Lock 文件（`package-lock.json`, `yarn.lock`, `Pipfile.lock`）不计入行数
- 迁移文件（`*/migrations/*.sql`）不计入行数

**处理**：返回 `human_required`，`skip_reason = "PR 变更行数过多（{n} 行），超过自动 Review 阈值（500 行）"`

### HR-002：Breaking Change

**检测关键词**（大小写不敏感）：
- 标题：`breaking change`, `BREAKING`, `[BREAKING]`, `breaking:`
- 描述：`## Breaking Changes`, `BREAKING CHANGE:`, `⚠️ breaking`

**处理**：返回 `human_required`，`skip_reason = "PR 包含破坏性变更，需要人工评估影响范围"`

### HR-003：RFC / 设计文档

**检测关键词**：
- 标题前缀：`[RFC]`, `RFC:`, `RFC -`, `[Design]`, `[Proposal]`

**处理**：返回 `human_required`，`skip_reason = "此 PR 是 RFC/设计提案，需要团队讨论"`

### HR-004：安全核心文件（3+ 个）

**安全核心文件路径模式**（每匹配一个计 1 分）：
```
auth/*, authentication*, authorization*  → security_score += 1
security/*, permission*, access_control* → security_score += 1
secret*, credential*, password*, token*  → security_score += 1（排除测试文件）
payment/*, billing*, checkout*           → security_score += 1
```

**处理**：`security_score >= 3` → 返回 `human_required`

### HR-005：Draft PR

**检测**：`pr.draft == true`

**处理**：返回 `human_required`，`skip_reason = "PR 处于草稿状态，尚未完成"`

---

## LLM 分类 Prompt 详解

### 输入构造

```python
file_list = "\n".join([
    f"- {f.filename} (+{f.additions}/-{f.deletions})"
    for f in pr_files[:20]  # 最多 20 个文件
])

memory_summary = memory_rules[:500]  # 截取前 500 字符
```

### 输出字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `review_type` | `"auto"\|"human_required"` | 分类结果 |
| `confidence` | `float [0,1]` | 置信度，< 0.6 时强制 human_required |
| `focus_areas` | `string[]` | 重点关注领域 |
| `files_to_review` | `string[]` | 优先审查文件（最多 10 个） |
| `estimated_complexity` | `"simple"\|"moderate"\|"complex"` | 复杂度评估 |
| `skip_reason` | `string\|null` | human_required 时的原因 |

### 复杂度评估标准

| 复杂度 | 条件 |
|--------|------|
| simple | 变更 < 100 行，单一功能，无跨模块依赖 |
| moderate | 变更 100-300 行，2-3 个模块，有测试文件 |
| complex | 变更 > 300 行，多模块，涉及数据库或 API |

---

## 边界情况处理

### 无描述的 PR
- 继续分类，但在 focus_areas 中添加 `"documentation"`
- 在 Review 结果中建议补充 PR 描述

### 仅有测试文件变更
- `review_type = "auto"`
- `focus_areas = ["test_quality"]`
- `estimated_complexity = "simple"`

### 仅有文档变更
- `review_type = "auto"`
- `focus_areas = ["documentation"]`
- `estimated_complexity = "simple"`

### 仅有配置文件变更
- 检查是否包含安全相关配置（如 CORS、CSP、认证配置）
- 若包含：`focus_areas` 加入 `"security"`
- 若不包含：`estimated_complexity = "simple"`
