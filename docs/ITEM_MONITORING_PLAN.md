# User-selected item monitoring

Active request, 2026-10-30: live boxes/labels/counts, explain and record API use, and choose an item from a photo rather than restricting the app to cement.

The linked [Laya](https://github.com/NandhaKishorM/laya) consumes text questions/state. It is not a camera detector. Use bundled Apache-2.0 YOLOX for its trained COCO categories, a local reference-image matcher for visually similar custom objects, and temporal tracking. Label reference similarity as a score, not a calibrated probability. One reference does not establish arbitrary-object or field accuracy.

Implementation order:

1. Test rotation/letterbox/inverse, YOLOX raw-head decoding/NMS, tracker and reference-match math in core before implementation.
2. Reuse preallocated YUV/BGR/tensor/detection buffers, invoke the cached winning delegate only during bursts and flush raw pre-roll chronologically. Publish real overlay boxes and visible confirmed counts. Initial and occasional rescan bursts must find already-stationary objects too.
3. Choose all supported objects, one trained class, or a saved reference target. Reference matching searches image regions independently of COCO proposals so custom items are not silently limited to COCO. Disclose scale/viewpoint/occlusion limits and expose sensitivity.
4. Item setup: take/select a photo while monitoring is stopped, crop the single target, preview/manual redact, optionally Confirm & Upload for visual characteristics, edit name, save encrypted reference and profile. No API request while monitoring. Explicit descriptions do not train YOLOX.
5. Explain API roles and add encrypted per-request purpose/provider/model/status/image/token ledger, including catalog/verification calls. Unknown usage/cost stays unknown. Do not fabricate historical usage or prices.
6. Run build/lint/core/Python and real Android tests; install/launch and verify real labels on the connected phone. Keep physical accuracy/battery/training gates open.

Retain previous audit history and keys. Camera permissions and foreground lifecycle remain mandatory. No garden feature, video writer, automatic upload, embedded credentials or Laya dependency is added.
