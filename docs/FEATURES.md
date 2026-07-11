# Mastar Feature Matrix (v0.3.0)

Target: full CapCut parity **minus Pro features and AI features**.
✅ shipped · 🔶 partial · ⏳ staged (architecture ready, next milestones) · ✖️ excluded by design

## 1. Project Management
| Feature | Status |
|---|---|
| Create multiple projects | ✅ |
| Auto save (every edit persists to Room instantly) | ✅ |
| Duplicate project | ✅ |
| Recent projects (sorted by last modified, with thumbnails) | ✅ |
| Draft recovery (projects are DB rows; nothing is lost on crash) | ✅ |
| Import media / multi-select video import | ✅ |
| Project templates, media bin | ⏳ |
| Cloud projects | ✖️ Pro |

## 2. Timeline Editing
| Feature | Status |
|---|---|
| Split / Delete / Duplicate (copy-paste equivalent) | ✅ |
| Trim by edge-drag handles | ✅ |
| Multi-track (video / audio / text / sticker lanes) | ✅ |
| Drag clips with magnetic snapping (playhead + clip edges) | ✅ |
| Zoom timeline (pinch), filmstrip thumbnails | ✅ |
| Frame stepping (±1 frame buttons) | ✅ |
| Snap to playhead | ✅ |
| Ripple on freeze-frame insert | ✅ |
| Ripple delete, lock/hide/mute track, clip grouping | ⏳ |

## 3. Video Speed
| Feature | Status |
|---|---|
| Speed 0.25x–4x with pitch-corrected audio (Sonic) | ✅ |
| Freeze frame (frame grab → still inserted with ripple) | ✅ |
| Curve speed, reverse | ⏳ (reverse needs re-encode pipeline) |

## 4. Crop & Canvas
| Feature | Status |
|---|---|
| Aspect ratios 9:16, 16:9, 1:1, 4:5, 3:4 | ✅ |
| Rotate (90° steps + arbitrary via DB), Flip H/V, Scale | ✅ |
| Position offset, crop rectangle, background blur/color | ⏳ (GL compositor milestone) |

## 5. Keyframes
Interpolation engine (linear + cubic bezier) is built and unit-tested; DB
stores diamonds per property. Timeline UI + per-frame GL application: ⏳.

## 6. Transitions
4 MIT gl-transitions shaders bundled + C++ GL host compiled + per-clip
selection & duration persist. Cross-clip rendering in preview/export: ⏳
(SurfaceTexture pipeline — the next engine milestone).

## 7. Filters
| Feature | Status |
|---|---|
| 12 filters across categories (Cinema, Retro, Warm, Cool, Food, Nature, Portrait, B&W, Mood, Vivid) | ✅ |
| Intensity slider | ✅ |
| Identical in preview and export (WYSIWYG GPU chain) | ✅ |

## 8. Manual Color Adjustment
Brightness, Contrast, Saturation, Hue, Temperature, Tint: ✅ (per clip,
preview + export). Highlights/Shadows/Vibrance/Sharpen/Vignette/Grain/HSL
per-channel/Curves: ⏳ (need custom GL shaders — host exists).

## 9–10. Effects & Animations
⏳ — the C++ shader host runs arbitrary GLSL; effect/animation packs are the
next asset drop after the transition pipeline lands.

## 11. Stickers
Bundled Lottie animated stickers (offline): ✅. Emoji/GIF/custom import: ⏳.

## 12. Text
| Feature | Status |
|---|---|
| Add text, colors (8 swatches), size, bold, background pill, 4 font families | ✅ |
| Rendered in preview and burned into export (timed overlays) | ✅ |
| Outline/shadow/glow, letter/line spacing, presets, bubbles, text animation | ⏳ |

## 13. Audio
| Feature | Status |
|---|---|
| Import music, position on timeline, mixed into export at exact offsets | ✅ |
| Extract audio from video | ✅ |
| Volume per clip (0–200%) | ✅ |
| Fade in / fade out (custom PCM ramp processor) | ✅ |
| Audio speed (via clip speed + Sonic) | ✅ |
| Voice effects: Deep, Chipmunk, Robot, Echo (pitch + delay DSP) | ✅ |
| Voice recording, noise reduction, EQ, beat markers, stereo balance | ⏳ |

## 15–24. Compositing (Blend/Chroma/Mask/PIP/Motion/Frames)
Opacity per clip: ✅ (preview + export). The rest — blend modes, chroma key,
masks, PIP video, motion blur, frames/borders — all depend on the multi-layer
GL compositor: ⏳ (single biggest next milestone; the OpenGL/NDK foundation
for it is already in the app).

## 25–26. Image & Per-Clip Adjustments
Photo import as timeline stills: ✅ (plays in preview, encodes in export).
Per-clip speed/volume/opacity/rotation/mirror: ✅. Reverse/crop: ⏳.

## 27. Export
| Feature | Status |
|---|---|
| Resolution: 480p / 720p / 1080p / 2K | ✅ |
| Quality (bitrate): Low / Medium / High | ✅ |
| H.264/AAC hardware encode | ✅ |
| No watermark, ever | ✅ (not a Pro upsell — just none) |
| FPS selection | ⏳ (output follows source fps) |

## 28. Miscellaneous
Undo/redo (50 levels): ✅ · Snapping: ✅ · Pinch-zoom timeline: ✅ ·
Playhead controls + frame stepping: ✅ · Full-screen preview, guidelines,
safe area: ⏳.

## 29. Asset Libraries
All assets ship inside the APK (zero-network editing): transitions, filters,
stickers, fonts (system). Music/SFX library: ⏳ (CC0 pack curation).

## 30. Mobile-Specific
Gesture editing, multi-touch timeline scaling, drag-and-drop clips,
quick duplicate: ✅. Favorites, clip grouping: ⏳.

---

### Explicitly excluded (by request)
- **Pro features**: cloud projects, watermark toggles/upsells
- **AI features**: auto-captions, AI cutout, script-to-video, translators
