# Mastar Architecture

## Design goals

1. **60fps timeline on budget hardware** — every gesture-hot code path avoids
   allocation, DB writes, and file I/O.
2. **Non-destructive everything** — source media is immutable; the project is
   pure metadata in Room.
3. **Offline-first** — all creative assets ship in the APK (target < 60MB).

## Data model (Room)

```
ProjectEntity 1─* TrackEntity 1─* ClipEntity 1─* KeyframeEntity
```

- `ClipEntity.sourceStartMs/sourceEndMs` — the "cut" window into the untouched
  source file (source-media time).
- `ClipEntity.timelineStartMs` — where the clip sits in project time.
- `timelineDurationMs` is derived: `(sourceEnd - sourceStart) / speed`.
- **Split** = one row becomes two rows (`ClipDao.splitClip`), executed in a
  Room `@Transaction`. No media I/O.
- `KeyframeEntity` holds `(property, timeMs, value, easing)` — the diamond
  dots on the timeline.

## Playback path (preview)

```
Room Flow ──> EditorViewModel ──> PreviewEngine (ExoPlayer)
                                   └─ MediaItem + ClippingConfiguration per clip
```

`PreviewEngine.setTimeline()` rebuilds the ExoPlayer playlist from clip
metadata. ExoPlayer performs the seeks/clipping natively, so re-rendering the
timeline after an edit costs microseconds.

## Export path

```
clips ──> EditedMediaItem(Clipping + Effects) ──> EditedMediaItemSequence
      ──> Composition ──> Transformer ──> hardware MediaCodec ──> MP4
```

- Per-clip `SpeedChangeEffect` + `SonicAudioProcessor` keep audio pitch natural
  during speed changes.
- `Presentation.createForWidthAndHeight` letterboxes everything into the
  project canvas (default 1080x1920@30).
- H.264/AAC output: the most widely hardware-accelerated combo in India.

## Keyframe engine

`KeyframeEngine.valueAt()` binary-searches the keyframe segment containing the
playback time, then applies the segment's easing (`CubicBezierEasing`,
Newton-Raphson with bisection fallback — the CSS/AE convention). The resolved
`Transform` feeds the GL matrix wrapper each frame. Pure Kotlin, covered by
JVM unit tests.

## Native engine (`app/src/main/cpp`)

- `transition_renderer.{h,cpp}` — hosts a gl-transitions MIT shader snippet:
  wraps `vec4 transition(vec2 uv)` in a GLES3 fragment shader with
  `getFromColor`/`getToColor` helpers, renders a fullscreen quad blending the
  outgoing/incoming clip textures at `uProgress`.
- `audio_engine.cpp` — Oboe low-latency output stream (exclusive, float,
  stereo). The mixer graph plugs into `onAudioReady` in Phase 3.
- `mastar_engine.cpp` — the JNI surface (`NativeBridge.kt` on the Kotlin side).
  All GL calls must run on the thread owning the EGL context.

Transition GLSL files live in `assets/transitions/` with license headers and
are cached in memory by `TransitionLibrary` (zero-lag first tap).

## Timeline UI

- `TimelineState` holds zoom as a single `pxPerMs` float — pinch-zoom mutates
  one value, keeping recomposition trivial.
- CapCut-style fixed center playhead; content scrolls beneath it.
- Magnetic snapping (`TimelineState.snap`): clip edges + playhead + t=0 are
  magnets within a 12px threshold, converted to ms at the current zoom.
- Drag offsets live in Compose state during the gesture; Room is written only
  on drag end.

## Roadmap state

| Phase | Scope | Status |
|---|---|---|
| 1. Core architecture | Media3 import/stitch/export, NDK bridge, Room model | ✅ scaffolded |
| 2. Timeline UX | Pinch-zoom, drag/snap, split; sub-frame trim polish | 🔨 in progress |
| 3. Creative layers | GL transition playback wiring, LUT filters, Lottie stickers, Oboe mixer, PIP layers | shader host + assets ready |
| 4. Optimization & launch | Proxy editing for 4K, low-end device passes, AdMob export ad | not started |

### Phase 3 wiring notes (next up)

- Preview transitions: render ExoPlayer output onto `SurfaceTexture`-backed GL
  textures; when the playhead crosses a boundary with `transitionId`, draw via
  `TransitionRenderer` for the `transitionDurationMs` window.
- Export transitions: implement as a custom Media3 `GlEffect` wrapping the same
  shader, so preview and export share GLSL.
- LUT filters: `SingleColorLut` (Media3) for export; same LUT PNG sampled in
  our fragment shader for preview.
- Speed curves: piecewise `SpeedChangeEffect` segments from keyframed speed;
  FFmpeg `minterpolate`-style frame blending is a stretch goal.
