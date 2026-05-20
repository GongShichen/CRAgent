---
name: code-review-lang-kotlin-android
description: Kotlin and Android review checks for nullability, coroutines, lifecycle, threading, Flow, permissions, and JVM interop.
languages: [kotlin]
file_patterns: [*.kt, *.kts, build.gradle.kts, AndroidManifest.xml]
risk_triggers: [coroutine, flow, dispatcher, lifecycle, nullable, platform type, android, permission, main thread]
modes: [diff, repo_audit]
---

# Kotlin / Android CR Skill

Use this skill for Kotlin/JVM or Android code. Keep comments specific to executable behavior.

## High-Signal Bug Patterns
- Null safety: platform types from Java, `!!`, unsafe casts, nullable API changes, and default values can move failures away from the source.
- Coroutines: check structured concurrency, cancellation propagation, `GlobalScope`, supervisor behavior, exception handling, and dispatcher choice.
- Flow/StateFlow: verify replay, backpressure, collection lifecycle, duplicate collectors, and stale UI state.
- Android lifecycle: UI work must happen on the main thread; observers, receivers, callbacks, and jobs must be tied to lifecycle owner or explicitly released.
- Security/privacy: manifest permission/exported component changes, deep links, intents, storage, and logging need trust-boundary evidence.
- Persistence/network: Room transactions, Retrofit error paths, serialization compatibility, and offline cache invalidation are high value.

## Evidence Requirements
- Cite the changed function/class and the lifecycle or coroutine path.
- For missing tests, identify the concrete behavior and expected framework: JUnit, Kotest, Robolectric, instrumented tests, or Compose UI tests.

