# EdgePulse AI: Ultra-Efficient Local-First Android Monitoring System

**System Architecture, Detailed Design, Tech Stack & Phase-Wise Implementation Blueprint (v5.0 — Final Production Specification)**

## 1. Executive Summary & Core Design Philosophy

**EdgePulse AI** is an industrial-grade, low-power Android edge-vision application engineered for two offline-first workflows:

1. **Primary Product — Stationary Construction Inventory Auditing:** Counting cement bags moved into and out of a storage doorway over continuous 8-hour shifts.

2. **Secondary Product — Longitudinal Garden Spatial Mapping:** Building an aligned 2D virtual map and historical visual timeline of garden plants across manual walk-around sessions.

The architecture enforces seven foundational engineering guarantees:

1. **Zero-Conflict Camera Pipelines, EWMA Background Gate & Lazy Raw-YUV Pre-Roll:**

   * **Stationary Construction Mode** runs inside a foreground `LifecycleService` (`foregroundServiceType="camera"`) using `CameraX` (`640x480`, sensor AE locked to `Range(5, 10)` FPS). **Tier 1 (`LumaMotionGate`) inspects every single sensor frame ($5\text{--}10\text{ FPS}$, $<0.8\text{ ms}$ CPU)** against a slowly adapting **Exponentially Weighted Moving Average (EWMA) background model** so slow hand-trucks and hesitating workers are never missed.

   * **Lazy Raw-YUV Pre-Roll Buffer (`FramePreRollBuffer`):** To avoid expensive YUV$\to$RGB conversion, rotation, and resize on idle frames, `IDLE_WATCH` copies only the raw `YUV_420_888` planes of the last 2 frames ($640 \times 480 \times 1.5 = 460,800\text{ bytes} \approx 460\text{ KB}$ per slot, $\sim 922\text{ KB}$ total in 2 pre-allocated direct buffers, $<0.3\text{ ms}$ bulk memory copy). Color conversion and $320 \times 320$ letterboxing execute **lazily only when a motion burst triggers**.

   * **Accurate Pre-Roll Recovery & Edge-Margin Semantics:** When a small, distant, or low-contrast bag was already inside the frame at $t_{-2}, t_{-1}$ before crossing the $3.5\%$ luma threshold at $t_0$, flushing $(t_{-2}, t_{-1}, t_0)$ recovers those two prior sightings so the track reaches `minHits = 3` (`CONFIRMED`) at $t_0$. Conversely, a bag that physically enters the camera view at $t_0$ is absent in $t_{-2}, t_{-1}$ and reaches `minHits = 3` at $t_{+2}$ ($\sim 0.2\text{--}0.4\text{ s}$ after entry)—which is why the setup UI enforces a **$\ge 20\%$ doorway line frame-edge margin ($\ge 1.0\text{ s}$ walking buffer)** and Phase 4 tests cold-idle walk-ins from the frame edge.

   * **Garden Walk-Around Mode** runs exclusively in the foreground `Activity` **without `CameraX` or the foreground service** on ARCore devices, acquiring CPU `YUV_420_888` frames directly from `arFrame.acquireCameraImage()` so ARCore retains exclusive ownership of the physical camera sensor.

2. **True End-to-End Local Encryption & Auto-Backup Exclusion:**

   * All structured telemetry, spatial coordinates, and audit logs reside in an **AES-256 encrypted SQLCipher database (`Room 2.8.5` + `sqlcipher-android 4.18.0`)**, while event keyframes are encrypted on disk (`Tink StreamingAead` `.webp.enc`).

   * Because hardware-bound Android Keystore keys cannot be restored across devices, `AndroidManifest.xml` enforces `android:allowBackup="false"` and `android:dataExtractionRules="@xml/data_extraction_rules"` (excluding `databases/` and `datastore/`).

3. **Locally & Externally Verifiable ECDSA Audit Chain with Authorized Key Rotation:**

   * Every inventory event and downtime gap is signed using a hardware-backed **Android Keystore `SHA256withECDSA` (`secp256r1` / NIST P-256) private key** chaining monotonic `SystemClock.elapsedRealtime()` and wall-clock timestamps.

   * The **X.509 public key (`SPKI`)**, **Keystore hardware attestation certificate chain**, and **signed checkpoints (`checkpoint_YYYYMMDD_HHMM.sig.json`)** are exported to external SAF storage. If an external auditor supplies a challenge nonce at enrollment, the attestation chain proves both hardware residency and key freshness; if self-initialized offline, it proves hardware-backed Keystore residency (`TEE`/`StrongBox`).

   * **Deterministic Verification & Authorized Device Swap vs. Tamper Alert:** Cryptographic verification runs **100% deterministically on-device (`LocalAuditVerifier`) or via an offline Python script (`verify_audit_chain.py`)**—never delegated to the Cloud VLM. Legitimate phone replacements are supported via a signed **`AuthorizedKeyRotationRecord`** (co-signed by the outgoing device key or a supervisor re-enrollment key), whereas an unannounced key change from *"Clear App Data"* immediately triggers **`PUBKEY_ROTATED_TAMPER_ALERT`**.

4. **Thermal & Dead-Service Alarm Notifications with True Gap-Start Accounting:**

   * If `ThermalMonitor` reaches `THERMAL_STATUS_SEVERE` while the process is alive, it pauses inference and fires an **immediate ($<1\text{ s}$) high-priority audible/vibrating alarm notification (`IMPORTANCE_HIGH`)**.

   * If the OS or OEM task killer terminates `MonitoringForegroundService` while `is_session_active == true`, a periodic 15-minute `WorkManager` job (`WatchdogHeartbeatWorker`) detects the stale heartbeat (`age > 45s`), records a `monitoring_gaps` row with **`gap_start_elapsed_ms = last_heartbeat_elapsed_ms`** (the exact last known alive timestamp rather than detection time), and posts the `IMPORTANCE_HIGH` alarm notification (with a documented **15–30+ minute worst-case detection latency** under Android Doze, avoiding Google Play-restricted exact alarms).

5. **Zero-Video Storage Optimization:**

   * Raw video is never recorded. Only verified event keyframes are software-encoded on `Dispatchers.IO` into lossy `WebP` ($\approx 34\text{ KB}$), encrypted via `StreamingAead`, and pruned in $<2\text{ ms}$ via an indexed SQLite `archive_index` table enforcing a strict $200\text{ MB}$ FIFO circular quota.

6. **Offline-Resilient BYOK Governance & Consent-Gated Cloud Analysis:**

   * The user's Cloud VLM API key is encrypted via **Google Tink (`1.24.0`) + Proto DataStore (`1.3.0` / `datastore-tink`)** backed by the Android Keystore. Validated once online, the unlocked state is cached locally so the app boots and operates **100% offline on zero-signal sites**.

   * Data stays strictly local until the user explicitly taps **Analyze**, reviews keyframes processed by **best-effort automated head/face blurring**, and approves the upload preview.

7. **Commercial-Safe Apache-2.0 Models & Strict `YOLOX-Tiny` ($\le 35\text{ ms}$) Counting Policy:**

   * Stationary Construction Counting **mandates Apache-2.0 `YOLOX-Tiny`** (`5.06M` params, $\approx 5.1\text{ MB}$ INT8) across all supported device tiers to guarantee reliable discrimination between cement bags and hard negatives (sand, lime, fertilizer sacks). Both the Tier C target and the Construction Mode eligibility gate enforce a unified **`YOLOX-Tiny` p95 latency $\le 35\text{ ms}$**; slower devices are blocked from Construction Mode rather than silently falling back to `YOLOX-Nano` (which is used only for single-class `plant` detection in Garden Mode).

## 2. System Architecture

### 2.1 High-Level Mode-Separated Architecture

```
+---------------------------------------------------------------------------------------------------+
|                                      PRESENTATION LAYER (UI)                                      |
|  [Jetpack Compose UI]   [Activity OLED Blackout Overlay (1% Brightness)]   [2D Garden Map Canvas] |
+-------------------------------------------------+-------------------------------------------------+
                                                  |
                  +-------------------------------+-------------------------------+
                  |                                                               |
                  v (MODE A: STATIONARY CONSTRUCTION)                             v (MODE B: GARDEN WALK-AROUND)
+-------------------------------------------------+     +-------------------------------------------+
| MonitoringForegroundService (LifecycleService)  |     | GardenCameraActivity (Foreground Only)    |
| - foregroundServiceType="camera"                |     | - No ForegroundService, No Motion Gate    |
| - ThermalMonitor (<1s SEVERE Alarm) +           |     | - Tier A (ARCore): Session Owns Camera,   |
|   WatchdogHeartbeatWorker (15-30m Doze-Safe     |     |   Frames via arFrame.acquireCameraImage() |
|   Check when is_session_active, Gap=last_hb)    |     | - Tier B (Fallback): Activity CameraX +   |
+------------------------+------------------------+     |   GameRotationVector + StepDetector (±2m) |
                         |                              +---------------------+---------------------+
                         v                                                    |
+-------------------------------------------------+                           |
| CameraX ImageAnalysis (640x480, Range[5,10] FPS)|                           |
+------------------------+------------------------+                           |
                         | (Every Sensor Frame: 5-10 FPS)                     |
                         v                                                    |
+-------------------------------------------------+                           |
| TIER 1: LumaMotionGate + Raw YUV PreRollBuffer  |                           |
| - Compares 32x32 Y-Plane against EWMA Background|                           |
| - Freezes background update during Active Burst |                           |
| - Retains 2 raw YUV_420_888 frames (~922KB RAM, |                           |
|   <0.3ms copy; lazy RGB letterbox ONLY on flush)|                           |
+------------------------+------------------------+                           |
                         |                                                    |
                         v                                                    |
+-------------------------------------------------+                           |
| TIER 0: TemporalCadenceController               |                           |
| - Idle -> Burst transition: Lazily converts &   |                           |
|   flushes (t-2, t-1) + t0 to Tier 2             |                           |
| - Active Burst (3.0s tail): Pass 5-10 FPS       |                           |
| - Idle Watch (No motion > 3s): Throttle 0 FPS   |                           |
+------------------------+------------------------+                           |
                         |                                                    |
                         +------------------------+---------------------------+
                                                  |
                                                  v
+---------------------------------------------------------------------------------------------------+
|      TIER 2: BENCHMARKED LITERT INFERENCE (YOLOX-Tiny <=35ms Mandatory for Cement @ 320x320)      |
|  - First-Launch Benchmarked Winner: Vendor NPU (CompiledModel) vs GPU (FP16) vs XNNPACK (INT8)    |
|  - Upright Rotation Transform + 640x480 -> 320x320 Letterboxing + Inverse Box Un-Letterboxing     |
+------------------------+----------------------------------------------------+---------------------+
                         |                                                    |
                         v (Construction Domain)                              v (Garden Domain)
+-------------------------------------------------+     +-------------------------------------------+
| ObjectTracker (4-State Kalman, Birth>=3, 1.5s,  |     | GardenSessionAligner (Textured Plaque /   |
|   Track-Level Majority Class Vote)              |     |   2D ICP + Alignment Covariance Sigma_al) |
| + CementBagLineCounter (20% Edge Margin, Normal |     | + GardenSpatialMapper (<=1.5m Spawn,      |
|   Dot-Product, Deadband-Born, Hysteresis Band)  |     |   1.5-3.0m Update-Only, 2D Kalman + Q)    |
+------------------------+------------------------+     +---------------------+---------------------+
                         |                                                    |
                         +------------------------+---------------------------+
                                                  |
                                                  v
+---------------------------------------------------------------------------------------------------+
|                          ENCRYPTED LOCAL PERSISTENCE & MANUAL CLOUD LAYER                         |
|  +----------------------------------------------+  +-------------------------------------------+  |
|  | Local Storage & Deterministic Crypto Verifier|  | Tier 3: Manual Cloud Analyzer             |  |
|  | - Room 2.8.5 + SQLCipher 4.18.0 (7 Tables)   |  | - Strictly User-Triggered (No Cron)       |  |
|  | - Tink StreamingAead (.webp.enc, ~34KB)      |  | - LocalAuditVerifier checks ECDSA chain & |  |
|  | - Keystore SHA256withECDSA + Attestation PEM |  |   key continuity BEFORE building prompt   |  |
|  | - Authorized Key Rotation vs Unannounced     |  | - Best-Effort Face Blur + Mandatory       |  |
|  |   Key-Diff Tamper Alert (verify_audit_chain) |  |   Interactive Preview & Redaction Sheet   |  |
+-------------------------------------------------+  +-------------------------------------------+  |
+---------------------------------------------------------------------------------------------------+
```

### 2.2 Execution Lifecycle & Mode Isolation

1. **First-Launch Benchmark Harness & Unified $\le 35\text{ ms}$ Eligibility Gate (`DelegateBenchmarkHarness.kt`):**

   * On first launch, the app benchmarks bundled Apache-2.0 COCO `yolox_tiny_320.tflite` and `yolox_nano_320.tflite` across **(1) LiteRT Vendor NPU (`CompiledModel`), (2) GPU Delegate (`FP16`), and (3) CPU `XNNPACK` (`INT8`, 4 threads)**.

   * **Unified Construction Mode Eligibility Rule ($\le 35\text{ ms}$ p95):** Stationary Construction Mode **always runs `YOLOX-Tiny`**. If `YOLOX-Tiny` p95 latency is $\le 18\text{ ms}$ (Tier A NPU / Tier B GPU), Active Burst runs at up to $10\text{ FPS}$; if `YOLOX-Tiny` p95 latency is $18\text{--}35\text{ ms}$ (Tier C CPU), Active Burst caps at $5\text{ FPS}$ ($200\text{ ms}$ frame interval, leaving $\ge 90\text{ ms}$ headroom even during a 3-frame pre-roll flush); if p95 $> 35\text{ ms}$, Construction Mode displays an unsupported-hardware warning rather than degrading accuracy with `YOLOX-Nano`.

2. **Mode A — Stationary Construction Monitoring (CameraX + EWMA Gate + Lazy Raw-YUV Pre-Roll):**

   * Started while the `Activity` is visible, `MonitoringForegroundService` sets `is_session_active = true` in DataStore and binds `CameraX` `ImageAnalysis` (`640x480`, `YUV_420_888`, `STRATEGY_KEEP_ONLY_LATEST`) with sensor AE FPS set to `Range(5, 10)`.

   * **Tier 1 (`LumaMotionGate` + `FramePreRollBuffer`)** runs on every sensor frame ($5\text{--}10\text{ FPS}$):

     * Compares the current frame's exposure-normalized $32 \times 32$ luma grid against the **EWMA background reference grid** $\tilde{B}$ ($<0.8\text{ ms}$ CPU).

     * While in `IDLE_WATCH`, `FramePreRollBuffer` performs a fast bulk `ByteBuffer` copy of the raw `YUV_420_888` planes ($460,800\text{ bytes}$, $<0.3\text{ ms}$) into its 2-slot circular buffer ($t_{-2}, t_{-1}$) and closes the `ImageProxy` immediately—performing **zero** YUV$\to$RGB conversion or resizing while idle.

   * **Burst Wakeup & Lazy Pre-Roll Flush:**

     * When $\ge 3.5\%$ of cells differ from $\tilde{B}$ at $t_0$, `TemporalCadenceController` transitions `IDLE_WATCH` $\to$ `ACTIVE_BURST` ($3.0\text{ s}$ window), lazily converts and letterboxes $(t_{-2}, t_{-1}, t_0)$ into $320 \times 320$ RGB, and runs Tier 2 (`YOLOX-Tiny` + `ObjectTracker`) across all three frames in chronological order.

     * **Confirmation Timing:** If a small/distant bag was already in view at $t_{-2}, t_{-1}$ before tripping the luma threshold at $t_0$, pre-roll recovers those two detections so the track confirms at $t_0$. If a bag enters the frame fresh at $t_0$, it is not present in $t_{-2}, t_{-1}$ and confirms at $t_{+2}$ ($\sim 0.2\text{--}0.4\text{ s}$ after entry), well before reaching the doorway line placed $\ge 20\%$ ($\ge 1.0\text{ s}$ walk) inside the frame edge.

3. **Mode B — Garden Walk-Around Mapping (Exclusive ARCore / Fallback Activity Pipeline):**

   * Runs strictly in the foreground `Activity` without `MonitoringForegroundService`.

   * **Tier A (ARCore Devices):** `ArSession` owns the camera; CPU frames are acquired via `arFrame.acquireCameraImage()` at $5\text{ FPS}$ and closed deterministically.

   * **Tier B (Non-ARCore Devices — `Coarse Sketch Mode ±2.0m`):** `CameraX` binds to `GardenCameraActivity` alongside `TYPE_GAME_ROTATION_VECTOR` and `TYPE_STEP_DETECTOR`, with path length capped at $10\text{ m}$ between origin re-anchors.

## 3. Detailed System Design

### 3.1 Power, Duty-Cycle Telemetry, Thermal & Dead-Service Alert Engineering

| Subsystem | Baseline Unoptimized | EdgePulse AI v5.0 Design | Measured / Target Impact |
| :--- | :--- | :--- | :--- |
| **Display (Activity)** | $100\%$ brightness ($\sim 500\text{--}800\text{ mW}$) | **Activity OLED Sentinel Overlay:** Full `#000000` black Compose layer in `Activity` with `screenBrightness = 0.01f` + wake-lock. | $\sim 80\%$ display savings on OLED; minimum $1\%$ backlight on LCD |
| **Camera Sensor & ISP** | $1080\text{p}$ @ $30\text{ FPS}$ continuous | **Resolution & AE Clamping:** `640x480` @ `Range(5, 10)` FPS ($6\times$ fewer pixels, $3\text{--}6\times$ lower readout rate than $1080\text{p}30$). | Lower ISP/DMA power (measured via `BatteryManager` in Phase 2) |
| **CPU / NPU Compute & Duty Cycle** | $30\text{ FPS}$ continuous neural net inference + per-frame RGB conversion | **EWMA Tier 1 + Raw YUV Pre-Roll + Tier 0 Burst Gate:** $<1.1\text{ ms}$ total idle CPU (`LumaMotionGate` + raw YUV copy); NPU/GPU + RGB letterboxing run **only** during `ACTIVE_BURST`. Session logs `active_burst_duty_cycle_pct` (including up to $30\text{ s}$ static background freezes). | $\sim 80\%$ NPU reduction at **Typical $20\%$ duty cycle** ($\le 6.0\%/\text{hr}$ OLED battery); $\sim 50\text{--}60\%$ reduction at **Busy $40\text{--}50\%$ duty cycle** ($\le 8.5\%/\text{hr}$ OLED battery) |
| **Thermal Pause Alarm ($<1\text{ s}$)** | Unhandled thermal throttling | **`ThermalMonitor`:** Steps burst down to $5\text{ FPS}$ at `MODERATE`; at `SEVERE`, pauses Tier 2, logs a signed `monitoring_gaps` row, and fires an **immediate ($<1\text{ s}$) `IMPORTANCE_HIGH` audible/vibrating alarm**. | Prevents thermal shutdown in $38\text{--}42^\circ\text{C}$ storerooms |
| **Dead-Service Alarm ($15\text{--}30+\text{ min}$)** | Silent OEM task-killer death | **`WatchdogHeartbeatWorker` (15-min `WorkManager`):** Checks `is_session_active == true` and `last_heartbeat_elapsed_ms` (updated every $10\text{ s}$). If stale ($>45\text{ s}$), logs `gap_start_elapsed_ms = last_heartbeat_elapsed_ms` and posts an **`IMPORTANCE_HIGH` alarm notification** (worst-case **15–30+ min detection latency** under Android Doze; Play-policy compliant without `USE_EXACT_ALARM`). | Guarantees exact gap start timestamp and alerts site staff without Play Store permission rejection |

### 3.2 Frame Rotation & Aspect-Preserving Letterbox Math

Both `CameraX` (`ImageProxy`) and `ARCore` (`acquireCameraImage()`) provide `640x480` ($4:3$) frames with sensor orientation $\theta_{\text{rot}} \in \{0^\circ, 90^\circ, 180^\circ, 270^\circ\}$.

1. **Upright Orientation & Letterboxing to $320 \times 320$ (Executed Only on Active Burst Frames):**

   * After rotating into upright dimensions $(W_{\text{rot}}, H_{\text{rot}})$, the isotropic scale factor and padding offsets are:
     $$s = \min\left(\frac{320}{W_{\text{rot}}}, \frac{320}{H_{\text{rot}}}\right), \quad p_x = \frac{320 - W_{\text{rot}} \cdot s}{2}, \quad p_y = \frac{320 - H_{\text{rot}} \cdot s}{2}$$

2. **Inverse Mapping to Upright Normalized Coordinates $[u, v] \in [0, 1]^2$:**

   * Every detected bounding box $[x_1, y_1, x_2, y_2] \in [0, 320]$ is un-letterboxed before tracking or raycasting:
     $$u = \text{clamp}\left(\frac{x - p_x}{W_{\text{rot}} \cdot s},\; 0.0,\; 1.0\right), \quad v = \text{clamp}\left(\frac{y - p_y}{H_{\text{rot}} \cdot s},\; 0.0,\; 1.0\right)$$

### 3.3 Tier 1 & Tier 0 Design: EWMA Background Motion Gate & Lazy Raw-YUV Pre-Roll

```
[Sensor Frame t (5-10 FPS)]
        │
        ▼
[Tier 1: LumaMotionGate (<0.8ms CPU, 32x32 Y-Plane vs EWMA Background Reference B)]
        ├── Foreground Change >= 3.5% vs B:
        │     1. Freeze Background B (alpha = 0 up to 30s max so slow/paused movers stay active)
        │     2. If transitioning IDLE_WATCH -> ACTIVE_BURST:
        │          Lazily convert & letterbox Raw YUV Pre-Roll (t-2, t-1) + Current Frame (t0)
        │          and flush all 3 to Tier 2 sequentially
        │     3. Reset Burst Timer (burstUntilElapsedMs = now + 3000ms)
        │
        └── Foreground Change < 3.5% vs B:
              1. Slowly adapt Background B: B_i = 0.98 * B_i + 0.02 * Y_norm_i
              2. If (now <= burstUntilElapsedMs): Convert & Pass Frame t to Tier 2 (3.0s Burst Tail)
              3. Else (IDLE_WATCH): Bulk-copy raw YUV_420_888 planes (~460KB, <0.3ms) into
                 2-Slot Raw Pre-Roll Ring Buffer and close ImageProxy (Zero RGB/Resize, 0 FPS Tier 2)
```

1. **EWMA Background Reference Model (`LumaMotionGate.kt`):**

   * Samples $N = 32 \times 32 = 1024$ luma points using `y * rowStride + x * pixelStride` (`and 0xFF`), subtracts the frame mean $\bar{Y}^{(t)}$ to obtain exposure-normalized $\tilde{Y}_i^{(t)} = Y_i^{(t)} - \bar{Y}^{(t)}$, and compares against the **EWMA empty-doorway background model** $\tilde{B}_i^{(t)}$:
     $$\Delta_i^{(t)} = \left|\tilde{Y}_i^{(t)} - \tilde{B}_i^{(t-1)}\right|, \quad r_{\text{fg}}^{(t)} = \frac{1}{N}\sum_{i=1}^N \mathbb{I}\left(\Delta_i^{(t)} > 18\right)$$

   * **Gated Background Adaptation & Duty-Cycle Tracking:**

     * When $r_{\text{fg}}^{(t)} < 0.035$: update $\tilde{B}_i^{(t)} = 0.98\tilde{B}_i^{(t-1)} + 0.02\tilde{Y}_i^{(t)}$ to track gradual outdoor/shadow drift.

     * When $r_{\text{fg}}^{(t)} \ge 0.035$: **freeze foreground cells** ($\tilde{B}_i^{(t)} = \tilde{B}_i^{(t-1)}$ up to a $30\text{ s}$ static timeout) so slow hand-trucks or workers pausing in the doorway remain above the $3.5\%$ threshold.

     * `TemporalCadenceController` accumulates total time spent in `ACTIVE_BURST` vs. total session elapsed time and writes `active_burst_duty_cycle_pct` to `monitoring_sessions`.

2. **Lazy 2-Frame Raw `YUV_420_888` Pre-Roll Ring Buffer (`FramePreRollBuffer.kt`):**

   * Allocates two fixed `RawYuvSlot` objects at startup, each holding a compacted `ByteArray(460_800)` ($640 \times 480$ Y plane $= 307,200\text{ B}$ + $320 \times 240$ U/V planes $= 153,600\text{ B}$; total $\sim 922\text{ KB}$ across both slots), plus `rotationDegrees` and `monotonicElapsedMs`.

   * During `IDLE_WATCH`, copying the three planes into the older slot takes $<0.3\text{ ms}$ on the CPU with zero heap allocations and zero color conversion.

   * Only on the single transition frame from `IDLE_WATCH` $\to$ `ACTIVE_BURST` does the pipeline convert $(t_{-2}, t_{-1}, t_0)$ to upright $320 \times 320$ RGB and run Tier 2 inference.

### 3.4 Primary Use Case Design: Construction Cement Bag Counter (`ObjectTracker` + `CementBagLineCounter`)

#### 1. Constant-Velocity Kalman Tracker with Track-Level Majority Class Vote (`ObjectTracker.kt`)

* **State Vector:** Each track $k$ maintains $\mathbf{s}_k = [c_x, c_y, v_x, v_y]^T$ updated with the exact monotonic frame interval $\Delta t = (t_n - t_{n-1}) \in [0.10\text{ s}, 0.25\text{ s}]$.

* **Track Lifecycle & Confirmation Timing:**

  * **Track Birth (`minHits = 3`):**

    * *Case A (Late Motion-Gate Trigger):* If a small or low-contrast bag was already present in $t_{-2}$ and $t_{-1}$ before the luma gate fired at $t_0$, flushing the 2 raw pre-roll frames yields 3 consecutive hits at $t_0$, confirming the track immediately at $t_0$.

    * *Case B (Fresh Edge Entry at $t_0$):* If a worker walks in from outside the camera FOV at $t_0$, $t_{-2}$ and $t_{-1}$ contain an empty doorway; the bag gets its first hit at $t_0$ and reaches `minHits = 3` (`CONFIRMED`) at $t_{+2}$ ($200\text{ ms}$ later at $10\text{ FPS}$, or $400\text{ ms}$ later at $5\text{ FPS}$). Because the doorway line is placed $\ge 20\%$ inside the frame edge ($\ge 1.0\text{ s}$ walking distance), the track is confirmed well before reaching the hysteresis band.

  * **Time-Based Occlusion Survival (`maxAgeMs = 1500L`):** Unmatched tracks predict forward via $\hat{C}_k(t) = C_k + \vec{v}_k \Delta t$ for up to $1.5\text{ seconds}$ so brief occlusion never drops the track ID or side state.

* **Track-Level Majority Class Voting (Prevents Single vs. Double-Stack Flicker):**

  * Each track accumulates confidence-weighted class votes across all matched detections:
    $$W_{\text{single}}^{(k)} = \sum_{m \in \text{hits}(k)} \text{conf}_m \cdot \mathbb{I}(\text{cls}_m = \text{single}), \quad W_{\text{double}}^{(k)} = \sum_{m \in \text{hits}(k)} \text{conf}_m \cdot \mathbb{I}(\text{cls}_m = \text{double})$$

  * When a crossing event triggers, $\text{bag\_weight} = 2$ if $W_{\text{double}}^{(k)} > W_{\text{single}}^{(k)}$ (when double-stack mode is enabled), else $1$.

#### 2. Doorway Placement Margin & Outward Normal Vector ($\hat{n}_{\text{OUT}}$)

* **Mandatory $20\%$ Frame Edge Margin ($\ge 1.0\text{ s}$ Walking Buffer):**

  * `CounterSetupScreen` enforces that the user-drawn doorway line $A(x_1, y_1) \to B(x_2, y_2)$ lies inside $[0.20, 0.80]^2$ so any bag entering from the frame edge travels for $\ge 1.0\text{ s}$ ($\ge 5\text{--}10\text{ frames}$) before reaching the doorway line.

* **Outward Normal Vector:** Given $\vec{L} = B - A = (\Delta x, \Delta y)$, $L = \|\vec{L}\|_2$, and user-selected **OUT Arrow** orientation $\sigma_{\text{user}} \in \{-1, +1\}$:
  $$\hat{n}_{\text{OUT}} = \frac{\sigma_{\text{user}}}{L}(-\Delta y,\; \Delta x)$$

#### 3. Signed Hysteresis Band & Deadband-Born Origin Inference (`CementBagLineCounter.kt`)

* For each `CONFIRMED` track ($\ge 3$ hits) within doorway span $u_\parallel \in [-0.10, 1.10]$, signed perpendicular distance is $s_\perp(C^{(t)}) = (C^{(t)} - A) \cdot \hat{n}_{\text{OUT}}$ against deadband $[-\delta, +\delta]$ ($\delta = 0.05$):

  * **Case 1 — Born Outside Deadband ($\left|s_\perp(C_{\text{birth}})\right| > \delta$):**

    * Initial state is `CONFIRMED_INSIDE` ($s_\perp < -\delta$) or `CONFIRMED_OUTSIDE` ($s_\perp > +\delta$).

    * Transition `CONFIRMED_INSIDE` $\rightarrow$ $s_\perp > +\delta$ (`CONFIRMED_OUTSIDE`) increments **`OUT_COUNT`** by the track's majority-voted `bag_weight`.

    * Transition `CONFIRMED_OUTSIDE` $\rightarrow$ $s_\perp < -\delta$ (`CONFIRMED_INSIDE`) increments **`IN_COUNT`**.

  * **Case 2 — Born Inside Deadband ($\left|s_\perp(C_{\text{birth}})\right| \le \delta$):**

    * Initialized as `BORN_IN_BAND` with anchor $C_{\text{birth}}$. Upon exiting the deadband at $C_{\text{exit}}$ as a `CONFIRMED` track:

      * If $s_\perp(C_{\text{exit}}) > +\delta$ **AND** $(C_{\text{exit}} - C_{\text{birth}}) \cdot \hat{n}_{\text{OUT}} \ge 0.04$ **AND** $\vec{v}_k \cdot \hat{n}_{\text{OUT}} > 0$: origin inferred as `CONFIRMED_INSIDE`, transitions to `CONFIRMED_OUTSIDE`, and increments **`OUT_COUNT`**.

      * If $s_\perp(C_{\text{exit}}) < -\delta$ **AND** $(C_{\text{exit}} - C_{\text{birth}}) \cdot \hat{n}_{\text{OUT}} \le -0.04$ **AND** $\vec{v}_k \cdot \hat{n}_{\text{OUT}} < 0$: origin inferred as `CONFIRMED_OUTSIDE`, transitions to `CONFIRMED_INSIDE`, and increments **`IN_COUNT`**.

#### 4. Simultaneous Dual-Mount Field Acceptance Protocol ($N = 500$ Physical Crossings/Site)

* **Simultaneous Rig Setup (Unambiguous Sample Scope):**

  * To evaluate both hardware tiers without tripling physical labor, **one Tier A/B phone (`10 FPS` burst) and one Tier C phone (`5 FPS` burst) are mounted side-by-side on a dual-phone bracket** alongside an **independent tripod-mounted ground-truth video camera** and human tally observer.

  * Each unseen hold-out site executes **$N = 500$ physical bag crossings ($400\text{ OUT}$, $100\text{ IN}$ per site; $1,000$ total physical crossings across 2 hold-out sites)**, including:

    1. Cold-idle walk-ins starting from the frame edge after $\ge 30\text{ s}$ of zero doorway motion,

    2. Slow/hesitating hand-trucks pausing in the doorway (plus a busy $40\text{--}50\%$ duty-cycle period),

    3. Dim ($\le 30\text{ lux}$, $5\text{ FPS}$) storeroom carries, and

    4. Hard-negative carries (sandbags, lime sacks, and toolboxes).

* **Event-Level Matching & Explicit Confidence Interval Bounds:**

  * A predicted crossing event matches a ground-truth event only if $|\Delta t| \le 1.0\text{ s}$ with the correct direction (`OUT` vs `IN`) and bag weight. Both phones must independently satisfy:

    * **`OUT` Crossings ($N_{\text{OUT}} = 400$/site, $800$ pooled):**
      $$\text{Recall}_{\text{OUT}} \ge 96.5\% \;\;(\text{95\% Wilson CI lower bound } \ge 94.2\%\text{/site},\; \ge 95.0\%\text{ pooled})$$
      $$\text{Precision}_{\text{OUT}} \ge 96.5\% \;\;(\text{95\% Wilson CI lower bound } \ge 94.2\%\text{/site},\; \ge 95.0\%\text{ pooled})$$

    * **`IN` Crossings ($N_{\text{IN}} = 100$/site, $200$ pooled — wider CI explicitly bounded):**
      $$\text{Recall}_{\text{IN}} \ge 95.0\% \;\;(\text{95\% Wilson CI lower bound } \ge 88.8\%\text{/site},\; \ge 91.0\%\text{ pooled})$$
      $$\text{Precision}_{\text{IN}} \ge 95.0\% \;\;(\text{95\% Wilson CI lower bound } \ge 88.8\%\text{/site},\; \ge 91.0\%\text{ pooled})$$

### 3.5 Secondary Use Case Design: Garden Spatial Mapper (`GardenSessionAligner` + `GardenSpatialMapper`)

#### 1. Cross-Session Coordinate Alignment & Alignment Covariance $\mathbf{\Sigma}_{\text{align}}$ (`GardenSessionAligner.kt`)

To align new walk-around sessions to the canonical Garden Map frame $\mathbf{p}_{\text{map}} = \mathbf{R}(\theta_{\text{align}})\mathbf{p}_{\text{sess}} + \mathbf{t}_{\text{align}}$:

* **Method 1 — High-Contrast Textured Garden Origin Plaque (`AugmentedImageDatabase`, Primary):**

  * Ships a printable weatherproof **botanical graphic origin plaque** verified with `arcoreimg eval-img` score $\ge 85/100$. Phase 5 empirically measures its 20-trial pose repeatability $\sigma_{\text{marker}}$ at $0.5\text{--}1.0\text{ m}$ (target $\sigma_{\text{marker}} \le 0.05\text{ m}$).

* **Method 2 — Markerless 2D Rigid Umeyama/ICP Point-Set Registration (Fallback):**

  * When $M \ge 3$ existing plants are observed at close range ($D \le 1.5\text{ m}$), RANSAC + 2D Kabsch/Umeyama SVD solves for $(\theta_{\text{align}}, t_x, t_z)$ and computes the empirical registration residual variance:
    $$\sigma_{\text{align}}^2 = \max\left(0.05^2,\; \frac{1}{M}\sum_{j=1}^M \left\|\mathbf{R}(\theta_{\text{align}})\mathbf{p}_{\text{sess},j} + \mathbf{t}_{\text{align}} - \hat{\mathbf{x}}_{\text{map},j}\right\|_2^2\right)$$

* **Propagating Alignment Uncertainty into Measurement Noise:**

  * Every observation in a re-aligned session adds $\mathbf{\Sigma}_{\text{align}} = \text{diag}(\sigma_{\text{align}}^2, \sigma_{\text{align}}^2)$ into its total measurement covariance $\mathbf{R}_k^{\text{total}} = \mathbf{R}_k^{\text{ray}} + \mathbf{\Sigma}_{\text{align}}$.

#### 2. Dual-Range Rule ($D \le 1.5\text{ m}$ Spawn / $1.5\text{--}3.0\text{ m}$ Unambiguous Update)

* **Strict Dual-Range Policy (`GardenSpatialMapper.kt`):**

  1. **Close-Range Zone ($D \le 1.5\text{ m}$, Primary Mapping Zone):**

     * Here $\sigma_D = 0.05 \cdot D^2 \le 0.11\text{ m}$ and $\sigma_\perp = D\sigma_\theta \le 0.045\text{ m}$. Observations with $D \le 1.5\text{ m}$ may **spawn new `plant_nodes`**, associate via the 2-DoF $\chi^2_{0.95}$ Mahalanobis gate ($d_M^2 \le 5.991$ with hard Euclidean cap $\|\mathbf{z}_k - \hat{\mathbf{x}}_i\|_2 \le 0.30\text{ m}$), and increment `close_sightings_count`. Fusing $\ge 2$ close sightings ($D \le 1.5\text{ m}$) yields posterior position error $\le \pm 30\text{ cm}$.

  2. **Mid-Range Zone ($1.5\text{ m} < D \le 3.0\text{ m}$, Update-Only Zone):**

     * Sightings in $(1.5\text{ m}, 3.0\text{ m}]$ **never create new plant nodes** and **never merge nodes**. They update an existing node $i$ *only* if $\|\mathbf{z}_k - \hat{\mathbf{x}}_i\|_2 \le 0.25\text{ m}$ **and** no other plant node lies within $0.60\text{ m}$ of $\hat{\mathbf{x}}_i$.

  3. **Out-of-Range ($D > 3.0\text{ m}$):** Discarded immediately.

#### 3. Tier B Non-ARCore Fallback (`Coarse Sketch Mode ±2.0m`)

* Uses `Sensor.TYPE_GAME_ROTATION_VECTOR` + `Sensor.TYPE_STEP_DETECTOR` ($L_{\text{step}} = 0.72\text{ m}$) + pinhole projection ($H_{\text{cam}} = 1.40\text{ m}$, $D_{\max} = 2.5\text{ m}$) with accumulated step-drift camera covariance $\mathbf{\Sigma}_{\text{cam}}(D_{\text{walked}}) = \text{diag}\left((0.08 \cdot D_{\text{walked}})^2,\; (0.08 \cdot D_{\text{walked}})^2\right)$ and a mandatory $10.0\text{ m}$ walk-path re-anchor cap, labeled **`Coarse Sketch Mode (±2.0m)`**.

#### 4. 2D Spatial Kalman Filter Equations

* **Session Prediction Step:** $\mathbf{P}_i^{-} = \mathbf{P}_i + \mathbf{Q}_{\text{session}}$ where $\mathbf{Q}_{\text{session}} = \text{diag}(0.04^2, 0.04^2)\text{ m}^2$.

* **Total Measurement Covariance:** $\mathbf{R}_k^{\text{total}} = \mathbf{R}(\psi_{\text{ray}})\text{diag}(\sigma_D^2, (D\sigma_\theta)^2)\mathbf{R}(\psi_{\text{ray}})^T + \mathbf{\Sigma}_{\text{align}} + \mathbf{\Sigma}_{\text{cam}}$.

* **Innovation & Update:**
  $$\mathbf{S}_{i,k} = \mathbf{P}_i^{-} + \mathbf{R}_k^{\text{total}}, \quad \mathbf{K}_k = \mathbf{P}_i^{-} \mathbf{S}_{i,k}^{-1}$$
  $$\hat{\mathbf{x}}_i \leftarrow \hat{\mathbf{x}}_i + \mathbf{K}_k(\mathbf{z}_k - \hat{\mathbf{x}}_i), \quad \mathbf{P}_i \leftarrow (\mathbf{I} - \mathbf{K}_k)\mathbf{P}_i^{-}$$

### 3.6 Encrypted Local Database Schema (`Room 2.8.5` + `SQLCipher 4.18.0` — All 7 Tables)

```
+--------------------------------------+       +--------------------------------------+
|             garden_maps              |       |             plant_nodes              |
+--------------------------------------+       +--------------------------------------+
| PK map_id (TEXT)                     |1     *| PK node_id (TEXT)                    |
|    name (TEXT)                       +-------+ FK map_id (TEXT)                     |
|    has_arcore_planes (INTEGER)       |       |    detected_class (TEXT: 'plant')    |
|    accuracy_tier (TEXT:'30CM'|'2M')  |       |    species_label (TEXT, NULLABLE)    |
|    origin_plaque_id (TEXT, NULLABLE) |       |    world_x_meters (REAL)             |
|    created_at_wall (INTEGER)         |       |    world_z_meters (REAL)             |
+--------------------------------------+       |    cov_xx (REAL), cov_xz (REAL)      |
                                               |    cov_zz (REAL)                     |
                                               |    close_sightings_count (INTEGER)   |
                                               |    latest_enc_webp_path (TEXT)       |
                                               |    cloud_health_markdown (TEXT, NULL)|
                                               |    updated_at_wall (INTEGER)         |
                                               +------------------+-------------------+
                                                                  |1
                                                                  |*
+--------------------------------------+       +------------------+-------------------+
|         monitoring_sessions          |       |          plant_observations          |
+--------------------------------------+       +--------------------------------------+
| PK session_id (TEXT)                 |       | PK obs_id (INTEGER AUTOINCREMENT)    |
|    mode (TEXT: 'GARDEN' | 'CEMENT')  |       | FK node_id (TEXT)                    |
|    is_session_active (INTEGER: 0|1)  |       | FK session_id (TEXT)                 |
|    last_heartbeat_elapsed_ms (INT)   |       |    enc_webp_path (TEXT)              |
|    active_burst_duty_cycle_pct (REAL)|       |    webp_purged (INTEGER DEFAULT 0)   |
|    gate_ax, gate_ay, gate_bx, gate_by|       |    obs_x_meters (REAL)               |
|    out_normal_x, out_normal_y (REAL) |       |    obs_z_meters (REAL)               |
|    align_method (TEXT, NULLABLE)     |       |    distance_d_meters (REAL)          |
|    align_rot_rad (REAL DEFAULT 0)    |       |    captured_wall_ms (INTEGER)        |
|    align_tx_m (REAL DEFAULT 0)       |       +--------------------------------------+
|    align_tz_m (REAL DEFAULT 0)       |
|    align_sigma_m (REAL DEFAULT 0.05) |
|    total_out_count (INTEGER)         |
|    total_in_count (INTEGER)          |
|    pubkey_spki_b64 (TEXT)            |
|    attestation_cert_pem (TEXT)       |
|    attestation_nonce_mode (TEXT)     |
|    key_continuity_status (TEXT)      |
|    last_checkpoint_sig (TEXT, NULL)  |
|    started_elapsed_ms (INTEGER)      |
|    started_wall_ms (INTEGER)         |
|    ended_wall_ms (INTEGER, NULLABLE) |
|    cloud_audit_report (TEXT, NULL)   |
+---------+------------------+---------+
          |1                 |1
          |*                 |*
+---------+------------------+---------+       +--------------------------------------+
|           inventory_events           |       |           monitoring_gaps            |
+--------------------------------------+       +--------------------------------------+
| PK event_id (INTEGER AUTOINCREMENT)  |       | PK gap_id (INTEGER AUTOINCREMENT)    |
| FK session_id (TEXT)                 |       | FK session_id (TEXT)                 |
|    track_id (INTEGER)                |       |    gap_start_elapsed_ms (INTEGER)    |
|    direction (TEXT: 'OUT' | 'IN')    |       |    gap_end_elapsed_ms (INTEGER)      |
|    bag_weight (INTEGER: 1 | 2)       |       |    gap_start_wall_ms (INTEGER)       |
|    running_out_total (INTEGER)       |       |    reason (TEXT: 'THERMAL_SEVERE' |  |
|    enc_webp_path (TEXT)              |       |            'OS_KILL_DETECTED' |      |
|    webp_purged (INTEGER DEFAULT 0)   |       |            'CAMERA_ERROR' | 'PAUSE') |
|    webp_sha256 (TEXT)                |       |    prev_chain_hash (TEXT)            |
|    monotonic_elapsed_ms (INTEGER)    |       |    chain_hash (TEXT)                 |
|    wall_clock_ms (INTEGER)           |       |    ecdsa_sig_b64 (TEXT)              |
|    prev_chain_hash (TEXT)            |       +--------------------------------------+
|    chain_hash (TEXT)                 |
|    ecdsa_sig_b64 (TEXT)              |       +--------------------------------------+
+--------------------------------------+       |            archive_index             |
                                               +--------------------------------------+
                                               | PK enc_webp_path (TEXT)              |
                                               |    byte_size (INTEGER)               |
                                               |    created_elapsed_ms (INTEGER)      |
                                               |    source_table (TEXT: 'EVENT'|'OBS')|
                                               |    source_row_id (INTEGER)           |
                                               |    is_purged (INTEGER DEFAULT 0)     |
                                               +--------------------------------------+
```

### 3.7 Asymmetric ECDSA Signature Chain, Authorized Key Rotation & Local Verification (`EventSignatureChain.kt` & `LocalAuditVerifier.kt`)

1. **Keystore `SHA256withECDSA` (`secp256r1`) & Attestation Challenge Scope:**

   * At key generation, the user/supervisor may optionally enter or scan via QR an **auditor-supplied challenge nonce** passed to `setAttestationChallenge(nonce)` (`attestation_nonce_mode = "VERIFIER_SUPPLIED"`), proving both hardware residency and key freshness to that external auditor. If initialized offline without an external challenge (`attestation_nonce_mode = "SELF_GENERATED"`), the exported X.509 attestation chain (`attestation_cert_pem`) proves **hardware-backed Keystore residency (`TEE` / `StrongBox`)** rather than freshness against replay.

   * Every `inventory_events` and `monitoring_gaps` row $k$ is chained and signed:
     $$H_k = \text{SHA256}\left(H_{k-1} \;\|\; \text{session\_id} \;\|\; \text{direction} \;\|\; \text{bag\_weight} \;\|\; \text{running\_out\_total} \;\|\; \text{elapsed\_ms} \;\|\; \text{wall\_ms} \;\|\; \text{webp\_sha256}\right)$$
     $$\text{Sig}_k = \text{Base64}\left(\text{ECDSA-Sign}_{\text{KeystorePriv}}(H_k)\right)$$

2. **Authorized Device Replacement vs. Unannounced *"Clear App Data"* Tamper Alert (`DailyCheckpointExporter.kt`):**

   * Every 4 hours and at shift end, the app writes a signed `checkpoint_YYYYMMDD_HHMM.sig.json` to the external SAF folder.

   * **Legitimate Phone Replacement (`AuthorizedKeyRotationRecord`):** To prevent false tamper alerts when a phone is legitimately replaced or migrated, the app supports writing a signed `key_rotation_YYYYMMDD_HHMM.sig.json` to the SAF folder via two flows:

     1. *Planned Device Migration (Old Phone Accessible):* The outgoing device signs `{old_pubkey_spki_b64, new_pubkey_spki_b64, last_chain_hash, rotation_wall_ms}` with the outgoing Keystore private key.

     2. *Emergency Supervisor Re-Enrollment (Broken/Dead Phone):* A site supervisor signs the transition record using an offline supervisor Ed25519/ECDSA key (via QR code challenge-response) authorizing `new_pubkey_spki_b64` and recording an explicit hardware-swap gap.

   * **Verification Policy:** Both `LocalAuditVerifier` and `verify_audit_chain.py` inspect the SAF directory:

     * Same public key across all checkpoints $\rightarrow$ `key_continuity_status = "CONTINUOUS_VERIFIED"`.

     * Public key changed with a valid signed `key_rotation_*.sig.json` $\rightarrow$ `key_continuity_status = "AUTHORIZED_KEY_ROTATION"`.

     * Public key changed *without* a valid rotation record (e.g., someone ran *"Clear App Data"* to wipe counts) $\rightarrow$ **`key_continuity_status = "PUBKEY_ROTATED_TAMPER_ALERT"`**.

3. **Deterministic Local Verification vs. Cloud VLM Role:**

   * **`LocalAuditVerifier.kt` verifies every SHA-256 link, `SHA256withECDSA` signature, monotonic clock delta, and public-key continuity locally on the CPU before any cloud call**. Only the deterministic verification summary (`ecdsa_chain_valid`, `key_continuity_status`, verified counts, and downtime gap durations) plus user-approved keyframes are sent to the Cloud VLM.

### 3.8 Worker Privacy: Best-Effort Face Redaction & Mandatory Preview

* `FacePrivacyBlur` runs an on-device `BlazeFace` detector plus a geometric upper-torso/head-box fallback over person regions near detected cement bags, applying a Gaussian blur ($\sigma = 15$).

* Because automated detectors can miss small, turned, dust-obscured, or helmeted faces, the UI explicitly labels this step **"Best-Effort Automated Face Redaction"** and enforces a **mandatory interactive preview (`CloudConsentAndReportSheet`)** where the user inspects every candidate image, can tap-to-blur any missed face region manually or deselect the image, and must explicitly tap **"Confirm & Upload"** before any network call occurs.

## 4. Complete Technology Stack (Google Maven & Maven Central Catalog)

The version catalog below (`gradle/libs.versions.toml`) pins releases checked against Google Maven (`maven.google.com`) and Maven Central (`repo1.maven.org`). Keep `./gradlew dependencyUpdates` as a mandatory Day-1 bootstrap step to verify latest patch artifacts:

| Category | Artifact Coordinate | Pinned Version | Release Notes & Pinning Rationale |
| :--- | :--- | :--- | :--- |
| **Language** | `org.jetbrains.kotlin:kotlin-stdlib` | `2.4.21` | Kotlin 2.4 stable release; verify patch via `dependencyUpdates` at bootstrap. |
| **Coroutines** | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | `1.11.0` | Structured concurrency for background WebP encoding and IO. |
| **UI Framework** | `androidx.compose:compose-bom` | `2026.09.00` | Maps Compose UI `1.12.1` and Material3 `1.4.0`. |
| **Stationary Camera** | `androidx.camera:camera-core` / `camera2` / `lifecycle` | `1.6.1` | Verified `1.6.1` stable (fixes compile error in `1.6.0`). Used strictly in Stationary Construction Mode. |
| **On-Device Edge AI** | `com.google.ai.edge.litert:litert` / `litert-gpu` / `litert-support` | `2.3.0` | Vendor NPU `CompiledModel`, FP16 GPU, and INT8 XNNPACK runtimes. |
| **Object Detectors** | Custom **YOLOX-Tiny** (`5.06M`) & **YOLOX-Nano** (`0.91M`) | Apache-2.0 License | `YOLOX-Tiny` ($\approx 5.1\text{ MB}$ INT8, $\le 35\text{ ms}$) **mandatory** for cement bag counting; `YOLOX-Nano` ($\approx 1.0\text{ MB}$ INT8) for Garden plant mode on Tier C CPU. |
| **Garden Spatial VIO** | `com.google.ar:core` | `1.57.0` | Exclusive camera owner in Garden Mode (`acquireCameraImage()`), `Plane` raycasting, and `AugmentedImage` origin plaque. |
| **Encrypted Database** | `androidx.room:room-runtime` + `net.zetetic:sqlcipher-android` | Room `2.8.5` + SQLCipher `4.18.0` | Full AES-256 database encryption at rest across all 7 tables. |
| **Cryptography** | `com.google.crypto.tink:tink-android` | `1.24.0` | Keystore-wrapped `Aead` and `StreamingAead` (`.webp.enc` files). |
| **Key/Config Store** | `androidx.datastore:datastore` + `datastore-tink` | `1.3.0` | Typed DataStore with native `datastore-tink` `AeadSerializer` (`allowBackup="false"`). |
| **Service & Work** | `androidx.lifecycle:lifecycle-service` + `androidx.work:work-runtime-ktx` | Lifecycle `2.11.0` + Work `2.12.0` | `foregroundServiceType="camera"` and Doze-safe 15-min `WatchdogHeartbeatWorker`. |
| **Cloud HTTP Client** | `com.squareup.okhttp3:okhttp` | `5.3.0` | Invoked strictly on manual user-approved Cloud Analysis requests. |
| **Dependency Injection** | `com.google.dagger:hilt-android` | `2.60.1` | Compile-time DI isolating offline vision from the manual cloud client. |

## 5. Project Directory Structure

```
com.edgepulse.monitor/
├── di/
│   ├── AppModule.kt                       # Tink Aead, Proto DataStore 1.3.0, SQLCipher Room DB
│   └── VisionModule.kt                    # Benchmarked LiteRT runtime, Trackers, Controllers
├── security/
│   ├── TinkKeyManager.kt                  # AndroidKeystore AES256_GCM keyset + StreamingAead
│   ├── ApiKeyRepository.kt                # Offline-cached BYOK state + zero-fill memory hygiene
│   ├── EventSignatureChain.kt             # Keystore EC (secp256r1) SHA256withECDSA + Attestation PEM
│   ├── AuthorizedKeyRotationManager.kt    # Co-signed device migration & supervisor QR key rotation
│   ├── LocalAuditVerifier.kt              # Deterministic on-device ECDSA chain & key-rotation verifier
│   ├── DailyCheckpointExporter.kt         # Exports signed JSON checkpoints to SAF external folder
│   └── FacePrivacyBlur.kt                 # Best-effort face/head blur + manual brush redaction
├── data/
│   ├── local/
│   │   ├── EdgePulseDatabase.kt           # Room 2.8.5 + SQLCipher 4.18.0 (All 7 Tables)
│   │   ├── MonitoringDao.kt               # Queries for maps, nodes, obs, sessions, events, gaps, index
│   │   └── WebpCircularArchiver.kt        # Software WEBP_LOSSY + Tink StreamingAead + 200MB index
│   └── remote/
│       └── CloudAnalysisService.kt        # Sends locally-verified summary + approved keyframes to VLM
├── service/
│   ├── MonitoringForegroundService.kt     # Stationary Mode ONLY: LifecycleService (type="camera")
│   ├── ThermalMonitor.kt                  # Steps down FPS on MODERATE; <1s SEVERE pause + loud alarm
│   └── WatchdogHeartbeatWorker.kt         # 15-30m Doze-safe check: gap_start=last_hb + loud alarm
├── vision/
│   ├── camera/
│   │   ├── StationaryCameraXController.kt # CameraX 1.6.1 640x480 Range[5,10] + lazy upright letterbox
│   │   ├── GardenArCameraBridge.kt        # Acquires/closes YUV_420_888 from arFrame.acquireCameraImage()
│   │   ├── FramePreRollBuffer.kt          # 2-slot raw YUV_420_888 buffer (~922KB, <0.3ms copy)
│   │   └── TemporalCadenceController.kt   # Tier 0: Lazily converts & flushes pre-roll; tracks duty %
│   ├── gate/
│   │   └── LumaMotionGate.kt              # Tier 1: Every frame (<0.8ms) vs EWMA background reference
│   └── ml/
│       ├── DelegateBenchmarkHarness.kt    # Phase 1 benchmark; enforces YOLOX-Tiny <=35ms p95 gate
│       └── YoloxDetector.kt               # Tier 2: Apache-2.0 YOLOX-Tiny/Nano + un-letterboxing
├── domain/
│   ├── construction/
│   │   ├── ObjectTracker.kt               # 4-state Kalman (real dt, minHits=3, 1.5s, Majority Vote)
│   │   └── CementBagLineCounter.kt        # 20% edge margin, Normal dot-product, Deadband-Born, Hysteresis
│   └── garden/
│       ├── GardenSessionAligner.kt        # Textured Origin Plaque + 2D ICP + Alignment Cov Sigma_al
│       ├── GardenSpatialMapper.kt         # <=1.5m Spawn, 1.5-3.0m Update-Only, 2D Kalman + Q noise
│       └── FallbackPinholeProjector.kt    # GameRotationVector + StepCov + <=10m re-anchor (±2.0m)
└── ui/
    ├── auth/
    │   └── ApiKeyGateScreen.kt            # One-time online key validation + offline unlock cache
    ├── construction/
    │   ├── CounterSetupScreen.kt          # Enforces >=20% edge margin for A->B + OUT arrow + mode
    │   └── SentinelBlackoutOverlay.kt     # Activity OLED 1% brightness overlay + touch lock
    ├── garden/
    │   ├── GardenCameraScreen.kt          # ARCore HUD + <=1.5m spawn zone / 1.5-3.0m update indicator
    │   └── VirtualGardenMapCanvas.kt      # 2D map with covariance ellipses + aligned timeline carousel
    └── audit/
        └── CloudConsentAndReportSheet.kt  # Local ECDSA badge, mandatory preview, manual blur & upload
```

## 6. 18-Week Phase-Wise Implementation Roadmap

### Phase 1: Cryptography, Encrypted DB, Benchmark Harness & Field Data Start (Weeks 1–3)

**Objective:** Run `./gradlew dependencyUpdates` to verify Maven pins, build the Tink/SQLCipher/ECDSA security foundation, authorized key-rotation flow, and hardware benchmark harness while initiating multi-site field data collection (running Weeks 1–6).

* **Tasks:**

  1. **Launch Multi-Site Data Collection & Annotation Pipeline (Runs Weeks 1–6):**

     * Record doorway videos across $\ge 4$ construction sites (bright sun, shade, and dim $\le 30\text{ lux}$ storerooms; single carries, 2-bag stacked carries, slow hand-trucks; plus **hard negatives**: sandbags, lime/fertilizer sacks, trash bags, toolboxes) and garden walk-arounds. Reserve 2 unseen sites strictly for hold-out testing.

  2. **Cryptography, Auto-Backup Exclusion & Key Management (`TinkKeyManager`, `ApiKeyRepository`):**

     * Configure `android:allowBackup="false"` and `data_extraction_rules.xml`.

     * Implement **Google Tink (`1.24.0`) + DataStore (`1.3.0` / `datastore-tink`)** with one-time online API key validation and offline boot caching.

  3. **Encrypted Database (All 7 Tables), ECDSA Attestation, Authorized Rotation & Verifiers (`EdgePulseDatabase`, `EventSignatureChain`, `AuthorizedKeyRotationManager`, `LocalAuditVerifier`):**

     * Integrate Room `2.8.5` + SQLCipher `4.18.0` with all **7 tables** (including `is_session_active`, `last_heartbeat_elapsed_ms`, and `active_burst_duty_cycle_pct`).

     * Implement Keystore `SHA256withECDSA` (`secp256r1`) signing with optional verifier-supplied attestation nonce, `DailyCheckpointExporter`, `AuthorizedKeyRotationManager` (co-signed migration + supervisor QR re-enrollment), `LocalAuditVerifier`, and `verify_audit_chain.py`.

  4. **First-Launch `DelegateBenchmarkHarness` (Unified $\le 35\text{ ms}$ Eligibility Gate):**

     * Benchmark bundled COCO `yolox_tiny_320.tflite` and `yolox_nano_320.tflite` on LiteRT `2.3.0` Vendor NPU, FP16 GPU, and INT8 XNNPACK; enforce the unified **$\le 35\text{ ms}$ p95 `YOLOX-Tiny` eligibility gate** for Construction Mode.

* **Deliverables & Acceptance Criteria:**

  * `LocalAuditVerifier` and `verify_audit_chain.py` distinguish `CONTINUOUS_VERIFIED`, `AUTHORIZED_KEY_ROTATION` (legitimate phone swap), and `PUBKEY_ROTATED_TAMPER_ALERT` (unannounced *"Clear App Data"*).

### Phase 2: Stationary CameraX Service, EWMA Motion Gate, Lazy Raw-YUV Pre-Roll & Alarms (Weeks 4–6)

**Objective:** Complete the stationary construction camera service with EWMA background subtraction, lazy raw-YUV pre-roll, duty-cycle telemetry, dead-service/thermal alarms, and dataset completion.

* **Tasks:**

  1. **`MonitoringForegroundService` & `StationaryCameraXController`:**

     * Implement `LifecycleService` (`foregroundServiceType="camera"`) binding `CameraX 1.6.1` at `640x480` with `Camera2Interop` AE FPS `Range(5, 10)`, updating `is_session_active` and `last_heartbeat_elapsed_ms` every $10\text{ s}$ (and clearing `is_session_active = false` on normal user stop).

  2. **EWMA Tier 1 (`LumaMotionGate`) + Lazy Raw-YUV `FramePreRollBuffer` $\rightarrow$ Tier 0 (`TemporalCadenceController`):**

     * Run stride-aware, exposure-normalized `LumaMotionGate` on **every sensor frame ($5\text{--}10\text{ FPS}$, $<0.8\text{ ms}$)** against the EWMA background model $\tilde{B}$.

     * Copy raw `YUV_420_888` planes ($\sim 460\text{ KB}$/slot, $<0.3\text{ ms}$) into the 2-slot `FramePreRollBuffer` while idle; perform YUV$\to$RGB conversion and $320 \times 320$ letterboxing **only** when flushing $(t_{-2}, t_{-1}, t_0)$ on burst wakeup and during `ACTIVE_BURST`.

  3. **`ThermalMonitor` ($<1\text{ s}$ Alarm) & Doze-Safe `WatchdogHeartbeatWorker` ($15\text{--}30+\text{ min}$ Alarm):**

     * Step burst down to $5\text{ FPS}$ at `THERMAL_STATUS_MODERATE`; fire an immediate ($<1\text{ s}$) `IMPORTANCE_HIGH` alarm and log `THERMAL_SEVERE` on pause.

     * Configure the 15-minute `WatchdogHeartbeatWorker` to run only when `is_session_active == true`; if `now_elapsed - last_heartbeat_elapsed_ms > 45_000L`, record `monitoring_gaps` with **`gap_start_elapsed_ms = last_heartbeat_elapsed_ms`** and trigger the `IMPORTANCE_HIGH` alarm notification.

  4. **Dataset Completion & Annotation QA (End of Week 6):**

     * Finalize $8,000+$ annotated construction instances and $6,000+$ `plant` instances across training and unseen hold-out splits.

* **Deliverables & Acceptance Criteria:**

  * Idle frame handling takes $<1.1\text{ ms}$ total CPU (`LumaMotionGate` + raw YUV copy) with zero idle RGB conversions.

  * Measured power draw on a $5,000\text{ mAh}$ OLED device with `SentinelBlackoutOverlay` is $\le 6.0\%/\text{hr}$ at a typical $20\%$ burst duty cycle and $\le 8.5\%/\text{hr}$ at a busy $40\text{--}50\%$ burst duty cycle.

  * Simulated `adb shell am kill` while `is_session_active == true` logs `gap_start_elapsed_ms` within $10\text{ s}$ of actual kill time (`last_heartbeat_elapsed_ms`), fires the `IMPORTANCE_HIGH` alarm within the 15–30 minute `WorkManager` window, and never false-alarms when stopped normally by the user.

### Phase 3: Apache-2.0 YOLOX-Tiny & YOLOX-Nano Training & Tier-2 Integration (Weeks 7–9)

**Objective:** Train, quantize, and integrate the custom `YOLOX-Tiny` (mandatory for construction) and `YOLOX-Nano` (garden fallback) detectors.

* **Tasks:**

  1. **Model Training & Hard-Negative Optimization:**

     * Train Apache-2.0 **`YOLOX-Tiny`** (`5.06M` params) and **`YOLOX-Nano`** (`0.91M` params) at $320 \times 320$.

     * Evaluate hard-negative discrimination (cement bags vs. sand/lime/fertilizer sacks) on the 2 unseen hold-out construction sites.

  2. **`YoloxDetector` & Letterbox Coordinate Pipeline:**

     * Implement upright rotation handling (`rotationDegrees`) and $640 \times 480 \rightarrow 320 \times 320$ letterboxing with exact inverse bounding-box mapping.

* **Deliverables & Acceptance Criteria:**

  * `YOLOX-Tiny` p95 inference latency meets unified per-tier targets: **Tier A (NPU/Flagship)** $\le 12\text{ ms}$, **Tier B (Mid-range GPU/XNNPACK)** $\le 18\text{ ms}$, **Tier C (Entry CPU @ $5\text{ FPS}$ burst)** $\le 35\text{ ms}$ (matching the $\le 35\text{ ms}$ eligibility gate).

  * Hard-negative false-positive rate $< 2.0\%$ on unseen test sites.

### Phase 4: Primary Product — Construction Cement Bag Counter, Dual-Mount Field Validation & Buffer (Weeks 10–13)

**Objective:** Build the primary construction doorway counter (Weeks 10–11), execute the simultaneous dual-mount field validation ($N=500$ physical crossings/site across 2 unseen sites) against an external reference camera (Week 12), and reserve 1 full week (Week 13) for edge-case tuning and re-test verification.

* **Tasks:**

  1. **`ObjectTracker` & `CementBagLineCounter` Implementation (Weeks 10–11):**

     * Implement 4-state $[c_x, c_y, v_x, v_y]^T$ Kalman tracking (`minHits = 3`, `maxAgeMs = 1500L`, majority class voting) and `CementBagLineCounter` ($\ge 20\%$ doorway frame-edge margin, $\hat{n}_{\text{OUT}}$ dot-product, Signed Hysteresis Band + Deadband-Born Origin Inference).

  2. **Simultaneous Dual-Mount 2-Site Field Validation ($N = 500$ Physical Crossings/Site, Week 12):**

     * Mount a **Tier A/B phone (`10 FPS` burst)** and a **Tier C phone (`5 FPS` burst)** side-by-side on the same doorway alongside an independent tripod-mounted reference video camera.

     * Execute **$N = 500$ physical crossings per unseen site ($400\text{ OUT} + 100\text{ IN}$ per site; $1,000$ total physical crossings across 2 sites)** covering cold-idle walk-ins from the frame edge, slow/pausing hand-trucks, busy $40\text{--}50\%$ duty-cycle periods, dim ($\le 30\text{ lux}$) storerooms, and hard-negative carries.

  3. **Field Remediation & Re-Test Buffer (Week 13):**

     * Analyze false positives/negatives against the reference camera footage, tune tracker/hysteresis thresholds if needed, and re-run validation on any failing condition.

* **Deliverables & Acceptance Criteria:**

  * Both the Tier A/B device and the Tier C device independently achieve:

    * $\text{Recall}_{\text{OUT}} \ge 96.5\%$ and $\text{Precision}_{\text{OUT}} \ge 96.5\%$ (95% Wilson CI lower bound $\ge 94.2\%$/site, $\ge 95.0\%$ pooled across $N_{\text{OUT}} = 800$),

    * $\text{Recall}_{\text{IN}} \ge 95.0\%$ and $\text{Precision}_{\text{IN}} \ge 95.0\%$ (95% Wilson CI lower bound $\ge 88.8\%$/site, $\ge 91.0\%$ pooled across $N_{\text{IN}} = 200$).

### Phase 5: Secondary Product — Garden ARCore Mapper, Session Aligner & Fallback (Weeks 14–16)

**Objective:** Deliver the conflict-free ARCore garden mapper (`acquireCameraImage()`), cross-session coordinate aligner with alignment covariance $\mathbf{\Sigma}_{\text{align}}$, dual-range ($1.5\text{ m} / 3.0\text{ m}$) Kalman filter, and $\pm 2.0\text{ m}$ coarse sketch fallback.

* **Tasks:**

  1. **`GardenArCameraBridge` & `GardenSessionAligner` Repeatability Benchmark:**

     * Acquire CPU `YUV_420_888` frames directly from `arFrame.acquireCameraImage()`.

     * Ship the high-contrast textured botanical origin plaque (`AugmentedImageDatabase` score $\ge 85/100$), empirically benchmark its 20-trial pose repeatability $\sigma_{\text{marker}}$ at $0.5\text{--}1.0\text{ m}$ (target $\le 5\text{ cm}$), implement 2D Umeyama/ICP fallback alignment, and propagate $\mathbf{\Sigma}_{\text{align}}$ into measurement noise.

  2. **`GardenSpatialMapper` (Dual-Range $\le 1.5\text{ m}$ Spawn / $1.5\text{--}3.0\text{ m}$ Update):**

     * Enforce the strict Dual-Range rule: only sightings at $D \le 1.5\text{ m}$ spawn nodes or increment `close_sightings_count`; sightings in $(1.5\text{ m}, 3.0\text{ m}]$ update only unambiguous isolated nodes.

  3. **`FallbackPinholeProjector` (`Coarse Sketch Mode ±2.0m`) & `VirtualGardenMapCanvas`:**

     * Implement `TYPE_GAME_ROTATION_VECTOR` + `TYPE_STEP_DETECTOR` + accumulated step-drift covariance $\mathbf{\Sigma}_{\text{cam}}(D_{\text{walked}})$ with a mandatory $10\text{ m}$ re-anchor cap, labeled **`Coarse Sketch Mode (±2.0m)`**.

* **Deliverables & Acceptance Criteria:**

  * Zero camera resource conflicts on ARCore devices; adjacent plants spaced $25\text{ cm}$ apart are never erroneously merged by mid-range sightings; fused close sightings ($D \le 1.5\text{ m}$) achieve $\pm 30\text{ cm}$ relative accuracy.

### Phase 6: Privacy Redaction UI, Manual Cloud Audit & High-Temp Stress Testing (Weeks 17–18)

**Objective:** Complete best-effort face redaction with mandatory interactive preview, locally verified Cloud VLM reporting, and 8-hour $38\text{--}42^\circ\text{C}$ thermal and busy-duty-cycle stress validation.

* **Tasks:**

  1. **`FacePrivacyBlur` & Mandatory `CloudConsentAndReportSheet`:**

     * Integrate best-effort head/face Gaussian blurring plus an interactive touch-to-blur brush and image deselection UI prior to any cloud upload.

  2. **`CloudAnalysisService` (Consuming `LocalAuditVerifier` Output):**

     * Run `LocalAuditVerifier` on-device to verify ECDSA signatures and key continuity, then transmit the deterministic verification summary + user-approved keyframes to the VLM API to generate `species_label`, `cloud_health_markdown`, and `cloud_audit_report`.

  3. **Hot-Site ($38\text{--}42^\circ\text{C}$) & Busy Duty-Cycle ($40\text{--}50\%$) 8-Hour Stress Testing:**

     * Run continuous 8-hour shifts in $38\text{--}42^\circ\text{C}$ ambient conditions across OLED and LCD phones under both typical ($20\%$) and busy ($40\text{--}50\%$) doorway duty cycles, verifying `ThermalMonitor` FPS step-down, $<1\text{ s}$ thermal alarm notifications, 15–30 min `WorkManager` dead-service alarms with exact `last_heartbeat_elapsed_ms` gap start times, and battery targets.

* **Deliverables & Acceptance Criteria:**

  * Zero network packets sent during monitoring; mandatory preview blocks unapproved uploads; 8-hour hot-site run completes with intact ECDSA chains and $\le 6.0\%/\text{hr}$ ($20\%$ duty cycle) / $\le 8.5\%/\text{hr}$ ($40\text{--}50\%$ duty cycle) battery drain on reference $5,000\text{ mAh}$ OLED hardware.