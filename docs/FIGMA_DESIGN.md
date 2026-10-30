# Android UI redesign

[Editable Figma file](https://www.figma.com/design/dVZ9zIT491UUQbUhOlX1VE?node-id=3-43), created 2026-10-30.

| Screen | Node | Native implementation |
| --- | --- | --- |
| Monitor / ready | 3:363 | MonitoringScreen, TargetSelector |
| Items | 3:403 | ItemSetupScreen |
| API usage | 3:438 | ApiUsageScreen |
| Settings | 3:464 | EdgePulseScreen, ApiKeyGateScreen |

The design uses the supplied Sharp Blades palette, Roboto, editable auto-layout/text, a shared TargetSelector component, Material 3 button instances and bound palette tokens. The final reviewed composition contained 129 editable descendants and no image-filled nodes. No private photo, credential or production metric was uploaded. Screens were read back using get_design_context and translated into native Compose; no React/Tailwind runtime or screenshot-based UI was added.

The user then requested full-screen active monitoring, a floating bottom control bar and shorter text. That request overrides the ready-screen composition for active sessions: PreviewView FILL_CENTER occupies the whole screen, labels use the matching center-crop projection, a top status pill shows target/detections, and the bottom bar exposes Stop/Rescan/More. Normal tabs return when stopped. More holds diagnostics, including burst duty and 30-second freeze events. Counts refer to the full analyzed camera frame, which can extend beyond the cropped preview; they are not stock totals.

Figma's Starter-plan MCP call limit prevented further canvas revisions and navigation-component consolidation. The linked file retains the reviewed four-screen design; the newer full-screen live state and shortened copy are implemented in Compose and recorded in native renders. No upgrade was purchased.

Native adaptations: system bars and display cutouts use Android insets; content scrolls instead of clipping at a fixed artboard height; controls wrap at larger font scales; light mode uses the same semantic palette; essential consent and error feedback remain visible. Transitions use the existing 160/220 ms policy and respect reduced motion. Smoothness under sustained physical monitoring remains a profiling gate.

Ignored evidence: artifacts/figma-redesign/state.json, figma-overview.png, phone-renders and test/build logs. These are design/test fixtures, not measured field analytics.
