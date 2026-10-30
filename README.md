# Drishti

Native Kotlin/Compose inventory monitoring app under development. Users can select a trained object category or enroll a reference photo; gardening remains removed.

The source of truth is [the attached v5.0 guide](docs/edgepulse_spec_v5.md), as approved by the user, followed by the overriding [user build contract](docs/BUILD_CONTRACT.md). See [decisions](docs/DECISIONS.md), [status](docs/STATUS.md), [local verification](docs/VERIFICATION.md), and [dependency evidence](docs/dependency-resolution.json).

Use a JDK supported by Gradle 9.6, Android SDK 37, and `./gradlew :core:check :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`. This workspace was verified with Android Studio's JBR 25; JVM bytecode targets Java 17. Run the Python suite with `python -m unittest discover -s tests -v`. No credentials are bundled; documented public provider origins configure key-only setup.

Home provides Start; active monitoring fills the display with camera labels, a compact status pill and a floating Stop/Rescan/More bar. Monitor/Items/Usage/Settings tabs return outside the live view. The [Figma design](docs/FIGMA_DESIGN.md) records the redesign and later full-screen amendment. Motion gating, initial/manual/60-second scans, raw-YUV pre-roll and a camera foreground service keep inference local. Counts are full-frame observations, not cumulative stock totals or validated crossing events.

Choose all 80 trained COCO categories, one category, or a cropped reference photo. Custom photo matching uses multiscale appearance correlation; it is experimental, depends on view/scale/lighting and is not a newly trained arbitrary-object detector. AI-generated descriptions are editable metadata. The [model choice](docs/ITEM_MONITORING_PLAN.md) explains why text-only Laya is not the camera model.

Gemini, OpenRouter and NVIDIA NIM setup asks for a key and loads a searchable model list after Connect. Catalogs/selection are encrypted and work offline; refresh is explicit. NVIDIA requires selected-model verification. A photo can be cropped/redacted, reviewed and explicitly Confirmed & Uploaded for characteristics while monitoring is stopped. The API usage screen explains each purpose and records HTTP outcome, image count and provider-reported tokens. Prices and missing usage remain unknown. See [UX improvements](docs/UX_IMPROVEMENTS.md) and STATUS for actual live-test evidence.

Two Apache-2.0 COCO YOLOX-Tiny FP16/INT8 models are bundled. See [conversion provenance](docs/MODEL_PREPARATION.md). Docker is only a build-time converter; the app runs locally on the phone. Device observations and pending field gates are recorded in STATUS. These baseline models are not cement-trained.

Settings prepares the audit identity/device benchmark and chooses a local SAF report folder. Verified checkpoints are requested every four hours, subject to Android delays. Session rows and gap adapters exist; signed crossing events and line-counter setup remain pending. Use `tools/Test-AndroidDevice.ps1 -Serial <phone-serial>` to install/check cold launch; close active sessions normally before updating. Physical performance/accuracy/battery results remain human-gated.

The active plan has five construction-only phases and four database tables. Schema upgrades retain construction sessions, signed audit history and keys. The original spec and v1 schema remain historical/migration references.

Phase completion tags are created only after all required checks pass. Physical checks remain HUMAN-GATED until actual evidence is collected.
