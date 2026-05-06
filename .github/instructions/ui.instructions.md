---
applyTo: "app/src/main/kotlin/com/adsamcik/mindlayer/service/ui/**/*.kt"
description: "Dashboard and diagnostics UI rules"
---

<!-- context-init:managed -->

- The UI is a troubleshooting console for a provider service, not a consumer chat app.
- Prioritize one clear readiness answer before secondary metrics.
- Keep dashboard order provider-first: runtime readiness, active sessions, test inference, investigation links, telemetry, caller access.
- Use Material 3 Expressive for hierarchy, shape, and purposeful motion; do not add decorative motion.
- Use text labels in addition to color for readiness, thermal, memory, and error states.
- Keep security-sensitive caller approval details explicit and confirm destructive/revocation actions.
