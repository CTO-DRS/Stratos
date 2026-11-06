# Changelog

All notable changes to Stratos are documented here. Format: Keep a Changelog 1.1; versioning: SemVer.

## [1.0.0] — 2026-11-06

### Added
- **Dual serving engine**: stdlib-only Go engine (`net/http`, `os.Root`, gomobile-ready) + pure-Kotlin NanoHTTPD fallback with one shared `EngineApi` contract. The app runs out of the box; the Go engine is a drop-in performance upgrade.
- **JSON command bus** (`ping/start/stop/status/sites.set/metrics.reset/zip.extract/log.tail`) over JNI — no management HTTP surface.
- **Secure ZIP import**: zip-slip, symlinks, zip bombs, encrypted/duplicate entries, drive-letter/NUL names, per-entry CRC32; atomic extraction; root auto-detection (index.html / single top folder / public/).
- **Multi-site hosting**: default site at `/`, secondary at `/s/<id>/`, SPA fallback, ETag/Last-Modified/304, gzip, single byte-range, branded 404/405/welcome pages.
- **AI site builder**: offline template engine (trilingual, RTL-aware, injection-escaped) + OpenAI-compatible API client (Z.ai GLM / OpenAI / OpenRouter presets), key sealed in the Keystore vault.
- **Built-in code editor**: file tree, sora-editor, loopback-only live preview, save with instant ETag refresh.
- **TextMate syntax highlighting** in the editor: official vscode grammars (HTML/CSS/JS/JSON/MD/XML) + Red Hat YAML, two Stratos themes (light/dark) swapped in step with the app theme; fully guarded — a malformed grammar degrades to plain text, never a crash.
- **Custom-domain wizard**: guided 5-step flow on top of the named tunnel (hostname → tunnel token → decoded public tunnel ID → CNAME instructions → live HTTPS verification). Only the public tunnel ID is decoded; the secret is never extracted, shown or stored.
- **Honest metrics**: requests, active connections, p50/p95/max, status-code distribution, memory/storage/battery-temperature/network counters, 60-second req/s sparkline; CPU shown as *Unavailable* when the platform denies it.
- **Cloudflare Tunnel manager**: Quick Tunnel (temporary `*.trycloudflare.com`) and Named Tunnel (persistent hostname), token via environment only, redacted log tail.
- **Foreground hosting service** (`specialUse`): partial wake lock + high-perf Wi-Fi lock, persistent notification with STOP, clean stop on every path.
- **Trilingual UI** (Arabic full RTL, English, Turkish) with per-app language switching.
- **QR code** for the LAN URL; copy/open actions everywhere.
- **Share-target import**: ACTION_SEND / ACTION_VIEW entry points — a ZIP shared from any app or opened from a file manager is staged into the app cache and flows into the same guarded import preview; the transient content URI is copied immediately, never reused later.
- **Share action** on the dashboard for the live LAN URL (system share sheet).
- **Editor text-size (60–200%) and word-wrap controls**, persisted in settings and applied live to the sora editor.
- **Launch experience** (splash → welcome → tour → permissions): an animated splash with an orbital energy ring, breathing halo and version chip (reads the frozen manifest version, minimum display guaranteed); a trilingual welcome screen greeting users in AR/EN/TR; a 3-page swipeable app tour (the phone as a real server · AI creation + syntax-highlighted editor · QR/tunnels/custom domains); and a consent-first permissions screen that explains *why* before asking — POST_NOTIFICATIONS (Android 13+), legacy READ_EXTERNAL_STORAGE (Android ≤ 12 only, `maxSdkVersion=32`), and the battery-optimization exemption (direct system dialog with a settings-list fallback), each with live Allowed/Not-yet status, an always-available Continue, and a "Replay introduction" row in Settings. Android 12+ system splash (`values-v31`/`values-night-v31`) is brand-aligned so cold boot is seamless in both themes.
- Tests: 31 Go tests (server, ZIP security, bus, redaction, percentiles; race-clean) + Kotlin JVM tests (ZIP security incl. hand-built duplicate-entry ZIPs, tunnel-token decoding, path helpers, redaction, template generator).
- **Unified build pipeline** (`.github/workflows/build.yml`, replacing the earlier two-file pair): Go + JVM gates → debug APK artifact on every push/PR; `v*` tags → signed-or-documented-fallback APK on the GitHub Release with SHA-256 checksums.

### Changed
- **Custom Arabic typography** (bundled Cairo font, SIL OFL 1.1 — notices updated): a geometric, Arabic-first family that covers Arabic, Latin and Turkish in one brand voice, weight-mapped (400/500/600/700) into every Material 3 text style. The shared type scale is tracking-free because nonzero letter-spacing breaks Arabic glyph joining; wide tracking is reserved for the Latin-only STRATOS wordmark (which now settles from wide to final during the splash boot moment). Line heights raised for Arabic dots/hamza breathing room. Third-party notices gained the full OFL section.
- **Extra tour animations** (version number unchanged):
  - Choreographed entrances: welcome texts, tour-page content and permission cards arrive as staggered sequences (lift + fade on one shared progress; tour pages replay their choreography every time they re-enter the viewport).
  - Swipe parallax in the tour: hero, title and bullets travel at different depths while the layer fades near the page edge — pager offsets are read inside `graphicsLayer` lambdas, so swiping costs no recomposition.
  - Living hero stages: rotating energy ring, breathing halo and three ion particles on elliptical comet-tail orbits behind the splash logo, welcome logo and tour icons (draw-phase only).
  - Tour dots upgraded: springy width change, animated color, breathing plasma glow behind the active dot; the "step n of m" counter now rolls vertically (RTL-safe).
  - Micro-interactions: GlowButton press squash (springy 0.965×), permission status pill morphs color and pulses once on grant, welcome CTA arrow nudges along the layout direction (RTL-aware), step transitions fade + scale (identical in RTL/LTR).
- **Stratos 2035 interface** (version number unchanged):
  - Glassmorphic surfaces (translucent fill + gradient hairline + polished top highlight) over a living aurora backdrop (three drifting plasma blobs, draw-phase-only animation — no recomposition cost).
  - Floating glass dock replaces the bottom navigation bar: translucent, under-glow energy line, animated gradient pill for the selected destination.
  - Signature gradient accents: energy-sweep CTAs (blue → violet → cyan) with breathing ion halos; gradient logo tile; gradient hairline "energy ticks" on stat tiles.
  - Animated data displays: stat values slide on change; plasma-gradient sparkline with glow underlay and live head marker; status-code bars animate to their values.
  - Living status indicators: pulsing halo + bright core in both themes.
  - Persisted theme setting (light/dark/system) now truly drives the app theme; editor surface, night-boot resources and both TextMate themes follow the same palette.

### Security
- Keystore AES-256-GCM vault for tunnel token and AI key; excluded from backups; `Bearer`/JWT redaction in all logs.
- `network_security_config` allows cleartext only on loopback; WebView preview locked to loopback without file access.
- Path helpers reject any `..` element after decoding, mirroring the Go engine's pre-I/O traversal refusal.
