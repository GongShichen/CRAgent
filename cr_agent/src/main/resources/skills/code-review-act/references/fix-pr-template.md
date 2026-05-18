# Fix PR Template — 自动修复 PR 格式规范

## PR 标题格式

```
[Auto Fix] PR #{original_pr_number}: {issues_summary}
```

**示例**：
- `[Auto Fix] PR #42: Fix SQL injection in user_views.py`
- `[Auto Fix] PR #123: Remove hardcoded API keys (2 files)`
- `[Auto Fix] PR #88: Fix SQL injection + XSS vulnerability`

**多问题时的摘要规则**：
- 1 个问题：直接描述问题
- 2 个问题：`{issue1} + {issue2}`
- 3+ 个问题：`{n} security/bug fixes`

## PR Body 模板

```markdown
## 🤖 自动修复 PR

此 PR 由代码 Review 系统自动生成，修复了 PR #{original_pr_number} 中发现的问题。

### 修复的问题

{issues_table}

### 变更文件

{changed_files_list}

### 验证方法

1. 检查上述修复是否符合预期
2. 运行相关测试：`{test_command}`
3. 确认无误后合并此 PR

### 注意事项

- ⚠️ 此 PR 由自动系统生成，请人工确认修复正确性后再合并
- 此 PR 基于 PR #{original_pr_number} 的 head 分支创建
- 建议将此 PR 合并到 PR #{original_pr_number} 的分支，而非直接合并到 main

---
*由 Claude Code Review Skill 自动生成*
```

## Issues Table 格式

```markdown
| 文件 | 行号 | 问题类型 | 严重程度 | 修复说明 |
|------|------|----------|----------|----------|
| app/views.py | 42 | SQL 注入 | 🔴 Critical | 使用参数化查询替换字符串拼接 |
| config/settings.py | 15 | 硬编码密钥 | 🔴 Critical | 替换为环境变量读取 |
| utils/helper.py | 88 | 空值检查缺失 | 🟠 High | 添加 None 检查 |
```

## Changed Files List 格式

```markdown
- `app/views.py` — 修复 SQL 注入（第 42 行）
- `config/settings.py` — 移除硬编码 API Key
- `utils/helper.py` — 添加空值检查
```

## 分支命名规范

```
auto-fix/pr-{original_pr_number}-{timestamp}
```

**示例**：`auto-fix/pr-42-20260323`

**规则**：
- 使用 `auto-fix/` 前缀便于识别
- 包含原始 PR 编号
- 包含日期（避免重复）

## Commit Message 格式

每个文件的修复使用独立 commit：

```
fix({category}): {description}

Auto-fix for PR #{original_pr_number}
Issue: {issue_description}
File: {filename}:{line}
```

**示例**：
```
fix(security): use parameterized queries to prevent SQL injection

Auto-fix for PR #42
Issue: SQL injection vulnerability in user search
File: app/views.py:42
```

## 自动修复范围说明

### 可自动修复的问题

| 问题类型 | 修复方式 | 风险等级 |
|----------|----------|----------|
| SQL 注入（参数化查询） | 代码替换 | 低 |
| 硬编码密钥 | 替换为 os.environ.get() | 低 |
| 简单空值检查 | 添加 if None 检查 | 低 |
| 代码格式问题 | 格式化工具 | 极低 |

### 不可自动修复的问题

| 问题类型 | 原因 |
|----------|------|
| 业务逻辑错误 | 需要理解业务含义 |
| 架构设计问题 | 涉及多文件重构 |
| 复杂权限逻辑 | 修复方案不唯一 |
| 性能优化 | 需要了解数据规模 |

## Label 规范

Fix PR 自动添加以下 label（若仓库已配置）：
- `auto-fix`
- `security`（若修复安全问题）
- `needs-review`（始终添加，提醒人工确认）
