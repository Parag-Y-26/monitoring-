# Drishti functionality and pipeline test run

Started 2026-10-30 from commit 6664a00. This work tests the current app and fixes reproduced failures; it does not convert missing features or human gates into passed results. Initial expanded run: 50 JVM, 25 Android/API36 and 14 Python tests passed. The composed camera test processed frames, rescanned, released the camera and observed unchanged UID byte counters. The pinned COCO fixture produced five airplane/truck boxes. These are emulator/integration results.

RED checkpoint: native render review reproduced a caption covered by the status pill at 1.3x text; a new caption-safe-area regression is written before its fix. The aggregate harness also failed closed on an incorrect JaCoCo report path, despite passing test suites; it is not yet reported green.

| Workflow | Evidence required | Current run |
| --- | --- | --- |
| Code/build | Fresh Kotlin/Python results, coverage, APK/test APK and lint | Baseline build passes; fresh tests in progress |
| Setup/model discovery | Explicit-tap UI tests, parser/pagination/error cases, encrypted offline catalog | Pending current Android run; live provider access remains separate |
| Items/photo | Offline enrollment, category selection, saved references, redaction/consent binding, blocked changes while active | Pending current Android run |
| Camera/detection | Real YUV model invocation, positive COCO fixture, tracking and label projection | Pending current Android run and composed pipeline tests |
| Live controls | Full-screen bounds, portrait/compact/landscape, Stop/Rescan/More, paused state | Latest revision has not yet passed its Android checks |
| Storage/audit | Migration, authenticated stills, FIFO/tamper, chain recreation/rotation and independent Python verifier | Pending current rerun |
| Network/privacy | No cloud work before fresh matching consent; active and stale-active session rejection; actual request outcomes | Existing boundary coverage; composed Android checks to be expanded |
| Service/watchdog | Start/stop, heartbeat active flag, thermal gaps and user-stop suppression | Unit policy exists; actual camera/service/alarm delivery requires integration/physical checks |

The USB phone is currently absent. API 36 managed-emulator setup is being added so Android UI tests can run without the known API 37 Espresso compatibility problem. Emulator results will be named explicitly and will not establish physical speed, battery, field accuracy, real provider authorization or OEM scheduling behavior. No production credentials or private photos are used.

Line-crossing accounting/signed crossing integration and full audited cloud reports remain unfinished product features in STATUS; observation counts are not inventory totals. Live photo description previously returned an error; the exact provider message/HTTP status has not been supplied. A parser test or fixture response cannot establish that live request as passed.
