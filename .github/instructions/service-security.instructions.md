---
applyTo: "app/src/main/kotlin/com/adsamcik/mindlayer/service/**/*.kt,docs/AUTHORIZATION.md"
description: "Service authorization, allowlist, and security rules"
---

<!-- context-init:managed -->

- Every external AIDL entry point must call the authorization gate before doing work.
- Keep authorization order intentional: caller identity, rate limiting, allowlist, then session ownership.
- Reject ambiguous caller identities such as shared UIDs.
- Keep allowlist decisions based on `(packageName, signingCertSha256)`; dashboard approvals pin the signing cert.
- Treat unknown sessions as unauthorized to avoid leaking which session IDs exist.
- Never weaken the manifest signature permission or default-deny allowlist without documenting the deployment reason.
