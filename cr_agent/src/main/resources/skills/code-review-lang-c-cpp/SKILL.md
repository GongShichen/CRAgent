---
name: code-review-lang-c-cpp
description: C and C++ review checks for memory ownership, buffer bounds, integer overflow, RAII, unsafe APIs, concurrency, ABI, and build changes.
languages: [c, c-header, cpp]
file_patterns: [*.c, *.h, *.cc, *.cpp, *.cxx, *.hh, *.hpp, *.hxx, CMakeLists.txt, Makefile, compile_commands.json]
risk_triggers: [malloc, free, new, delete, memcpy, strcpy, buffer, pointer, lifetime, atomic, mutex, abi, cmake]
modes: [diff, repo_audit]
---

# C / C++ CR Skill

Use this skill for native code and native build changes.

## High-Signal Bug Patterns
- Memory: ownership of `malloc/free`, `new/delete`, smart pointers, moved values, references, and callbacks must be explicit on all paths.
- Bounds: buffer length, null termination, indexing, signed/unsigned conversion, integer overflow, and size arithmetic are high risk.
- Unsafe APIs: `strcpy`, `sprintf`, unchecked `memcpy`, raw casts, varargs, and manual lifetime management need exact evidence.
- RAII/exceptions: destructors, locks, files, sockets, and transactions must release on error and exception paths.
- Concurrency: atomics, memory order, locks, condition variables, thread lifetime, and callbacks need race/deadlock reasoning.
- ABI/build: CMake/Make/include path/compile flag changes can break platform compatibility, sanitizers, or exported ABI.

## Evidence Requirements
- Cite exact allocation/ownership/bounds path or build target.
- For tests, prefer GoogleTest/Catch2/doctest/CTest plus sanitizer/static-analysis evidence when available.

