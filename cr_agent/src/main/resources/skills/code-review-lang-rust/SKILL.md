---
name: code-review-lang-rust
description: Rust review checks for unsafe invariants, ownership, lifetimes, panic boundaries, async Send/Sync, error handling, and Cargo changes.
languages: [rust]
file_patterns: [*.rs, Cargo.toml, Cargo.lock]
risk_triggers: [unsafe, unwrap, expect, panic, lifetime, send, sync, tokio, pin, ffi]
modes: [diff, repo_audit]
---

# Rust CR Skill

Use this skill for Rust crates, binaries, and Cargo changes.

## High-Signal Bug Patterns
- `unsafe`: every unsafe block must have a local invariant; check aliasing, lifetimes, alignment, initialization, FFI boundaries, and thread safety.
- Panic boundaries: `unwrap`, `expect`, `panic!`, indexing, and integer arithmetic must not be reachable from untrusted or recoverable runtime paths.
- Ownership/lifetimes: check moved values, borrowed aliases, interior mutability, drop order, and resource cleanup.
- Async/concurrency: `Send`/`Sync`, `Arc<Mutex<_>>`, cancellation, spawned tasks, channels, and blocking calls inside async runtimes are high risk.
- Error handling: lost context, broad `anyhow` conversion at API boundaries, and partial writes need concrete review.
- Cargo: feature flags, dependency versions, build scripts, and lockfile changes can alter security/compatibility.

## Evidence Requirements
- For unsafe claims, cite the exact invariant violation or missing proof.
- For tests, prefer unit/property tests, `tokio::test`, `rstest`, or Miri/sanitizer evidence when applicable.

