# CR-Agent

![CR-Agent architecture](docs/architecture.svg)

CR-Agent 是一个 Java 实现的 agentic code review 系统。它可以通过命令行或 chat CLI 接收自然语言输入，解析 GitHub PR、仓库链接、compare URL 或 commit range，然后自动收集上下文、执行多阶段代码审查、记录完整轨迹，并导出 SFT/DPO 训练数据。

当前主 agent 使用 Java/Gradle 工程实现，SFT/DPO 训练脚本保留在 Python 侧。

## 核心能力

- 自然语言 chat 入口：可以直接输入 GitHub 仓库、PR 链接、`owner/repo`、compare URL、两个 commit 或分支名。
- PR review：读取 GitHub PR 元信息、diff、changed files、review comments、CI checks 和仓库上下文。
- Commit range review：支持 `base...head`，也支持只给仓库时默认审查默认分支最新提交区间。
- 本机 Git 优先：优先使用本机 clone、SSH/HTTPS Git 环境生成 diff；`GITHUB_TOKEN` 作为 GitHub API fallback。
- 五阶段主流程：`Triage -> Analyze -> Review -> Act -> Report`。
- 深度 review 策略：Context Expansion、Risk Modeling、Regression/Test Reasoning、Evidence Validation。
- 工具系统：GitHub 读写工具、Memory 工具、测试生成工具、上下文扩展工具统一注册到 `ToolRouter`。
- 安全执行：默认 dry-run；真实写操作失败不会阻塞 review 主流程。
- 轨迹记录：每次运行写入 JSONL trace，包含 phase、LLM 请求/响应、tool call/result、issue、action 和错误。
- Memory 系统：记录 developer profile、repo pattern、false positive 规则和 health report。
- 数据导出：从 trace 导出 SFT、DPO 和 tool-supervision 数据。

## 工程结构

```text
CR-Agent/
├── README.md
├── .env.example
├── docs/
│   └── architecture.svg
├── cr_agent/
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── src/main/java/com/cragent/
│   │   ├── agent/          # CodeReviewAgent 主流程与结果解析
│   │   ├── cli/            # CLI、chat parser、本机 Git/GitHub token 检查
│   │   ├── config/         # .env 与环境变量配置
│   │   ├── datasets/       # SFT/DPO/tool-supervision 导出
│   │   ├── llm/            # OpenAI-compatible LLM client
│   │   ├── memory/         # JSONL memory store
│   │   ├── model/          # Agent/issue/tool 数据模型
│   │   ├── skills/         # skill prompt loader
│   │   ├── tools/          # GitHub、Memory、TestGeneration、ToolRouter
│   │   ├── trace/          # JSONL trajectory recorder
│   │   └── util/           # JSON、retry、path helpers
│   ├── src/main/resources/skills/
│   │   ├── code-review/
│   │   ├── code-review-act/
│   │   ├── code-review-analyze/
│   │   ├── code-review-java-runtime/
│   │   ├── code-review-memory/
│   │   ├── code-review-test-gen/
│   │   └── code-review-triage/
│   ├── src/test/java/com/cragent/
│   └── data/
│       ├── traces/
│       └── memory/
├── datasets/
│   ├── SFT/
│   └── DPO/
├── train/
│   ├── sft_train.py
│   └── dpo_train.py
└── model/
```

## 执行流程

1. `CrAgentCli` 接收命令或启动 `chat`。
2. `ChatCommandParser` 从自然语言中识别 PR、仓库、commit range、compare URL 或 repo-only 请求。
3. 如果是 repo-only，agent 会尝试读取默认分支最新提交区间。
4. `GitEnvironment` 优先使用本机 Git 环境和本地 clone 生成 review context。
5. 如果本机 Git 不可用，agent 使用 `GITHUB_TOKEN` 通过 GitHub API 获取 PR 或 commit context。
6. `CodeReviewAgent` 执行主流程：
   - `Triage`：判断 docs-only、draft、大变更、风险文件、是否需要人工介入。
   - `Analyze`：收集 diff、文件列表、CI、comments、依赖配置、相关测试、敏感路径和 memory。
   - `Review`：调用 LLM 输出结构化 JSON issues。
   - `Act`：dry-run 或真实执行 review comments、auto-fix PR、memory update 等动作。
   - `Report`：加载 `code-review-report` skill，调用 LLM 生成 report draft，并写入 `report/`。
7. `Evidence Validation` 在行动前过滤证据不足、行号不在 diff、重复或低置信度的问题。
8. `TraceRecorder` 将完整运行轨迹写入 `cr_agent/data/traces/*.jsonl`。

## 配置

复制示例配置：

```bash
cp .env.example .env
```

当前 Java agent 会读取：

- 当前目录 `.env`
- 如果在 `cr_agent/` 内运行，也会读取父目录 `../.env`
- 项目 `.env` 会覆盖同名系统环境变量

示例：

```env
OPENAI_BASE_URL=https://token-plan-cn.xiaomimimo.com/v1
OPENAI_API_KEY=replace-me
OPENAI_MODEL=mimo-v2.5-pro

# 可选。没有 token 时仍可使用本机 Git 路径或 dry-run/local flow。
GITHUB_TOKEN=

CR_AGENT_DRY_RUN=true
CR_AGENT_TRACE_DIR=data/traces
CR_AGENT_MEMORY_DIR=data/memory
CR_AGENT_REPORT_DIR=report
CR_AGENT_MAX_ITERATIONS=30
CR_AGENT_MAX_TOOL_RESULT_CHARS=12000
CR_AGENT_HUMAN_REVIEW_CHANGED_LINES_THRESHOLD=2000
```

配置说明：

- `OPENAI_BASE_URL`：OpenAI-compatible `/v1` base URL。
- `OPENAI_API_KEY`：LLM API key。
- `OPENAI_MODEL`：review 使用的模型名。
- `GITHUB_TOKEN`：GitHub API fallback 和真实 PR 写操作所需 token。
- `CR_AGENT_DRY_RUN`：默认建议 `true`，避免直接写 GitHub。
- `CR_AGENT_TRACE_DIR`：trace 输出目录，相对 `cr_agent/` 工作目录。
- `CR_AGENT_MEMORY_DIR`：memory 输出目录，相对 `cr_agent/` 工作目录。
- `CR_AGENT_REPORT_DIR`：review report 输出目录；相对路径会解析到项目根目录，默认 `report/`。
- `CR_AGENT_MAX_ITERATIONS`：agent tool-use 最大轮数。
- `CR_AGENT_MAX_TOOL_RESULT_CHARS`：单次工具返回最大字符数。
- `CR_AGENT_HUMAN_REVIEW_CHANGED_LINES_THRESHOLD`：超过该 changed lines 阈值时建议人工 review。

## Java 环境要求

当前 Java agent 位于 `cr_agent/`，通过 Gradle Wrapper 运行，不需要单独安装系统 Gradle。

最低要求：

- JDK 21 或更高版本。
- macOS/Linux shell 环境，Windows 可使用 `gradlew.bat`。
- 网络可访问配置的 `OPENAI_BASE_URL`。
- 如果需要读取私有仓库或真实写 GitHub review，需要本机 Git 凭据或 `GITHUB_TOKEN`。

推荐环境：

- IntelliJ IDEA 2024+。
- 使用项目自带 Gradle Wrapper：`cr_agent/gradlew`。
- 本机已配置 Git SSH key，并能执行 `git ls-remote git@github.com:owner/name.git HEAD`。

检查 Java：

```bash
java -version
```

期望版本类似：

```text
openjdk version "21..."
```

如果本机没有 JDK 21，可以用 SDKMAN 安装：

```bash
curl -s "https://get.sdkman.io" | bash
sdk install java 21.0.6-tem
```

也可以使用 Homebrew：

```bash
brew install openjdk@21
```

在 IntelliJ IDEA 中打开：

1. 打开 `CR-Agent/cr_agent/`。
2. 选择 Gradle JVM 为 JDK 21+。
3. 使用 Gradle task `run` 或 `test`。
4. 运行参数示例：`chat` 或 `review --repo owner/name --pr 123`。

注意：建议从 `cr_agent/` 目录运行命令。此时 agent 会读取父目录 `../.env`，也会读取当前目录 `.env`；当前目录 `.env` 优先级最高。

## 快速开始

进入 Java 工程：

```bash
cd cr_agent
./gradlew test
```

启动自然语言 chat：

```bash
./gradlew run --args="chat"
```

chat 示例输入：

```text
帮我 review https://github.com/GongShichen/JTravelAgent.git
review https://github.com/owner/name/pull/123
review owner/name 从 main 到 feature-branch
review https://github.com/owner/name/compare/main...feature-branch
live review owner/name 从 6e83187b 到 0224b0ec
```

如果只给仓库，agent 会默认 review 默认分支最新提交区间。

## 常用命令

Review GitHub PR：

```bash
./gradlew run --args="review --repo owner/name --pr 123"
./gradlew run --args="review --pr-url https://github.com/owner/name/pull/123"
```

Review commit range：

```bash
./gradlew run --args="review-commits --repo owner/name --base main --head feature-branch"
./gradlew run --args="review-commits --repo https://github.com/owner/name --base <base-sha> --head <head-sha>"
```

检查本机 Git 环境：

```bash
./gradlew run --args="git-check --repo owner/name"
```

检查 GitHub token 权限：

```bash
./gradlew run --args="github-token-check --repo owner/name"
```

批量 review：

```bash
./gradlew run --args="batch-review --prs prs.txt"
```

初始化 memory：

```bash
./gradlew run --args="init-memory"
```

查看仓库 memory health：

```bash
./gradlew run --args="health-report --repo owner/name"
```

Inspect trace：

```bash
./gradlew run --args="inspect --trace data/traces/<session>.jsonl"
```

## Review 输出

命令行会输出：

```text
Status: completed
Summary: ...
Trace: data/traces/<session>.jsonl
Report: /path/to/CR-Agent/report/<session>-owner-repo.md
Issues: 0
Actions: 2
```

## Report 输出

每次 review 结束都会进入 `REPORT` 节点：

1. 加载 `src/main/resources/skills/code-review-report/SKILL.md`。
2. 把 session、repo、target、summary、issues、actions、trace path 传给 LLM。
3. 要求 LLM 返回结构化 report draft JSON。
4. 将最终 Markdown report 写入 `report/` 目录。

默认输出位置：

```text
report/<session>-<owner-repo>.md
```

如果 Report 节点的 LLM 调用失败，agent 会写一份 deterministic fallback report；这不会影响主 review 的完成状态。

Issue JSON schema 由 skill prompt 约束，核心字段包括：

```json
{
  "severity": "critical|high|medium|low|info",
  "category": "security|bug|style|performance|maintainability|tests",
  "file": "path/to/file",
  "line": 123,
  "body": "problem",
  "evidence": "exact diff/config/check evidence",
  "impact": "why this matters",
  "suggestion": "fix",
  "autoFixable": false,
  "fixCode": null,
  "confidence": 0.9
}
```

## ToolRouter 与工具层

`ToolRouter` 负责统一注册和执行工具：

- 参数 schema 校验
- 未知工具拦截
- dry-run 写操作拦截
- 大响应截断
- tool call / tool result trace 记录
- 工具异常降级为可读错误，避免轻易 crash 主流程

已实现工具包括：

- GitHub read：PR、diff、changed files、review comments、checks、commit compare、code search、file contents。
- GitHub write：submit review comments、create branch、create or update file、create PR。
- Memory：rules、developer profile、repo patterns、false positives、health report。
- Test generation：框架探测、测试路径推断、测试建议生成。

## Memory

Memory 存在 `cr_agent/data/memory/`，使用 JSONL 文件保存：

- repo review patterns
- developer profile
- issue history
- false positive rules
- health report aggregation

Memory 会参与后续 review，用于降低重复误报、补充团队偏好和识别仓库长期风险模式。

## Trace 与数据导出

每次运行都会写入 trace：

```text
cr_agent/data/traces/<session>.jsonl
```

事件类型包括：

```text
session_start
phase_start
llm_request
llm_response
tool_call
tool_result
issue_found
action_taken
memory_update
session_end
```

导出 SFT：

```bash
cd cr_agent
./gradlew run --args="export-sft --input data/traces --output ../datasets/SFT/sft.jsonl"
```

导出 DPO：

```bash
cd cr_agent
./gradlew run --args="export-dpo --input data/traces --output ../datasets/DPO/dpo.jsonl"
```

一次性导出：

```bash
cd cr_agent
./gradlew run --args="export-datasets --input data/traces"
```

输出目录：

```text
datasets/SFT/sft.jsonl
datasets/DPO/dpo.jsonl
```

## Python 训练

Java 负责 agent 执行和数据导出。SFT/DPO 训练仍由 Python 脚本执行：

```bash
pip install -r train/requirements.txt
python train/sft_train.py --base-model Qwen/Qwen2.5-Coder-7B-Instruct --model-name qwen2.5-coder-7b
python train/dpo_train.py --base-model model/qwen2.5-coder-7b/sft --model-name qwen2.5-coder-7b
```

模型和权重输出：

```text
model/<model_name>/sft/
model/<model_name>/dpo/
```

## 开发与验证

运行测试：

```bash
cd cr_agent
./gradlew test
```

真实链路 smoke test：

```bash
cd cr_agent
./gradlew run --args="chat"
```

然后输入：

```text
帮我 review https://github.com/GongShichen/JTravelAgent.git
```

如果本机存在该仓库 clone，agent 会优先使用本机 Git 生成 diff；否则尝试使用 GitHub API。

## 安全建议

- 首次运行建议保持 `CR_AGENT_DRY_RUN=true`。
- 确认 GitHub token 权限后，再切换到 `CR_AGENT_DRY_RUN=false`。
- 对超大 PR，可调高或调低 `CR_AGENT_HUMAN_REVIEW_CHANGED_LINES_THRESHOLD`。
- 写权限失败不应阻塞 review，agent 会继续输出 summary、issues、actions 和 trace。
