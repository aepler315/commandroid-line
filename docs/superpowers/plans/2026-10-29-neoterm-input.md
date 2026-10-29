# NeoTerm Input Implementation Plan

> Execute inline using superpowers:executing-plans and test-driven-development.

**Goal:** Stage Android IME text outside the PTY, then explicitly transmit it.
**Architecture:** Pure Kotlin lifecycle/paste policy; native EditText owns all
text and selection. Narrow app session/UI hooks preserve direct TerminalView.
**Tech stack:** Termux v0.118.3, Kotlin 1.8.22, JDK 11, AGP 7.4.2, Gradle 7.6.4.
**Spec:** docs/neoterm/ARCHITECTURE.md and user's Milestone 1 requirements.

## Global constraints
Retain com.termux and bootstrap/native/runtime compatibility. No input logs or
persistence. No IME callback submits. Never rewrite terminal input. AUTO defaults
to direct unless explicitly composing. All editor events stay on main thread.

## Review focus
Late callbacks after transmission/session changes; UTF-16 deletion boundaries;
multiline/control injection; terminal focus/modifier behavior; privacy on restart.

### Task 1: Pure input state/paste policy
Files: neoterm-input/build.gradle, src/main/kotlin/com/neoterm/input/InputEngine.kt,
src/test/kotlin/com/neoterm/input/InputEngineTest.kt; root Gradle/settings.
Interfaces: Draft (text, composing, finishComposition, clear); TerminalTarget
(identity, running, bracketedPaste, write); InputEngine mode, generation,
applyImeEdit, bindSession, setMode, openComposer, transmit, submit.
- [x] Write tests for ordinary commands/paths, modes, transitions, composition,
      correction, multiline, Unicode/control validation, stale callbacks,
      no implicit submission, restart, rejected transport and diagnostic privacy.
- [x] Observe RED, implement engine, run :neoterm-input:test, commit.

### Task 2: Android vertical slice
Files: app/src/main/java/com/neoterm/input/{CompositionEditText,NeoTermInputController}.kt,
app build.gradle/resources/layout and TermuxActivity/session client; one read-only
TerminalEmulator bracketed-paste getter; TerminalView logging hardening.
Interface: native InputConnection delegates edits through InputEngine generation
gate; controller binds actual session and explicit buttons, no cursor mirror.
- [x] Write Android IME callback, surrogate deletion, selection, action, stale
      connection, privacy and hardware tests; observe missing functionality.
- [x] Implement widget/controller, selector, explicit send/Enter and Keys toggle.
- [x] Run module and Android JVM suites; build debug and test APKs; commit.

### Task 3: Release readiness documentation and review
Files: docs/neoterm/{BUILD,TESTING,MIGRATION,ROADMAP,VERIFICATION}.md, CHANGELOG.md.
- [x] Document exact environment/results and pending physical IME checks.
- [x] Run all available tests, lint/build and connected instrumentation attempt.
- [x] Fresh branch review; reproduce/fix important findings with regressions.
- [x] Commit verified changes and report artifacts/blockers without publishing.
