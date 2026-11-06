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
- Tests: 31 Go tests (server, ZIP security, bus, redaction, percentiles; race-clean) + Kotlin JVM tests (ZIP security incl. hand-built duplicate-entry ZIPs, tunnel-token decoding, path helpers, redaction, template generator).
- **Build pipeline** (`.github/workflows/`): `ci.yml` (Go + JVM gates, debug APK artifact on every push/PR) and `release.yml` (`v*` tags → signed-or-documented-fallback APK on the GitHub Release with SHA-256 checksums).

### Changed
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
