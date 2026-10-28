# Product

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

<!-- Targets: web tutor console + Android tutor app + Android child agent. No iOS target. -->

## Stack

delegated: FastAPI + SQLAlchemy (PostgreSQL in production, SQLite for zero-setup local demo) for the API; a zero-build HTML/CSS/JS tutor console served by FastAPI; Kotlin + Jetpack Compose + SQLCipher + WorkManager for the Android app, which supports both the tutor role and the child agent. Chosen for KISS, testability, and native access to Android usage/notification APIs.

## Users

**Primary:** tutors (parents or legal guardians in Chile) with one to three children aged 8–15 who use an Android phone. They check the console at home, often on a phone browser, in short sessions: "is everything okay?" and "what happened this week?".

**Secondary:** the child, who installs the agent with the tutor and needs to understand what is and is not shared. The child never sees the tutor console.

## Product Purpose

Make multi-device parental pairing trivial (QR in under 60 seconds, manual code as fallback), collect only derived behavioural signals computed on the child's device, and turn them into few, explainable alerts the tutor can act on. Success means the tutor pairs confidently, understands every alert without reading the child's messages, and can revoke access in one action.

## Positioning

Privacy is enforced by architecture, not policy: raw text never leaves the child's phone. The agent sends only a `DerivedFeatureVector` (category, lexical flag counts, a salted contact hash, usage minutes). Alerts explain which rules fired, never quote content. A neighbouring product cannot copy the claim without rebuilding its data contract.

## Operating Context

University thesis project ("Proyecto de Título"). Demo runs locally with seeded synthetic data and an Android emulator. Interface language is Chilean Spanish. The product must be demonstrable without cloud services, third-party analytics, or a Play Store release. Legal context: Ley N.° 21.719 (Chile) and Privacy by Design; the tutor consents, the child sees an always-on notice.

## Capabilities and Constraints

- Tutor accounts: registration, email verification, password recovery, rotating refresh sessions on web/Android, consent history, export and deletion.
- Pairing: tutor declaration plus child assent, QR JWT (10 min) or a 6-digit manual code, live confirmation over WebSocket, revocable binding.
- Capture: Android usage stats and notification metadata, sanitized on-device; optional accessibility is **out of scope** (Play policy risk).
- Sync: offline-first SQLCipher queue, WorkManager retries, idempotent batches.
- Risk: deterministic, explainable rules (critical lexical flags, night use, unknown-contact repetition, excessive duration).
- Console and Android tutor mode: pairing, overview, devices, alert review, account settings. No message content, no screen mirroring, no remote control.
- Demo data is synthetic and must stay labelled as such in the UI.
- Known limitation: the risk engine is heuristic, not a clinical or diagnostic tool.

## Brand Commitments

- Name: **IA Guardian** (working title; "GuardianIA" acceptable in code identifiers).
- Voice: calm, plain Chilean Spanish, adult-to-adult. Never alarmist, never cute, never surveillance-flavoured. The child is a person, not a target.
- The product must feel like a trustworthy instrument: quiet surfaces, precise language, visible privacy boundaries.

## Evidence on Hand

No real customer data, testimonials, benchmarks, or institutional endorsements exist. All names, devices, alerts, and usage figures in the demo are synthetic and must be presented as such.

## Product Principles

1. **Privacy is visible.** Every screen can answer "what does GuardianIA know?" in one sentence.
2. **Calm over alarm.** Severity is earned by evidence; the default state is reassuring.
3. **Explain, don't judge.** Every alert names the rules that fired and what to do next.
4. **Fewer, better signals.** One clear alert beats ten noisy ones.
5. **Reversible by design.** Revoking a device is one action and takes effect immediately.

## Accessibility & Inclusion

Web console: WCAG 2.2 AA (contrast, keyboard, focus, reduced motion, 44px targets). Android: Material 3, TalkBack labels, 48dp targets, system font scale, dark theme. Spanish (Chile) only in this phase.
