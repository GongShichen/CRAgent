---
name: code-review-memory
description: |
  管理代码 Review 知识图谱：存储规则、已知问题模式、误报列表、修复模板。
  内部 skill，由其他 code-review skill 调用。也可单独触发：
  "add review rule", "mark as false positive", "update code review memory",
  "show review rules", "添加 review 规则", "查看已知问题模式", "查看误报列表",
  "更新 review 规则", "管理代码审查知识库"。
  Make sure to use this skill whenever the user mentions managing review rules,
  false positives, known patterns, or code review memory.
---

# Code Review Memory

知识图谱管理 skill，负责持久化存储和检索代码 Review 相关知识。

## 存储位置

所有数据存储在 `~/.claude/review_memory.jsonl`，每行一个 JSON 对象。

## 数据结构

每条记录格式：
```json
{
  "type": "review_rule|known_pattern|false_positive|fix_template",
  "id": "unique-id",
  "created_at": "ISO-8601",
  "updated_at": "ISO-8601",
  "content": { ... }
}
```

详细 schema 见 `references/memory-schema.md`。

## 支持的操作

### 查询操作（0 LLM 调用）

**show rules** / **查看规则**：
- 读取 `~/.claude/review_memory.jsonl`
- 按 type 分组展示所有条目
- 格式：表格或列表

**search `<keyword>`**：
- 在所有记录的 content 字段中进行关键词匹配
- 返回匹配的记录列表

**get_all**（供其他 skill 调用）：
- 返回所有规则的摘要（每条 ≤ 50 字）
- 格式化为适合注入 LLM prompt 的文本
- **总量上限 3,000 tokens**（约 12,000 字符）：超出时按 `known_pattern > review_rule > false_positive > fix_template` 优先级截断，并标注 `[已截断，共 {n} 条，显示前 {m} 条]`

### 写入操作（0 LLM 调用）

**add_rule `<description>`**：
```json
{
  "type": "review_rule",
  "content": {
    "description": "规则描述",
    "severity": "error|warning|info",
    "category": "security|style|logic|performance",
    "language": "python|javascript|go|...(可选)",
    "example_bad": "问题代码示例（可选）",
    "example_good": "正确代码示例（可选）",
    "source": "team_standard|incident|best_practice|manual",
    "tags": ["tag1", "tag2"]
  }
}
```

**add_pattern `<name>` `<description>`**：
```json
{
  "type": "known_pattern",
  "content": {
    "name": "SQL注入",
    "description": "未参数化的 SQL 查询",
    "indicators": ["f-string SQL", "string concatenation in query"],
    "severity": "critical"
  }
}
```

**add_false_positive `<pattern>` `<reason>`**：
```json
{
  "type": "false_positive",
  "content": {
    "pattern": "test_*.py 中的 hardcoded credentials",
    "reason": "测试文件使用 mock 凭证，非真实密钥",
    "file_patterns": ["test_*.py", "*/tests/*", "*/migrations/*"]
  }
}
```

**add_template `<name>` `<fix_code>`**：
```json
{
  "type": "fix_template",
  "content": {
    "name": "参数化 SQL 查询",
    "problem": "SQL 注入",
    "before": "cursor.execute(f'SELECT * FROM users WHERE id={id}')",
    "after": "cursor.execute('SELECT * FROM users WHERE id=%s', (id,))"
  }
}
```

## 执行步骤

1. 解析用户意图（show/search/add_rule/add_pattern/add_false_positive/add_template）
2. 读取 `~/.claude/review_memory.jsonl`（文件不存在时创建空文件）
3. 执行对应操作（CRUD）
4. 写回文件（append 或 rewrite）
5. 确认操作结果

## 默认内置规则

首次运行时，若文件为空，初始化以下默认规则（含完整 created_at/updated_at 字段）：

```json
{"type":"known_pattern","id":"builtin-1","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z","content":{"name":"SQL注入","description":"使用字符串拼接或 f-string 构造 SQL 查询","severity":"critical","indicators":["f\"SELECT","f'SELECT","+ \" WHERE","+ ' WHERE"]}}
{"type":"known_pattern","id":"builtin-2","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z","content":{"name":"硬编码密钥","description":"代码中直接包含 API key、密码、token","severity":"critical","indicators":["password =","api_key =","secret =","token ="]}}
{"type":"known_pattern","id":"builtin-3","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z","content":{"name":"XSS漏洞","description":"未转义的用户输入直接渲染到 HTML","severity":"high","indicators":["innerHTML =","dangerouslySetInnerHTML","render_template_string"]}}
{"type":"false_positive","id":"builtin-fp-1","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z","content":{"pattern":"test 文件中的硬编码值","reason":"测试文件使用 mock 数据","file_patterns":["test_*.py","*_test.py","*/tests/*","*/test/*"]}}
{"type":"false_positive","id":"builtin-fp-2","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z","content":{"pattern":"migrations 文件中的 SQL","reason":"数据库迁移文件使用原生 SQL 是正常的","file_patterns":["*/migrations/*","*migration*.py"]}}
```

读取 `references/memory-schema.md` 了解完整的数据结构规范。
