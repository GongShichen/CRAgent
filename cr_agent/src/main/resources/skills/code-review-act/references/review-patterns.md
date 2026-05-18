# Review Patterns — 常见问题模式与修复示例

## 🔴 安全漏洞模式

### SQL 注入

**检测**：f-string 或字符串拼接构造 SQL

```python
# ❌ 问题代码
query = f"SELECT * FROM users WHERE id={user_id}"
cursor.execute(query)

# ✅ 修复代码
cursor.execute("SELECT * FROM users WHERE id=%s", (user_id,))
```

**Django ORM 版本**：
```python
# ❌ 问题代码
User.objects.raw(f"SELECT * FROM users WHERE name='{name}'")

# ✅ 修复代码
User.objects.filter(name=name)
# 或
User.objects.raw("SELECT * FROM users WHERE name=%s", [name])
```

**Node.js 版本**：
```javascript
// ❌ 问题代码
db.query(`SELECT * FROM users WHERE id=${userId}`)

// ✅ 修复代码
db.query('SELECT * FROM users WHERE id = ?', [userId])
```

---

### 硬编码密钥

**检测**：代码中直接包含密钥字符串

```python
# ❌ 问题代码
API_KEY = "sk-1234567890abcdef"
DATABASE_PASSWORD = "mypassword123"

# ✅ 修复代码
import os
API_KEY = os.environ.get("API_KEY")
DATABASE_PASSWORD = os.environ.get("DATABASE_PASSWORD")
```

**Node.js 版本**：
```javascript
// ❌ 问题代码
const apiKey = "sk-1234567890abcdef"

// ✅ 修复代码
const apiKey = process.env.API_KEY
```

---

### XSS 漏洞

**检测**：未转义的用户输入渲染到 HTML

```javascript
// ❌ 问题代码
element.innerHTML = userInput

// ✅ 修复代码
element.textContent = userInput
// 或使用 DOMPurify
element.innerHTML = DOMPurify.sanitize(userInput)
```

**Python Flask 版本**：
```python
# ❌ 问题代码
return render_template_string(f"<h1>{user_input}</h1>")

# ✅ 修复代码
from markupsafe import escape
return render_template_string("<h1>{{ input }}</h1>", input=escape(user_input))
```

---

### 路径遍历

**检测**：用户输入直接用于文件路径

```python
# ❌ 问题代码
with open(f"/uploads/{filename}") as f:
    return f.read()

# ✅ 修复代码
import os
safe_path = os.path.join("/uploads", os.path.basename(filename))
if not safe_path.startswith("/uploads/"):
    raise ValueError("Invalid path")
with open(safe_path) as f:
    return f.read()
```

---

## 🟠 Bug 模式

### 空值检查缺失

```python
# ❌ 问题代码
user = get_user(user_id)
print(user.name)  # user 可能为 None

# ✅ 修复代码
user = get_user(user_id)
if user is None:
    raise ValueError(f"User {user_id} not found")
print(user.name)
```

**JavaScript 版本**：
```javascript
// ❌ 问题代码
const name = user.profile.name  // user 或 profile 可能为 null

// ✅ 修复代码
const name = user?.profile?.name ?? 'Unknown'
```

---

### 未处理的异常

```python
# ❌ 问题代码
response = requests.get(url)
data = response.json()

# ✅ 修复代码
try:
    response = requests.get(url, timeout=10)
    response.raise_for_status()
    data = response.json()
except requests.RequestException as e:
    logger.error(f"API request failed: {e}")
    raise
```

---

### N+1 查询

```python
# ❌ 问题代码
users = User.objects.all()
for user in users:
    print(user.profile.bio)  # 每次循环都查询数据库

# ✅ 修复代码
users = User.objects.select_related('profile').all()
for user in users:
    print(user.profile.bio)  # 使用预加载的数据
```

---

## 🟡 规范问题模式

### 魔法数字

```python
# ❌ 问题代码
if retry_count > 3:
    return None

# ✅ 修复代码
MAX_RETRY_COUNT = 3
if retry_count > MAX_RETRY_COUNT:
    return None
```

### 过长函数

```python
# ❌ 问题代码（函数超过 50 行）
def process_order(order):
    # 验证
    # 计算价格
    # 库存检查
    # 创建记录
    # 发送通知
    # ... 100 行

# ✅ 修复代码（拆分职责）
def process_order(order):
    validate_order(order)
    price = calculate_price(order)
    check_inventory(order)
    record = create_order_record(order, price)
    notify_user(order, record)
    return record
```

---

## Review Comment 格式模板

### 安全漏洞 Comment

```markdown
🔴 **安全漏洞：{漏洞类型}**

**问题**：{问题描述}

**风险**：{潜在危害}

**建议修复**：
```{language}
{fix_code}
```

参考：{相关文档链接（可选）}
```

### Bug Comment

```markdown
🟠 **潜在 Bug：{问题类型}**

**问题**：{问题描述}

**场景**：当 {触发条件} 时，{会发生什么}

**建议**：
```{language}
{suggestion_code}
```
```

### 规范建议 Comment

```markdown
🟡 **代码规范建议**

{建议内容}

```{language}
{example_code}
```
```
