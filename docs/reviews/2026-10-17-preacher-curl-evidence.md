# Preacher curl captures f5f09b2d and 472a62d6

Both supplied exports identify `preacher_curl`; both user reviews record 10
repetitions without per-rep boundaries. All nine entries in each archive passed
indexed integrity checks. Extracted originals and replay outputs remain in
ignored `work/evidence/<capture suffix>/`, outside version control.

| Capture | Reviewed | Recorded watch | Frozen replay | Current replay | Direct ReturnToPose replay |
|---|---:|---:|---:|---:|---:|
| f5f09b2d | 10 | 10 | 10 | 10 | 10 |
| 472a62d6 | 10 | 10 | 10 | 10 | 10 |

The profile already uses ReturnToPoseRepDetector through FLEXION_EXTENSION
routing in ProfiledMotionRepDetector; the direct comparison is therefore not
an alternative engine improvement for these captures. All event timestamps
match between frozen, current, and direct replay in each capture. This differs
from the rope-extension experiments, where pose-based counting is unadopted.

The first capture has 1,785 samples over 35.707 seconds, observed 49.962 Hz,
longest gap 20.289 ms; the second has 1,673 over 33.475 seconds, 49.948 Hz,
longest gap 21.108 ms. Both sensors report HIGH accuracy throughout. Neither
archive reports missing original clocks, unreliable samples, or stale joins.
Raw SHA-256 values:

- f5f09b2d: `e3178e3680611a99ef6e62f8a541f5abc796a705651cc94f8867483bcf2f512a`
- 472a62d6: `10ac1ecd9fd1db04186b41e00f353ff8a10a9852903bd4b9a62ff5ad33249ccb`

The selected original-evidence replay test passed once for each archive, with
no target or camera input. Matching reviewed totals supports these two cases;
no manual video-boundary review or population-accuracy claim is made.
No production code, versions, push, or deployment changed.
