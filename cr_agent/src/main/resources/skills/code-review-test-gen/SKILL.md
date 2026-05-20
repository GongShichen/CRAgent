---
name: code-review-test-gen
description: |
  针对 PR 中未被测试覆盖的新代码路径，自动生成单元测试并作为 Fix PR 的一部分提交。
  内部 skill，由 code-review-act 在满足触发条件时调用。也可单独触发：
  "generate tests for PR #N", "create unit tests", "add test coverage",
  "生成测试用例", "自动生成单元测试", "补充测试覆盖"。
  Make sure to use this skill whenever the user asks to generate tests,
  add test coverage, or create unit tests for a PR.
---

# Code Review Test Generation

针对 PR 中新增的业务逻辑代码，自动生成单元测试，提升代码覆盖率。

## 触发条件

满足以下**所有**条件时触发：
1. `changed_files` 中包含业务逻辑文件（非 `test_*.py`/`*_test.*`/`*.test.*`/`*.spec.*`）
2. `additions > 20`（新增代码超过 20 行）
3. diff 中包含新增的函数或方法定义（`def ` / `function ` / `const.*=>` / `class `）

## 输入

- `diff`：PR 的代码变更（来自 `get_pull_request_files`）
- `framework_info`：测试框架信息（来自 `detect_test_framework`）
- `owner/repo`：仓库标识
- `pr_number`：PR 编号

## 分析步骤

### Step 1：识别新增函数/方法

从 diff 中提取所有新增（`+` 开头）的函数/方法定义：

```
Python:  def function_name(...)
JS/TS:   function name(...) | const name = (...) => | async name(...)
Java:    public/private/protected returnType methodName(...)
Rust:    fn function_name(...) | async fn function_name(...)
Go:      func functionName(...)
Frontend: React/Vue/Angular component exports, hooks, handlers, route loaders
```

### Step 2：分析代码路径

对每个新增函数，识别需要测试的路径：

1. **正常路径**：函数的主要功能（happy path）
2. **边界条件**：空值、零值、最大值、最小值
3. **异常路径**：错误输入、抛出异常的场景
4. **条件分支**：`if/else`、`try/except`、`switch` 的各个分支

### Step 3：生成测试代码

根据 `framework_info.primary_framework` 选择测试模板（参考 `references/test-patterns.md`）。

#### Prompt 结构

```
你是一个测试工程师。请为以下新增代码生成单元测试。

## 新增代码
{diff_additions}

## 测试框架
{framework}: {version}

## 要求
1. 覆盖正常路径、边界条件和异常路径
2. 每个测试用例有清晰的名称（描述测试场景）
3. 使用 {framework} 的标准断言方式
4. Mock 外部依赖（数据库、HTTP 请求、文件系统）
5. 测试文件路径：{test_file_path}

## 现有测试文件（如有）
{existing_tests}

输出格式：
```json
{
  "test_file_path": "tests/test_xxx.py",
  "test_code": "完整的测试文件内容",
  "test_count": 数字,
  "coverage_summary": "覆盖了哪些场景的简要说明"
}
```
```

### Step 4：确定测试文件路径

| 语言 | 原始文件 | 测试文件路径 |
|------|---------|------------|
| Python | `src/services/user.py` | `tests/test_user.py` |
| Python | `app/utils/helpers.py` | `tests/utils/test_helpers.py` |
| JavaScript | `src/utils/format.js` | `src/utils/format.test.js` |
| TypeScript | `src/api/users.ts` | `src/api/users.spec.ts` |
| React TSX | `src/components/Button.tsx` | `src/components/Button.test.tsx` |
| Vue | `src/components/UserCard.vue` | `src/components/UserCard.test.ts` |
| Angular | `src/app/user.service.ts` | `src/app/user.service.spec.ts` |
| Java | `src/main/java/com/acme/UserService.java` | `src/test/java/com/acme/UserServiceTest.java` |
| Kotlin | `src/main/kotlin/com/acme/UserService.kt` | `src/test/kotlin/com/acme/UserServiceTest.kt` |
| Rust | `src/lib/auth.rs` | `src/lib/auth_test.rs` 或 `tests/auth_test.rs` |
| Go | `pkg/user/service.go` | `pkg/user/service_test.go` |
| PHP | `app/Controller/UserController.php` | `tests/Controller/UserControllerTest.php` |
| Ruby | `app/models/user.rb` | `spec/models/user_spec.rb` |
| C# | `src/UserService.cs` | `tests/UserServiceTests.cs` |
| Swift | `Sources/App/UserService.swift` | `Tests/UserServiceTests.swift` |
| C/C++ | `src/parser.cpp` | `tests/parser_test.cpp` |
| Objective-C | `Sources/AuthManager.m` | `Tests/AuthManagerTests.m` |

### 支持的测试框架识别

| 生态 | 配置文件 | 框架 |
|------|----------|------|
| Python | `requirements.txt`, `pyproject.toml`, `setup.cfg` | pytest, unittest, nose |
| JavaScript/TypeScript | `package.json` | jest, vitest, mocha, jasmine |
| Frontend | `package.json` | React Testing Library, Vue Test Utils, Angular TestBed, Playwright, Cypress |
| Java | `pom.xml`, `build.gradle`, `build.gradle.kts` | JUnit 5, JUnit 4, TestNG, Mockito, Spring Boot Test |
| Kotlin | `build.gradle`, `build.gradle.kts` | Kotest, MockK, JUnit |
| Rust | `Cargo.toml` | cargo test, tokio::test, rstest, mockall |
| Go | file extension / module convention | testing |
| PHP | `composer.json` | PHPUnit, Pest, Laravel Test |
| Ruby | `Gemfile` | RSpec, Minitest, Rails Test |
| C#/.NET | `.csproj`, `Directory.Packages.props` | xUnit, NUnit, MSTest, Moq |
| Swift | `Package.swift` | XCTest, Swift Testing, Quick/Nimble |
| C/C++ | `CMakeLists.txt`, `Makefile`, `compile_commands.json` | GoogleTest, Catch2, doctest, CTest |
| Objective-C | `Podfile`, `.xcodeproj`, `.xcworkspace` | XCTest, OCMock |

### Step 5：提交测试文件

将生成的测试代码提交到 Fix PR（与自动修复共用同一 PR，或单独创建 test PR）：

```
# 先获取现有测试文件（如果存在）
file_info = get_file_contents(owner, repo, test_file_path, branch=fix_branch)

# 写入测试文件
create_or_update_file(
  owner=owner, repo=repo,
  path=test_file_path,
  content=test_code,
  sha=file_info.sha if file_info else None,  # 更新时需要 sha
  message=f"test: add unit tests for PR #{pr_number} changes",
  branch=fix_branch
)
```

## 输出

```
## 自动生成测试用例

**测试框架**: {framework}
**生成文件**: {test_file_path}
**测试用例数**: {test_count}

### 覆盖场景
{coverage_summary}

### 测试文件已添加到 Fix PR #{fix_pr_number}
```

## 注意事项

1. **不修改业务代码**：只生成测试文件，不改动被测试的代码
2. **保持幂等**：如果测试文件已存在，追加新测试用例而不是覆盖
3. **Mock 外部依赖**：数据库、HTTP、文件系统等必须 mock，避免测试依赖外部环境
4. **测试命名规范**：
   - Python: `test_<function_name>_<scenario>`
   - Java: `<ClassName>Test` + `methodName_condition_expectedResult`
   - Kotlin: `<ClassName>Test` + descriptive StringSpec/BehaviorSpec case names
   - Rust: `function_name_condition_expected_result`
   - Go: `TestFunction_Scenario`
   - PHP: `test_<behavior>` 或 Pest `it('...')`
   - Ruby: `describe` + `it '...'`
   - C#: `<Method>_<Condition>_<ExpectedResult>`
   - Swift: `test<Behavior>()`
   - C/C++: `TEST(...)`/`TEST_CASE(...)`
   - Objective-C: XCTest method `- (void)test<Behavior>`
   - Frontend: `renders ...`, `calls ... when ...`, `shows ... on error`

读取 `references/test-patterns.md` 了解各语言测试框架模板。
