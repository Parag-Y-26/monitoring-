# Native Android design contract

2026-10-30: the user requested the supplied Sharp Blades palette, design/motion skills and a Figma redesign. The latest direction is full-screen live monitoring with a floating bottom bar and minimal copy. See FIGMA_DESIGN.md.

- Purpose/audience: operators choosing inventory targets and monitoring from a stationary phone. Home leads to item selection and Start; AI/device/report setup remains separate.
- Direction: quiet industrial monochrome. Exact sampled swatches: `#F3F3F3`, `#BCBCBE`, `#060606`, `#4B4C51`, `#D0D0D2`. Use contrast, labels and selection marks rather than color alone. Only the live camera uses a subtle black readability scrim; no decorative gradient or hero area.
- Hierarchy: Monitor/Items/Usage/Settings tabs outside sessions. Active monitoring fills the display with a compact status pill and floating Stop/Rescan/More bar; diagnostics and explanations stay behind More. Setup retains numbered steps. Provider labels must not split inside words.
- Target renders: `360x780dp` dark and light, plus `320x568dp` with 1.3x text. Native Compose fixtures exercise narrow layouts without changing the user's phone settings. Also inspect the real installed app on SM-A346E.
- Motion: shared duration tokens; short interruptible opacity/color/graphics transitions. No layout-size animation, idle loops or network-driven speculative progress. Honor Android's animation-off setting and suppress nonessential motion on low-RAM devices.
- Accessibility: native editable/selectable semantics, 48dp targets, readable neutral contrast, visible selected state, scroll/IME support, status announcements and no animation required to understand an outcome.

| State | Trigger / feedback / next action |
| --- | --- |
| Loading | Secure-storage startup or explicit operation; bounded request, progress and Cancel where supported. |
| Empty | No key/models/history; clear entry, Connect or prepare/folder action. |
| Error | Rejected key/network/storage failure; explanatory text and reconnect/retry or storage review. |
| Partial | NIM public catalog or incomplete device/folder setup; clearly labelled remaining verification/prerequisite. |
| Success | Encrypted saved connection/catalog; choose model or continue. |
| Permission | Reports requires a local folder and explicit Android SAF access; recover from revoked access by choosing again. |

Rendered evidence and actual limits belong in VERIFICATION and the ignored local design-evidence record. Fixture renders prove layout/state handling, not live provider acceptance or TalkBack usability. The design/motion skills' web-specific React/CSS mechanisms are adapted to native Compose; no web runtime is added.
