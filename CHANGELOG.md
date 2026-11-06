# Changelog

All notable changes to Stratos are documented here. Format: Keep a Changelog 1.1; versioning: SemVer.

## [0.1.0] — 2026-11-06

First public release.

### Added
- **Dual serving engine**: stdlib-only Go engine (`net/http`, `os.Root`, gomobile-ready) + pure-Kotlin NanoHTTPD fallback with one shared `EngineApi` contract. The app runs out of the box; the Go engine is a drop-in performance upgrade.
- **JSON command bus** (`ping/start/stop/status/sites.set/metrics.reset/zip.extract/log.tail`) over JNI — no management HTTP surface.
- **Secure ZIP import**: zip-slip, symlinks, zip bombs, encrypted/duplicate entries, drive-letter/NUL names, per-entry CRC32; atomic extraction; root auto-detection (index.html / single top folder / public/).
- **Multi-site hosting**: default site at `/`, secondary at `/s/<id>/`, SPA fallback, ETag/Last-Modified/304, gzip, single byte-range, branded 404/405/welcome pages.
- **AI site builder**: offline template engine (trilingual, RTL-aware, injection-escaped) + OpenAI-compatible API client (Z.ai GLM / OpenAI / OpenRouter presets), key sealed in the Keystore vault.
- **Built-in code editor**: file tree, sora-editor, loopback-only live preview, save with instant ETag refresh.
- **TextMate syntax highlighting** in the editor: official vscode grammars (HTML, CSS, JavaScript, JSON, Markdown, XML) + Red Hat YAML, bundled under `assets/textmate/` (MIT), with dedicated light/dark Stratos themes that follow the app theme; loading is fail-safe and degrades to plain text.
- **Custom-domain wizard**: 5-step guided flow on the Tunnel screen — hostname validation (RFC 1123 subset), tunnel-token paste that decodes **only the public tunnel ID** (the secret is never extracted, shown or stored), CNAME review with copy actions, DNS instructions, and a live HTTPS verification that persists the hostname. Covered by JVM tests (8 cases).
- **Honest metrics**: requests, active connections, p50/p95/max, status-code distribution, memory/storage/battery-temperature/network counters, 60-second req/s sparkline; CPU shown as *Unavailable* when the platform denies it.
- **Cloudflare Tunnel manager**: Quick Tunnel (temporary `*.trycloudflare.com`) and Named Tunnel (persistent hostname), token via environment only, redacted log tail.
- **Foreground hosting service** (`specialUse`): partial wake lock + high-perf Wi-Fi lock, persistent notification with STOP, clean stop on every path.
- **Trilingual UI** (Arabic full RTL, English, Turkish) with per-app language switching; Vercel-grade light theme with dark companion.
- **QR code** for the LAN URL; copy/open actions everywhere.
- Tests: 31 Go tests (server, ZIP security, bus, redaction, percentiles; race-clean) + Kotlin JVM tests (ZIP security, path helpers, redaction, template generator, tunnel-token decoding).

### Security
- Keystore AES-256-GCM vault for tunnel token and AI key; excluded from backups; `Bearer`/JWT redaction in all logs.
- `network_security_config` allows cleartext only on loopback; WebView preview locked to loopback without file access.
