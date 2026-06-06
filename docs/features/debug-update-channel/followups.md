# Debug Update Channel Followups

Migrated from global `memory/followups.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## UI visual QA - Button-like shaped ripple device pass (deferred 2026-05-27)

**Scope:** Shaped ripple controls added in the button-like control pass:
compact dialog text buttons, DateTimeDialog tabs and dropdown entry controls,
DateTime recurrence icon/text actions, NoticeableNotification action icons,
Detail quick-remind/checklist controls, Settings help icons, AudioRecord side
icons, and the converted HabitDetail "Got it" button.

**Path:** Install the debug APK and smoke-test light App Chrome, dark App
Chrome, light Thing Background, dark Thing Background, and a gradient Thing
Background. Verify that press feedback is pill/circular, that text/icon visual
positions did not shift, and that full-row/full-card surfaces still use their
original full-row feedback.

**Risk if left undone:** compile verifies the helper wiring, but it does not
prove the ripple mask is visually correct on Material TabLayout internals or on
all shaped foreground hosts.

## Debug update channel - Move the Aliyun update source to HTTPS (deferred 2026-05-28)

**Scope:** The initial debug APK update channel may need to start from a bare
Aliyun server IP before a domain or automated IP-address certificate setup is
ready.

**Path:** Prefer a domain with a normal HTTPS certificate, or automate a public
IP-address certificate if the channel must stay on a bare IP. Once HTTPS is
available, remove any debug-only cleartext HTTP allowance used during the
bootstrap phase.

**Risk if left undone:** SHA-256 protects the APK payload, but cleartext
metadata can still be tampered with to cause repeated downloads, wrong URLs, or
other update-channel disruption.

## Debug update channel - Design the release update channel before publishing release builds (deferred 2026-05-28)

**Scope:** The initial About-screen update feature is intentionally debug-only.

**Path:** Before publishing a release APK, decide whether release builds should
use a formal update channel, whether that channel can reuse the static metadata
shape, and what hosting/security requirements apply. Release builds must not
reuse the debug HTTP/IP channel; if a release channel is exposed, it should use
independent release metadata and at least HTTPS hosting.

**Risk if left undone:** A release APK could either omit update support that the
user expects later, or expose an internal debug channel to normal users.

## Debug update channel - Configure and smoke-test the Aliyun debug update channel (deferred 2026-05-28)

**Scope:** The debug APK update feature has repository support, app-side
checking/download/install flow, and a Gradle publish task, but the user's Aliyun
IP and SSH details are not available yet.

**Path:** Add `everythingdone.update.*` values to local `local.properties`, set
up `/var/www/everythingdone-updates` on the Aliyun server using
`server/update-debug-apk/`, run `:app:publishDebugUpdate`, install a previous
debug APK on a test device, then verify About → Check update → download
progress → SHA-256 verification → unknown-source permission recovery → system
installer.

**Risk if left undone:** Compile proves the code paths link, but it does not
prove server hosting, metadata URL injection, HTTP/IP access, APK installer
handoff, or real-device permission recovery.
