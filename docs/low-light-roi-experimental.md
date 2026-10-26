# Low-light ROI — experimental implementation

This branch adds `LowLightPreprocessor` to the shared Java/OpenCV core. It is not wired into the Android/Windows UI yet, and no accuracy improvement has been validated.

## Proposed integration
1. Crop the user-confirmed ROI from the original image (do not preprocess the full photo).
2. Call `assess(roi)`; expose the illumination metrics and preserve the original.
3. Offer ORIGINAL, GAMMA_CLAHE, DENOISE_CLAHE and RETINEX as explicit choices.
4. For automatic selection, run the SAME existing detector and identical parameters on each candidate. Reject candidates with clipping, implausible overlaps, inconsistent radius distributions or severe contour artifacts. Never optimize solely for count or Canny edge density.
5. Keep measurements/ellipse fitting and crack crops on the ORIGINAL ROI; use corrected images only to propose object locations. Record chosen mode and parameters in JSON/PDF.
6. Benchmark on held-out hand-annotated dark and bright images, using precision, recall, F1, count error and diameter bias; do not claim performance from visual inspection.
7. Add Android and Windows controls, regression tests and CI builds before release.

## Memory contract
`apply` returns a new Mat owned by the caller; release it after use. The source ROI is not mutated. This module runs offline.

## Known constraints
Retinex normalization may amplify noise and suppress absolute intensity. CLAHE can create false contours. Dark saturated pixels contain no recoverable information. No automatic selector is provided until ground-truth-based criteria are established.
