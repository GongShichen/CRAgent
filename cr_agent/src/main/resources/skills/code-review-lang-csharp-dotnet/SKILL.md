---
name: code-review-lang-csharp-dotnet
description: C# and .NET review checks for ASP.NET security, EF Core data access, async/cancellation, disposal, logging, and tests.
languages: [csharp]
file_patterns: [*.cs, *.csproj, Directory.Packages.props, appsettings*.json]
risk_triggers: [aspnet, controller, authorization, entityframework, dbcontext, async, cancellationtoken, idisposable, secret]
modes: [diff, repo_audit]
---

# C# / .NET CR Skill

Use this skill for C#, ASP.NET Core, EF Core, and .NET project changes.

## High-Signal Bug Patterns
- ASP.NET Core: endpoint/controller changes need authorization, model validation, antiforgery where applicable, and error-response safety.
- EF Core/data: watch N+1, unbounded queries, raw SQL interpolation, transaction boundaries, concurrency tokens, and migration safety.
- Async: avoid `.Result`/`.Wait()`, propagate `CancellationToken`, check fire-and-forget tasks and exception handling.
- Resource lifetime: `IDisposable`/`IAsyncDisposable`, streams, DbContext, HttpClient, timers, and cancellation sources need clear ownership.
- Secrets/logging: PII, tokens, connection strings, and exception details must not leak.
- Configuration: appsettings, auth schemes, CORS, and dependency version changes can alter production security.

## Evidence Requirements
- Ground claims in changed endpoint/service/config path.
- For tests, prefer xUnit/NUnit/MSTest with mocked external dependencies or Testcontainers for integration boundaries.

