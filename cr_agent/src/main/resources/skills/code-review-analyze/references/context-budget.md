# Context Budget — Token 预算与截断策略

## Token 预算分配

总预算：**32,000 tokens**

```
┌─────────────────────────────────────────────────────┐
│ Token 预算分配                                        │
├──────────────────────┬──────────┬───────────────────┤
│ 区域                  │ 预算     │ 说明              │
├──────────────────────┼──────────┼───────────────────┤
│ System Prompt        │ 3,000    │ 固定              │
│ PR 基本信息           │ 1,000    │ 标题/描述/元信息  │
│ Memory 规则          │ 3,000    │ 团队规则摘要      │
│ 代码 diff            │ 20,000   │ 按优先级截断      │
│ 安全告警             │ 2,000    │ 安全扫描结果      │
│ 提交历史             │ 1,000    │ 最近 5-10 条      │
│ 历史轮次             │ 2,000    │ 对话上下文        │
└──────────────────────┴──────────┴───────────────────┘
```

## 文件优先级分类

### Priority 1：安全核心文件（不截断）

路径匹配：
```
*/auth/*
*/security/*
*authentication*
*authorization*
*permission*
*access_control*
*secret*（非测试文件）
*credential*（非测试文件）
```

处理：包含完整 diff + 完整文件内容（通过 `get_file_contents` 获取）

### Priority 2：业务逻辑文件（部分截断）

路径匹配：
```
*/views/*
*/controllers/*
*/handlers/*
*/models/*
*/services/*
*/api/*
*.py（非测试）
*.js（非测试）
*.ts（非测试）
*.go（非测试）
```

处理：按变更行数从小到大优先包含，超出预算时截断

### Priority 3：低优先级文件（优先跳过）

路径匹配：
```
test_*.py
*_test.py
*.test.js
*.spec.ts
*/tests/*
*/test/*
*.md
*.txt
*.json（配置文件，非业务逻辑）
package-lock.json
yarn.lock
*.lock
```

处理：剩余预算充足时包含，否则跳过

## 截断算法

```python
def allocate_diff_budget(files, budget=20000):
    p1_files = [f for f in files if is_priority_1(f)]
    p2_files = [f for f in files if is_priority_2(f)]
    p3_files = [f for f in files if is_priority_3(f)]

    result = []
    remaining = budget

    # Step 1: 包含所有 P1 文件（不截断）
    for f in p1_files:
        tokens = estimate_tokens(f.patch + f.full_content)
        result.append({"file": f, "truncated": False})
        remaining -= tokens

    # Step 2: 按行数从小到大包含 P2 文件
    p2_sorted = sorted(p2_files, key=lambda f: f.additions + f.deletions)
    for f in p2_sorted:
        tokens = estimate_tokens(f.patch)
        if remaining >= tokens:
            result.append({"file": f, "truncated": False})
            remaining -= tokens
        elif remaining >= 500:  # 至少包含 500 tokens
            truncated_patch = truncate_to_tokens(f.patch, remaining)
            result.append({"file": f, "truncated": True, "patch": truncated_patch})
            remaining = 0
            break
        else:
            result.append({"file": f, "truncated": True, "patch": "[TRUNCATED: 预算不足]"})

    # Step 3: 剩余预算分配给 P3 文件
    for f in p3_files:
        tokens = estimate_tokens(f.patch)
        if remaining >= tokens:
            result.append({"file": f, "truncated": False})
            remaining -= tokens
        else:
            result.append({"file": f, "truncated": True, "patch": "[SKIPPED: 低优先级，预算不足]"})

    return result
```

## Token 估算

粗略估算（用于预算控制）：
- 1 token ≈ 4 个英文字符
- 1 token ≈ 2 个中文字符
- 1 行代码 ≈ 10-20 tokens

```python
def estimate_tokens(text: str) -> int:
    return len(text) // 4  # 粗略估算
```

## 截断标注格式

当文件被截断时，在 diff 末尾添加：

```
[... TRUNCATED: 仅显示前 {shown_lines} 行，共 {total_lines} 行。
完整文件路径: {filename}
截断原因: Token 预算限制（已使用 {used}/{budget}）]
```

## 并发请求策略

所有 GitHub API 请求并发发起（不等待前一个完成）：

```python
import asyncio

async def collect_data(owner, repo, pr_number, files_to_review):
    tasks = [
        get_pull_request(owner, repo, pr_number),
        get_pull_request_files(owner, repo, pr_number),
        list_commits(owner, repo, pr_number),
        get_memory_rules(),
    ]

    # 并发执行
    results = await asyncio.gather(*tasks, return_exceptions=True)

    # 对安全核心文件额外获取完整内容
    security_files = [f for f in results[1] if is_priority_1(f)]
    if security_files:
        content_tasks = [
            get_file_contents(owner, repo, f.filename, ref=pr_head_sha)
            for f in security_files
        ]
        file_contents = await asyncio.gather(*content_tasks)

    return assemble_analysis_data(results, file_contents)
```
