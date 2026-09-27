# Rest UI and round-ring acceptance notes

## Phone contract

Use the existing workout detail area as the only rest control surface:

```text
현재 세트 … · 완료 …
[thin yellow progress bar]
휴식 시간
45초   −   +   pause   stop
```

The countdown updates the existing seconds `TextView` in place (`45초 → 44초 → …`). Keep the configured rest duration editable through the existing `−/+` buttons. Do not add a second bottom card, a second countdown label, or a second control row. The progress bar may be visible only during REST and should reserve minimal height so the established layout does not jump.

## Watch ring geometry

A ring drawn with `minOf(view.width, view.height)` and centered using the entire activity height is unsafe when an emulator exposes a tall canvas. The ring can drift downward and be clipped on a real circular face.

Acceptance rules:

- square round viewport: center the ring in that viewport;
- tall emulator canvas: use the top `width × width` square as the display region;
- inset the stroke from the edge so the thin yellow stroke is not clipped;
- start the arc at `-90°` (12 o'clock) and advance clockwise;
- verify the ring is visible only during REST and disappears at the deadline;
- verify the watch displays `RESTING` with the same absolute `restEndAtMs` as the phone before accepting a real-watch upload.

## Verification evidence

A passing build is not enough. Capture both screens during REST, compare the phone and watch deadlines/countdowns, and record focused activities plus logcat fatal/ANR output. START/SET synchronization without REST synchronization is a partial pass and must block upload.
