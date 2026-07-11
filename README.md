# Mastar 🎬

A 100% native, ultra-lightweight Android video editor built for Indian creators —
CapCut-grade timeline fluency with **zero VPN, zero network, zero lag**, tuned for
both budget and flagship Indian smartphones.

## The Engine & The Shell

```
📱 THE SHELL  ──>  Kotlin + Jetpack Compose   (responsive UI, gestures, timeline)
      │
⚙️ THE ENGINE ──>  Media3 + OpenGL ES / C++   (raw hardware speed for video/audio/GFX)
```

| Component | Choice | Why |
|---|---|---|
| UI framework | Jetpack Compose | Complex pinch/drag gestures with zero lag, no XML |
| App language | Kotlin + Coroutines | Background thumbnail loading, structured concurrency |
| Video engine | Google Media3 (ExoPlayer + Transformer) | Native cutting, concatenation, hardware encoding |
| Graphics & transitions | OpenGL ES 3.0 via NDK/C++ | GPU-driven effects, real-time playback |
| Audio core | Google Oboe (C++) | Low-latency mixing, perfect A/V sync |
| Persistence | Room (SQLite) | Instant, offline project timeline saves |
| Stickers | Lottie | Tiny JSON vector animations |
| Thumbnails | Coil (+ video decoder) | Async, cached, coroutine-friendly |

## The core trick: non-destructive editing

Cutting a video never touches the file. A "cut" is two timestamps in the Room
database (`ClipEntity.sourceStartMs` / `sourceEndMs`); Media3 clips playback to
that window at render time. Splits, trims, and moves are instant — even for 4K
media on a ₹12,000 phone.

## Project layout

```
app/src/main/java/com/mastar/editor/
├── data/db/        Room entities + DAOs (the timeline IS the database)
├── data/repo/      ProjectRepository — the single write-path for edits
├── engine/
│   ├── playback/   PreviewEngine   — ExoPlayer timeline preview
│   ├── export/     ExportEngine    — Media3 Transformer H.264/AAC export
│   ├── keyframe/   KeyframeEngine  — linear + cubic-bezier interpolation
│   └── gl/         NativeBridge    — JNI into the C++ GL/audio engine
├── ui/
│   ├── home/       Project list
│   ├── editor/     Editing workspace (preview + toolbar + timeline)
│   ├── timeline/   Pro timeline: pinch-zoom, drag, magnetic snapping
│   └── theme/      Dark-only AMOLED-friendly theme
app/src/main/cpp/   NDK engine: GL transition renderer + Oboe audio stream
app/src/main/assets/transitions/   MIT-licensed gl-transitions shaders (offline)
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full engineering map
and the phase-by-phase roadmap.

## 📲 Install on your phone

**[⬇️ Download mastar-v0.5.0.apk](https://github.com/akshaytyagi482/mastar/raw/main/dist/mastar-v0.5.0.apk)** (~25 MB)

1. Open the link above on your Android phone (or scan the repo from GitHub mobile → `dist/` → tap the APK → Download).
2. When prompted, allow your browser to **install unknown apps** (Settings → Install unknown apps).
3. Open **Mastar**, tap **+** to create a project, tap **+** in the toolbar to add videos, and start editing.

> v0.5.0: fixes the black preview (the preview engine violated three
> CompositionPlayer constraints — now regression-tested on every build),
> exports now save into your **gallery (Movies/Mastar)**, timeline thumbnails
> load ~10x faster (sync-frame seeks + caching), and PIP preview uses the
> multi-input GL graph. See [docs/FEATURES.md](docs/FEATURES.md).

## Building

```bash
# Requires: JDK 17+, Android SDK (platform 35), NDK + CMake for native builds
./gradlew :app:assembleDebug

# JVM unit tests (keyframe/interpolation engine)
./gradlew :app:testDebugUnitTest
```

## Competitive pillars

1. **No-Heat Engine** — lightweight GL shaders + local processing; no thermal
   throttling on mid-range devices.
2. **Zero-Network Editing** — transitions, fonts, and effects ship inside the
   APK. Edit on trains, buses, anywhere.
3. **Hinglish UX** — search "gol", "swipe", "dheere" and get the right preset.
   Asset tags are mapped to Indian internet culture.

## Asset licensing

All bundled creative assets are legally redistributable:
- **Transitions**: [gl-transitions](https://gl-transitions.com) — MIT
- **Stickers**: LottieFiles marked *Free for Commercial Use*
- **Audio**: CC0 sources (Freesound, Pixabay Audio)
- **Filters**: original `.CUBE` LUTs authored in DaVinci Resolve
