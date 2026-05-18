# Escalation Rules — 人工介入规则

定义何时需要人工介入，以及如何通知相关人员。

## 触发人工介入的条件

### 硬规则（自动触发，无需 LLM 判断）

| 规则 ID | 条件 | 严重程度 | 说明 |
|---------|------|----------|------|
| HR-001 | 变更行数 > 500 | WARNING | 超大 PR 难以自动审查 |
| HR-002 | 标题/描述含 "BREAKING CHANGE" | WARNING | 破坏性变更需人工评估 |
| HR-003 | 标题含 "[RFC]" 或 "RFC:" | INFO | 设计讨论需人工参与 |
| HR-004 | 涉及 3+ 安全核心文件 | WARNING | 安全变更需人工确认 |
| HR-005 | PR 状态为 Draft | INFO | 草稿 PR 尚未完成 |
| HR-006 | PR 已有 CHANGES_REQUESTED | INFO | 已有人工 review 进行中 |

### LLM 判断触发（置信度低时）

| 条件 | 处理方式 |
|------|----------|
| 分类置信度 < 0.6 | 降级为 human_required |
| Review 置信度 < 0.6 | Decision 降级为 COMMENT |
| 涉及核心业务逻辑重构 | 标注 "建议人工确认" |

## 通知模板

### PR Comment（跳过自动 Review）

```markdown
## 🤖 自动 Code Review 已跳过

感谢提交 PR！由于以下原因，此 PR 需要人工代码审查：

**原因**：{skip_reason}

### 下一步
请在 Slack #code-review 频道通知团队成员进行人工审查。

---
*此消息由自动 Review 系统生成*
```

### CRITICAL 安全漏洞通知

```markdown
## 🚨 发现严重安全漏洞

自动 Review 系统在此 PR 中发现了 **{n} 个严重安全漏洞**。

### 漏洞列表
{issues_list}

### 要求
1. **此 PR 在安全问题修复前不得合并**
2. 已自动创建修复 PR：#{fix_pr_number}（需人工审查后方可使用）
3. 请通知安全团队

---
*此消息由自动 Review 系统生成*
```

## 安全核心文件列表

以下路径模式匹配的文件被视为安全核心文件：

```
# 认证相关
*/auth/*
*authentication*
*authorization*
*login*
*logout*
*session*
*jwt*
*oauth*

# 权限相关
*/security/*
*permission*
*access_control*
*acl*
*rbac*
*policy*

# 密钥相关（非测试文件）
*secret*（排除 test_*.py, *_test.py）
*credential*（排除 test_*.py, *_test.py）
*password*（排除 test_*.py, *_test.py）
*token*（排除 test_*.py, *_test.py）
*key*（排除 test_*.py, *_test.py）

# 支付相关
*/payment/*
*billing*
*checkout*
*stripe*
*paypal*
```

## 升级流程

```
发现需要人工介入
  ↓
1. 在 PR 添加说明 comment（使用上述模板）
2. 设置 PR label: "needs-human-review"
3. 若有 CRITICAL 安全漏洞：
   - 创建 Security Issue（标记 confidential）
   - 通知安全团队
  ↓
结束自动流程，等待人工处理
```
