# Guided setup improvements, 2026-10-30

The user requested a more convenient native Android flow, including API-key-only entry and automatic model lists. No camera/counting or cloud-image-analysis result is implied by this UI work.

| Friction | Implemented behavior |
| --- | --- |
| Manual HTTPS endpoint and NVIDIA model ID | Official provider presets, local key-prefix suggestions, provider override and a model picker populated from responses. |
| Long page of technical controls | AI, Device and Reports steps, real completion status, and separate Tools for diagnostics/advanced audit/export actions. |
| “Upload” wording while checking a key | Connect & load models explains the key request and that no photos are sent. Image upload consent remains separate. |
| Repeated key entry after a temporary connection failure | Explicit Retry uses an in-memory ByteArray; it is cleared on replacement/provider change or ViewModel destruction. Cancel stops the active request. |
| Manually choosing a model ID | Searchable names/IDs, image support only where supplied, and selection constrained to discovered IDs. No bundled fallback model list. |
| Model list disappears offline | Provider catalogs, selected model, active provider and fetch timestamps persist in encrypted Proto DataStore. Refresh requires a tap. |
| Repeated performance runs and key creation | Valid device/model/runtime results are reused; Prepare phone creates a missing local identity and checks speed. Existing identity is preserved. |
| Several confusing export buttons | Choose a local folder once; identity export and checkpoint scheduling happen together. The folder has a readable name. Detailed exports are under Tools. |
| Keyboard/accessibility friction | System light/dark theme, scrollable steps, IME-aware layout, Paste/Show/Hide, clear busy/cancel/error states and large native buttons. |

Key-prefix detection only suggests the provider; it sends no requests. Connect/Refresh/Verify consume endpoint/action-bound one-shot consent inside the shared monitoring interlock. Redirects/retries are disabled; responses, pagination and model counts are bounded. Previous saved connections/lists survive refresh failure. No key is included in shared SetupUiState, reports or logs; the private password field temporarily holds the user's input and clears on Connect.

Follow-up improvements: Home now leads with actual Start/Stop and a camera overlay. Items can be chosen from trained categories or enrolled via cropped/masked photos. API purpose/usage is reachable from Home and AI settings. Photo upload is optional and requires a reviewed crop plus fresh Confirm & Upload; gallery IO is serialized against monitoring. Raw model confidence/reference scores are explicitly distinguished from calibrated probabilities. A low-texture reference is refused rather than returning arbitrary matching boxes.

The model list is a provider catalog, not a promise of quota or entitlement. NVIDIA's hosted catalog can be read without authentication, so its list cannot unlock setup by itself. Verification uses the chosen returned model and a one-token text request; it may use quota. Unknown image capabilities remain unknown.

Provider sources: [Gemini model listing](https://ai.google.dev/api/models), [OpenRouter current key](https://openrouter.ai/docs/api/api-reference/api-keys/get-current-key), [OpenRouter model catalog](https://openrouter.ai/docs/api/api-reference/models/get-models), [NVIDIA hosted catalog](https://integrate.api.nvidia.com/v1/models), [NVIDIA chat API](https://docs.api.nvidia.com/nim/reference/llm-apis). These are official public service configuration; API keys are never bundled.

New dependency evidence: lifecycle-viewmodel 2.11.0 POM Last-Modified June 17, 2026; Compose ui-test-junit4/ui-test-manifest 1.12.1 (pinned by Compose BOM 2026.09.00) POM Last-Modified September 9, 2026. All are stable and older than 14 days. Locks were refreshed for app/test/lint configurations.

Validation and live-key limits are recorded in STATUS and VERIFICATION. Automated UI tests use explicit fixture callbacks and do not contact providers with fake credentials.
# v0.6 navigation and live view

- Editable Figma redesign: see FIGMA_DESIGN.md.
- Monitor, Items, Usage and Settings stay one tap away outside active monitoring.
- Live camera fills the screen; Stop, Rescan and More float at the bottom. Status and detection labels overlay the camera.
- Explanations and diagnostics move out of the live view; both burst duty and freeze events remain available under More.
- Actual consent, errors and missing prerequisites remain visible. No upload or catalog refresh happens automatically.
