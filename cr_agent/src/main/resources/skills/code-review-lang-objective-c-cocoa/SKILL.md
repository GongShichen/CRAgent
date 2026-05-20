---
name: code-review-lang-objective-c-cocoa
description: Objective-C and Cocoa review checks for ARC ownership, blocks/delegates/KVO, NSError/nullability, UI/CoreData threading, Keychain, and tests.
languages: [objective-c]
file_patterns: [*.m, *.mm, *.h, Podfile, *.xcodeproj, *.xcworkspace]
risk_triggers: [arc, retain, release, weak, strong, delegate, block, kvo, nserror, keychain, coredata, uikit]
modes: [diff, repo_audit]
---

# Objective-C / Cocoa CR Skill

Use this skill for Objective-C, Objective-C++, Cocoa, UIKit, AppKit, and mixed native Apple code.

## High-Signal Bug Patterns
- Ownership: ARC/MRC semantics, `weak/strong/copy/assign`, bridging, autorelease pools, and `dealloc` paths can cause leaks or dangling references.
- Lifecycle: blocks, delegates, KVO, notifications, timers, and async callbacks must unregister/cancel and avoid retain cycles.
- Threading: UIKit/AppKit/CoreData access must happen on the correct queue/thread; callbacks can cross queues unexpectedly.
- Error/nullability: `NSError **`, nil messages, nullable/nonnull annotations, and ObjC/C++ bridge exceptions need explicit error paths.
- Security/privacy: Keychain, pasteboard, UserDefaults, URL schemes, deep links, certificates, and logging are trust boundaries.
- Dependencies: Podfile/Xcode project changes can alter build flags, deployment targets, and linked frameworks.

## Evidence Requirements
- Cite the exact ownership/lifecycle callback or sensitive storage path.
- For tests, prefer XCTest/OCMock cases for delegates, notifications, async callbacks, and error paths.
- In repo audit, use `apple_platform_context` or the `apple_xcode_context` tool when present to verify `.xcodeproj`/`.xcworkspace`, CocoaPods, `xcodebuild`, and Xcode MCP bridge availability before relying on Apple build/test context.
