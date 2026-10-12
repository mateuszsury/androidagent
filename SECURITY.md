# Security policy

## Supported versions

Security fixes target the latest published release and current main branch. Older releases remain available as historical evidence but are not separately maintained. Review [releases](https://github.com/mateuszsury/androidagent/releases) before filing a report.

## Report a vulnerability

Use **Report a vulnerability** in the repository's [Security tab](https://github.com/mateuszsury/androidagent/security) when private reporting is enabled. If the button is unavailable, ask the maintainer for a private contact channel without posting vulnerability details; a public issue may contain only that contact request. Do not put credentials, private conversation content, login codes or an exploitable disclosure in a public issue.

Include the affected version/commit, Android version/ABI, relevant permission state, a reproducible sequence, expected access boundary and observed impact. Sanitized logs or a minimal test project help. Do not attach personal device data or a signed-in account archive. There is no promised response SLA or bug-bounty program.

## Relevant boundaries

- Per-run MCP authentication, exact command approval and cancellation.
- USB/SAF ownership, revocation and read/write permission handling.
- App/VM/container filesystem and token exposure, preview navigation and exports.
- Safe extraction, pinned client/runtime verification and update preservation.
- Accessibility consent, protected screens and accidental access beyond the intended Android UID.

Developer-mode agents intentionally have broad access within the app UID; PRoot is not a security sandbox. Android, QEMU and container isolation do not imply root or other apps' private-data access. Third-party client/model issues may need coordinated reporting to the relevant upstream project; include the exact component/version so they can be distinguished from integration bugs.

Normal bugs and setup questions belong in [Support](SUPPORT.md). The policy does not claim that a comprehensive security audit has been completed.
