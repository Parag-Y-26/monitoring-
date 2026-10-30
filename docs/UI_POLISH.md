# Drishti v0.6.4 - crop repair and interface alignment

User requests: make the photo crop/apply/save flow scrollable, remove the prominent Usage section, and improve text/button alignment using Apple's design principles. This continues the enhancement work; it does not replace or pass any construction/field acceptance gates.

Design references: [Apple layout guidance](https://developer.apple.com/design/human-interface-guidelines/layout) and [button hierarchy](https://developer.apple.com/design/human-interface-guidelines/buttons). Applied clarity, aligned margins, grouping and a prominent primary action using native Android Compose. Existing palette, system fonts, reduced-motion support and Android touch targets remain in use.

| Principle | Before | After |
| --- | --- | --- |
| Navigation hierarchy | Four main destinations, including Usage | Monitor, Items, Settings; encrypted request history is optional under Settings > Tools > API activity |
| Alignment | Toolbar text began at 16dp while page content began at 20dp; action heights/wrapping differed | Shared EdgePageHeader aligns at 20dp; shared primary/secondary buttons have a 52dp minimum, 14dp corners, consistent padding and centered multiline text |
| Clear emphasis | Repeated numbered/capitalized section headings; oversized target card; development-status footer | Short subtitles, quieter secondary typography, compact setup tabs, grouped saved-item controls and a single target row with trailing chevron |
| Live controls | Long Stop label caused the floating action bar to wrap unevenly | Three equal-width Stop / Rescan / More controls; Stop retains the accessibility label "Stop monitoring" |
| Scroll and gesture ownership | Entire Items page stopped scrolling while a photo was unapproved; every image drag moved a corner | Page scroll stays enabled. Only drags beginning within 32dp of a crop corner resize it; ordinary image-center swipes scroll. Mask strokes consume their own gestures |
| Discoverable apply/save | Tall portrait canvas placed Use this crop below the visible area; Crop was a mode chip | Adjust crop is explicitly a mode; Use this crop precedes the aspect-correct preview, capped at 360dp height. Name, Save and optional details are separate scrollable items |
| Button motion | Platform ripple only; labels/highlights could change abruptly | Shared 0.97 press scale with a snappy Compose spring, 160ms label/selection fades, immediate click dispatch and reduced-motion/low-RAM gating |

Implementation: `EdgeControls.kt`, `EdgeAppShell.kt`, `EdgePulseTheme.kt`, `ItemSetupScreen.kt`, `MonitoringScreen.kt`, `EdgePulseScreen.kt`, `ApiKeyGateScreen.kt`, `ApiUsageScreen.kt`, and MainActivity navigation. No new dependency, database, model, key, consent, network-owner or detector behavior changes.

## Regression evidence

- Added ItemCropInstrumentedTest before the repair. Both initial tests failed because no ScrollBy action existed (`artifacts/crop-regression-red.xml`), reproducing the screenshot's page-wide lock.
- Corner-drag and mask checks passed after gesture changes. The compact save test additionally caught a virtualized off-screen Save control; fields/actions now use separate lazy items and tests scroll the list to the actual action.
- Navigation test first failed because Usage was still a primary tab (`artifacts/navigation-polish-red.log`); it now requires three destinations, correct Settings selection for the history subpage and persistent Stop/Return controls while away from an active session.
- Whole-app journey now opens optional API history from Settings and returns to setup without making a provider call.
- Motion RED: ButtonMotionInstrumentedTest initially failed because a held button did not compress. Both spring-response and reduced-motion/single-click checks then passed (`artifacts/button-motion-red.log`, `button-motion-green.log`). Applied motion-foundations and motion-patterns through Compose APIs with tokens in EdgeMotion; no new runtime dependencies.
- Final `:core:check`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:lintDebug`: pass. 68 JVM tests, 97.04% line coverage; 14 Python tests and three exporter interface tests pass. pip-audit reports no known vulnerabilities; diff whitespace and targeted secret checks pass.
- Full API 36 emulator run: **31/32 pass, zero skips**. The sole failure remains the documented pinned cow-image miss at threshold 0.25. All UI/crop/motion/navigation/interlock/storage/camera-pipeline tests pass. See `artifacts/v064-full-validation.log` and `artifacts/ui-polish-20261031/full-emulator-results.xml`.
- Physical Samsung SM-A346E: all three crop/scroll/mask/apply/offline-save tests pass; both button spring/reduced-motion tests pass. See `artifacts/v064-phone-evidence/crop-results.xml` and `motion-results.xml`. These are isolated fixture flows on real hardware, not field-accuracy tests.
- Visually reviewed dark/light/compact layouts and the reachable crop/save actions. Physical press renders were collected under `artifacts/v064-phone-evidence/button-motion`; restored real-app Home render is `restored-launch.png`.
- Deliverable: `artifacts/Drishti-v0.6.4-debug.apk`, version code 10, SHA-256 `120dc5a158b0982d26fb262eabdb2181457ee596e57c411a92705e35834c7679`. Reinstalled with `adb install -r` after test cleanup; installed digest matches, MainActivity cold launch returns Status ok, launcher intent resolves, and a live process/resumed Activity are observed.

## Device-test cleanup incident

The connected test runner removed the installed APK during cleanup. The user reported the missing launcher icon. Reinstalling restored the launcher/activity, but the app now shows fresh setup and no saved target/key; retained settings/history/identity continuity must not be claimed. The encrypted key cannot be reconstructed from the APK; reconnect the key inside the app and redo setup. This incident was disclosed to the user.

The project now sets `android.injected.androidTest.leaveApksInstalledAfterRun=true`, verified as a supported option in the installed AGP 9.4.1 bytecode. No further personal-device instrumentation was run after restoration. Future destructive tests belong on disposable emulator profiles or a separate QA application identity. This option is a cleanup guard, not a backup or data-recovery guarantee.

The existing pinned cow-model miss and physical field/performance/live-provider gates remain open. The user explicitly requested committing/pushing this source to the existing `codex/edgepulse` branch and updating the phone. No phase-completion tag or field acceptance is claimed. GitHub Actions has no configured workflow/run in this repository; local checks are not CI evidence.
