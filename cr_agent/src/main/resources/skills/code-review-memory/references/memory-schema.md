# Memory Schema — 知识图谱数据结构规范

## 存储格式

文件：`~/.claude/review_memory.jsonl`
格式：每行一个 JSON 对象（JSONL）
编码：UTF-8

## 通用字段

所有记录必须包含：

```json
{
  "type": "review_rule|known_pattern|false_positive|fix_template",
  "id": "string (唯一标识符)",
  "created_at": "ISO-8601 datetime",
  "updated_at": "ISO-8601 datetime",
  "content": { ... }
}
```

**ID 生成规则**：`{type}-{timestamp}-{random4chars}`
例如：`review_rule-20260323-a1b2`

---

## 各类型 Content Schema

### type: developer_profile

开发者成长画像，跨 PR 追踪每位开发者的问题模式。

```json
{
  "type": "developer_profile",
  "id": "developer_profile-20260421-a1b2",
  "created_at": "2026-04-21T10:00:00Z",
  "updated_at": "2026-04-21T10:00:00Z",
  "content": {
    "author": "github_username",
    "issue_history": [
      {"category": "security", "count": 3, "last_seen": "2026-04-01T00:00:00Z"},
      {"category": "style", "count": 8, "last_seen": "2026-04-15T00:00:00Z"}
    ],
    "strengths": ["测试覆盖率高", "命名规范"],
    "growth_areas": ["SQL 安全", "错误处理"],
    "pr_count": 12,
    "last_updated": "2026-04-21T00:00:00Z"
  }
}
```

**用途**：review 前读取画像，调整 comment 语气（新手给详细解释，老手给简洁建议）。
**工具**：`memory_get_developer_profile`, `memory_update_developer_profile`

---

### type: repo_pattern

跨 PR 发现的高频问题模式，用于生成仓库健康报告。

```json
{
  "type": "repo_pattern",
  "id": "repo_pattern-20260421-c3d4",
  "created_at": "2026-04-21T10:00:00Z",
  "updated_at": "2026-04-21T10:00:00Z",
  "content": {
    "repo": "owner/repo",
    "pattern_name": "high:security",
    "category": "security",
    "severity": "high",
    "occurrence_count": 7,
    "affected_files": ["services/*.py"],
    "first_seen": "2026-03-01T00:00:00Z",
    "last_seen": "2026-04-20T00:00:00Z",
    "trend": "increasing",
    "sample_description": "未处理异常"
  }
}
```

**用途**：`health-report` 命令读取后生成 markdown 健康报告。
**工具**：`memory_aggregate_patterns`

---

### type: review_rule

团队代码规范条目。

```json
{
  "type": "review_rule",
  "id": "review_rule-20260323-a1b2",
  "created_at": "2026-03-23T10:00:00Z",
  "updated_at": "2026-03-23T10:00:00Z",
  "content": {
    "description": "所有数据库查询必须使用参数化查询",
    "severity": "error",
    "category": "security",
    "language": "python",
    "example_bad": "cursor.execute(f'SELECT * FROM users WHERE id={id}')",
    "example_good": "cursor.execute('SELECT * FROM users WHERE id=%s', (id,))",
    "source": "team_standard",
    "tags": ["sql", "security", "database"]
  }
}
```

**severity 枚举**：`error | warning | info`
**category 枚举**：`security | style | logic | performance | test`
**source 枚举**：`team_standard | incident | best_practice | manual`

---

### type: known_pattern

已知的问题模式，用于 Review 时的模式匹配。

```json
{
  "type": "known_pattern",
  "id": "known_pattern-20260323-c3d4",
  "created_at": "2026-03-23T10:00:00Z",
  "updated_at": "2026-03-23T10:00:00Z",
  "content": {
    "name": "SQL 注入",
    "description": "使用字符串拼接或 f-string 构造 SQL 查询",
    "severity": "critical",
    "category": "security",
    "indicators": [
      "f\"SELECT",
      "f'SELECT",
      "f\"INSERT",
      "f\"UPDATE",
      "f\"DELETE",
      "+ \" WHERE",
      "+ ' WHERE"
    ],
    "false_positive_rate": 0.05,
    "auto_fixable": true,
    "fix_template_id": "fix_template-builtin-sql-injection"
  }
}
```

**severity 枚举**：`critical | high | medium | low`

---

### type: false_positive

已知的误报规则，Review 时跳过这些情况。

```json
{
  "type": "false_positive",
  "id": "false_positive-20260323-e5f6",
  "created_at": "2026-03-23T10:00:00Z",
  "updated_at": "2026-03-23T10:00:00Z",
  "content": {
    "pattern": "test 文件中的硬编码凭证",
    "reason": "测试文件使用 mock 数据，非真实密钥",
    "file_patterns": [
      "test_*.py",
      "*_test.py",
      "*/tests/*",
      "*/test/*",
      "conftest.py"
    ],
    "related_pattern_ids": ["known_pattern-builtin-hardcoded-secrets"],
    "confirmed_count": 12,
    "last_confirmed": "2026-03-20T15:30:00Z"
  }
}
```

---

### type: fix_template

自动修复代码模板。

```json
{
  "type": "fix_template",
  "id": "fix_template-20260323-g7h8",
  "created_at": "2026-03-23T10:00:00Z",
  "updated_at": "2026-03-23T10:00:00Z",
  "content": {
    "name": "参数化 SQL 查询（Python）",
    "problem_pattern": "SQL 注入",
    "language": "python",
    "before_pattern": "cursor.execute(f'...{var}...')",
    "after_pattern": "cursor.execute('...%s...', (var,))",
    "before_example": "cursor.execute(f'SELECT * FROM users WHERE id={user_id}')",
    "after_example": "cursor.execute('SELECT * FROM users WHERE id=%s', (user_id,))",
    "regex_pattern": "cursor\\.execute\\(f['\"].*\\{.*\\}.*['\"]\\)",
    "usage_count": 5,
    "success_rate": 0.95
  }
}
```

---

## 查询接口

### get_all（供 Review 使用）

返回格式化的规则摘要，适合注入 LLM prompt：

```
## 团队代码规范
1. [error][security] 所有数据库查询必须使用参数化查询
2. [warning][style] 函数不超过 50 行

## 已知问题模式
1. [critical] SQL 注入 - 指标: f"SELECT, f'SELECT
2. [critical] 硬编码密钥 - 指标: password =, api_key =
3. [high] XSS 漏洞 - 指标: innerHTML =

## 误报排除规则
1. test 文件中的硬编码值（test_*.py, *_test.py）
2. migrations 文件中的 SQL（*/migrations/*）
```

### search（关键词检索）

在所有记录的以下字段中进行大小写不敏感的关键词匹配：
- `content.description`
- `content.name`
- `content.pattern`
- `content.tags`（数组）

---

## 文件操作规范

### 读取

```python
import json

def load_memory():
    try:
        with open("~/.claude/review_memory.jsonl", "r") as f:
            return [json.loads(line) for line in f if line.strip()]
    except FileNotFoundError:
        return []
```

### 追加

```python
def append_record(record):
    with open("~/.claude/review_memory.jsonl", "a") as f:
        f.write(json.dumps(record, ensure_ascii=False) + "\n")
```

### 更新（重写）

```python
def update_record(record_id, updated_content):
    records = load_memory()
    for i, r in enumerate(records):
        if r["id"] == record_id:
            records[i]["content"] = updated_content
            records[i]["updated_at"] = datetime.utcnow().isoformat() + "Z"
            break

    with open("~/.claude/review_memory.jsonl", "w") as f:
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
```
