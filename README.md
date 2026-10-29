# EdgePulse (Drishti) AI

Native Kotlin/Compose offline monitoring app under construction.

The source of truth is [the attached v5.0 guide](docs/edgepulse_spec_v5.md), as approved by the user, followed by the overriding [user build contract](docs/BUILD_CONTRACT.md). See [decisions](docs/DECISIONS.md), [status](docs/STATUS.md), [local verification](docs/VERIFICATION.md), and [dependency evidence](docs/dependency-resolution.json).

Use a JDK supported by Gradle 9.6, Android SDK 37, and `./gradlew :core:check :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`. This workspace was verified with Android Studio's JBR 25; JVM bytecode targets Java 17. Run the Python suite with `python -m unittest discover -s tests -v`. No credential or cloud endpoint is bundled.

Gemini, OpenRouter and NVIDIA NIM setup is implemented with separate encrypted credentials and user-entered HTTPS validation endpoints. Validation requires an explicit Confirm & Upload tap; real provider calls have not been tested. The native foundation has passed eight emulator integration tests. Monitoring features are still pending.

Four Apache-2.0 COCO YOLOX Tiny/Nano FP16/INT8 models are bundled and invoke successfully on Android. See [conversion provenance](docs/MODEL_PREPARATION.md). Docker is only a build-time converter; the app runs locally on the phone. Physical benchmarking remains HUMAN-GATED. These baseline models are not cement-trained.

The setup screen creates an optional verifier-challenged audit identity and exports public identity/checkpoints/benchmark results to a user-selected local SAF folder. Verified checkpoint export is scheduled every four hours, subject to Android scheduling delays. There is no monitoring history until later phases run. Use `tools/Test-AndroidDevice.ps1 -Serial <phone-serial>` to install and independently check the built APK's cold launch; it refuses emulators unless explicitly allowed.

Phase completion tags are created only after all required checks pass. Physical checks remain HUMAN-GATED until actual evidence is collected.
