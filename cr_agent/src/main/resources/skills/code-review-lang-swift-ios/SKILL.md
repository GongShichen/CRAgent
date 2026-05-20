---
name: code-review-lang-swift-ios
description: Swift and iOS review checks for MainActor/threading, async cancellation, retain cycles, Keychain/UserDefaults, networking, privacy, and tests.
languages: [swift]
file_patterns: [*.swift, Package.swift, Podfile, *.xcodeproj, *.xcworkspace]
risk_triggers: [mainactor, dispatchqueue, task, async, keychain, userdefaults, urlsession, delegate, permission, deeplink]
modes: [diff, repo_audit]
---

# Swift / iOS CR Skill

Use this skill for Swift, Swift Package, and iOS/macOS app code.

## High-Signal Bug Patterns
- Threading/lifecycle: UI updates need main actor/thread; tasks, delegates, observers, and notifications must cancel/unregister with lifecycle.
- Retain cycles: closures, delegates, Combine subscriptions, async tasks, timers, and stored callbacks need `weak`/`unowned` reasoning.
- Secrets/privacy: use Keychain or protected storage for tokens; avoid UserDefaults/pasteboard/logging for credentials or PII.
- Networking/data: URLSession status codes, cancellation, retry, decoding errors, timeouts, and offline cache invalidation need explicit handling.
- App flows: deep links, universal links, permissions, background tasks, and biometric/auth flows are trust boundaries.
- Compatibility: Codable schema, migrations, locale/timezone/date handling, and API contract changes can break existing users.

## Evidence Requirements
- Tie findings to concrete actor/thread/lifecycle or storage path.
- For tests, prefer XCTest/Swift Testing/Quick/Nimble async tests for cancellation, error, and lifecycle behavior.
- In repo audit, use `apple_platform_context` or the `apple_xcode_context` tool when present to understand Xcode project/workspace markers, `xcodebuild`, SwiftPM, and Xcode MCP bridge availability before making build/test-environment claims.
