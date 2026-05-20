# Code Review Checklist

代码审查完整清单，按优先级排序。

## 🔴 安全（Security）— 必查

### SQL 注入
- [ ] 所有数据库查询使用参数化查询或 ORM
- [ ] 无 f-string/字符串拼接构造 SQL
- [ ] 用户输入经过验证和转义

### XSS（跨站脚本）
- [ ] 用户输入不直接渲染到 HTML
- [ ] 使用模板引擎的自动转义功能
- [ ] `innerHTML`/`dangerouslySetInnerHTML` 的使用经过审查

### 认证与授权
- [ ] 所有 API 端点有适当的认证检查
- [ ] 权限检查在服务层而非仅在 UI 层
- [ ] 无越权访问漏洞（IDOR）

### 敏感数据
- [ ] 无硬编码密码、API Key、Token
- [ ] 敏感配置通过环境变量或密钥管理服务读取
- [ ] 日志中不包含敏感信息

### 依赖安全
- [ ] 新增依赖无已知高危漏洞
- [ ] 依赖版本锁定（pinned）

---

## 🟠 Bug 与逻辑（Bugs & Logic）— 重要

### 空值处理
- [ ] 可能为 None/null 的变量有空值检查
- [ ] 数组/列表访问有边界检查
- [ ] 外部 API 响应有异常处理

### 错误处理
- [ ] 异常被正确捕获和处理
- [ ] 错误信息不暴露内部实现细节
- [ ] 关键操作有事务保护（数据库操作）

### 并发与竞态
- [ ] 共享资源有适当的锁机制
- [ ] 数据库操作避免竞态条件
- [ ] 幂等性设计（重试安全）

### 业务逻辑
- [ ] 边界条件已考虑（0、负数、极大值）
- [ ] 状态机转换逻辑正确
- [ ] 计算逻辑无溢出风险

---

## 🟡 代码规范（Style & Standards）— 建议

### 命名规范
- [ ] 变量/函数名清晰表达意图
- [ ] 遵循语言/项目命名约定
- [ ] 无魔法数字（使用常量）

### 函数设计
- [ ] 函数单一职责（≤ 50 行为宜）
- [ ] 参数数量合理（≤ 5 个为宜）
- [ ] 返回值类型一致

### 注释与文档
- [ ] 复杂逻辑有注释说明
- [ ] 公开 API 有文档注释
- [ ] TODO/FIXME 有对应 Issue

### 测试覆盖
- [ ] 新功能有对应单元测试
- [ ] 边界条件有测试覆盖
- [ ] 测试用例有描述性名称

---

## 🟢 性能（Performance）— 可选

### 数据库查询
- [ ] 无 N+1 查询问题
- [ ] 适当使用索引
- [ ] 大数据集使用分页

### 缓存
- [ ] 频繁读取的数据有缓存策略
- [ ] 缓存失效逻辑正确

### 资源管理
- [ ] 文件/连接等资源正确关闭
- [ ] 大文件使用流式处理
- [ ] 无内存泄漏风险

---

## 自动化检查范围

以下问题可以自动修复：
- SQL 注入（参数化查询替换）
- 硬编码密钥（替换为环境变量读取）
- 简单的空值检查缺失
- 代码风格问题（格式化）

以下问题只能人工处理：
- 业务逻辑错误
- 架构设计问题
- 复杂的权限设计
- 性能优化方案选择

---

## 语言专项检查

### Java / Spring / JVM
- [ ] Controller / Filter / Interceptor 的鉴权和权限边界正确，不能只依赖前端控制
- [ ] Spring Security 配置没有误放开敏感路径，CSRF/CORS 配置有明确理由
- [ ] JPA/Hibernate 查询避免字符串拼接 JPQL/SQL，分页和 fetch strategy 不引入 N+1
- [ ] `@Transactional` 边界放在 service 层，异常回滚语义符合预期
- [ ] 外部输入有 Bean Validation 或显式校验，错误信息不泄露内部细节
- [ ] 资源使用 try-with-resources，线程池/连接池有关闭和限流策略
- [ ] 并发代码避免共享可变状态，缓存/单例对象线程安全
- [ ] Optional 不作为字段/参数滥用，nullability 契约清晰
- [ ] 单测优先 JUnit 5；依赖外部系统时用 Mockito/Testcontainers，Spring Boot Test 不过度启动全上下文

### Kotlin / Android / JVM
- [ ] nullable 类型边界明确，避免 `!!` 在生产路径触发崩溃
- [ ] coroutine scope 生命周期正确，避免泄漏、重复收集 Flow、在主线程执行阻塞 I/O
- [ ] suspend 函数错误传播清楚，取消异常不被吞掉
- [ ] data class copy / mutable collection 使用不会破坏不可变语义
- [ ] Android 代码避免 Activity/Fragment context 泄漏，Compose state hoisting 合理
- [ ] 单测使用 Kotest/JUnit，协程测试使用 test dispatcher，mock 使用 MockK/Mockito

### Rust
- [ ] 避免不必要的 `unwrap()` / `expect()` 穿透到生产路径，错误使用 `Result` 传播并保留上下文
- [ ] `unsafe` 块有清楚的不变量说明，范围尽可能小
- [ ] 所有权/生命周期改动没有引入 clone 滥用、悬垂引用或锁持有过久
- [ ] 并发代码避免在 `.await` 跨越期间持有 `MutexGuard` 或阻塞锁
- [ ] I/O、网络、反序列化错误有分类处理，不能 panic
- [ ] Serde 反序列化对外部输入设置默认值/deny unknown fields 时有明确意图
- [ ] Cargo 依赖没有引入高风险 crate 或未锁定的行为变化
- [ ] 单测使用 `cargo test`，异步逻辑用 `tokio::test`，复杂参数用 `rstest`

### JavaScript / TypeScript / Frontend
- [ ] 用户输入渲染避免 `innerHTML` / `dangerouslySetInnerHTML`，必要时使用可信 sanitizer
- [ ] React hook 依赖数组完整，避免 stale closure、重复请求和无限渲染
- [ ] 状态更新避免直接 mutation，异步请求处理 loading/error/abort race
- [ ] TypeScript 避免 `any` 扩散，外部 API 响应用 schema 校验或类型守卫
- [ ] 前端权限仅用于展示控制，敏感操作必须依赖后端鉴权
- [ ] XSS/CSRF/token 存储策略合理，避免把长期敏感 token 放入不安全位置
- [ ] 大列表、图片和重渲染路径有性能保护，避免阻塞主线程
- [ ] React/Vue/Angular 组件测试覆盖用户可见行为；端到端流程用 Playwright/Cypress

### Go
- [ ] 每个返回的 `error` 被处理或明确包装，不能静默丢弃
- [ ] goroutine 生命周期可控，channel 关闭和 context cancellation 语义正确
- [ ] HTTP handler 设置超时、限制 body size，并校验外部输入
- [ ] 数据库查询参数化，事务 commit/rollback 路径完整
- [ ] defer 关闭资源，循环内 defer 使用不会造成资源堆积

### C# / .NET
- [ ] async/await 链路没有 `.Result` / `.Wait()` 死锁风险，CancellationToken 正确传递
- [ ] ASP.NET Core endpoint 有认证授权策略，model binding 输入有验证
- [ ] Entity Framework 查询避免 N+1、未分页大查询和 SQL 拼接
- [ ] IDisposable/IAsyncDisposable 资源释放正确，HttpClient 生命周期合理
- [ ] 日志不包含 PII/secret，异常响应不泄露 stack trace
- [ ] 测试使用 xUnit/NUnit/MSTest，外部依赖用 Moq/Testcontainers 替代真实服务

### PHP / Laravel / Symfony
- [ ] 请求输入使用 validator/form request，不能直接信任 `$_GET`/`$_POST`
- [ ] SQL 使用 ORM/query builder 参数绑定，避免 raw SQL 拼接
- [ ] Blade/Twig 输出默认转义，避免 `{!! !!}` 或 raw filter 渲染不可信内容
- [ ] 认证授权使用 policy/gate/middleware，敏感路由不只靠前端隐藏
- [ ] 文件上传校验 mime/大小/扩展名，并防止路径遍历
- [ ] 测试使用 PHPUnit/Pest，Laravel feature test 覆盖鉴权和异常路径

### Ruby / Rails
- [ ] Strong Parameters 限制可写字段，避免 mass assignment 风险
- [ ] ActiveRecord 查询避免 SQL interpolation、N+1 和未分页导出
- [ ] before_action 鉴权覆盖敏感 action，CSRF 设置有明确理由
- [ ] background job 幂等，可重试失败不会重复副作用
- [ ] secret/credential 不进入日志或异常响应
- [ ] 测试使用 RSpec/Minitest，request spec 覆盖鉴权、权限和错误路径
- [ ] migration 可回滚，数据迁移分批执行，长事务/锁表风险可控
- [ ] rescue 不吞掉关键异常，事务边界内外的副作用不会产生不一致状态

### Swift / iOS
- [ ] UI 更新发生在 main actor/thread，异步任务取消与生命周期绑定
- [ ] force unwrap (`!`) 不出现在可由外部输入触发的路径
- [ ] Keychain/secure enclave 用于敏感数据，避免 UserDefaults 存储 token/password
- [ ] URLSession 错误、状态码和 decoding error 有明确处理
- [ ] delegate/closure 捕获避免 retain cycle，长生命周期对象使用 weak/unowned 有理由
- [ ] 测试使用 XCTest/Swift Testing，异步测试等待和取消路径覆盖完整
- [ ] Codable schema 变更兼容旧数据，日期/时区/locale 处理明确
- [ ] Combine/Task/NotificationCenter observer 生命周期释放明确，避免重复订阅或泄漏
- [ ] App privacy、permission、background task、deep link 和 universal link 路径有鉴权与状态校验

### C / C++
- [ ] buffer 长度、索引、整数溢出和 signed/unsigned 转换有边界检查
- [ ] `malloc/new` 与 `free/delete` 所有权清晰，错误路径无泄漏、double-free 或 use-after-free
- [ ] RAII、smart pointer、move/copy 语义符合对象生命周期，异常路径资源释放正确
- [ ] string/memory API 避免不安全 `strcpy/sprintf/memcpy` 长度错误，跨平台字符编码明确
- [ ] 多线程访问使用明确同步，atomic memory order、锁粒度和死锁风险被验证
- [ ] CMake/Make/compile_commands 变更不会破坏目标、include path、ABI 或 sanitizer/static-analysis 覆盖
- [ ] 测试使用 GoogleTest/Catch2/doctest/CTest，覆盖边界值、错误路径和 sanitizer 可发现的问题

### Objective-C / Cocoa
- [ ] ARC/MRC ownership 语义正确，`weak/strong/copy/assign` 属性选择避免 retain cycle 和 dangling pointer
- [ ] block/delegate/notification/KVO 生命周期释放明确，异步回调不会访问释放对象
- [ ] UI、CoreData 和 UIKit/AppKit 对象在正确线程/queue 使用
- [ ] `NSError **`、nil message、nullable/nonnull 注解和 Objective-C/C++ bridge 错误路径完整
- [ ] Keychain、pasteboard、UserDefaults、URL scheme/deep link 等敏感路径不泄露 token 或绕过鉴权
- [ ] 测试使用 XCTest/OCMock，覆盖 delegate、notification、async callback 和错误路径
