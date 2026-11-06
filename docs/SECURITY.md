# Stratos — Security Model

Stratos runs a real HTTP server on a personal device. The threat model below
is inherited from the PocketHost-class design and extended for the features
Stratos adds (AI builder, code editor, dual engine).

## Threat table

| Threat | Mitigation |
|---|---|
| Malicious ZIP: path traversal (zip-slip) | entry names rejected on `..` segments **before** cleaning; `path.Clean` canonicalisation; serving re-checks through `os.Root` / canonical-path containment |
| ZIP symlink escape | symlink entries refused at import; `os.Root` refuses escaping symlinks at serve time |
| Zip bomb | entry count ≤ 20 000; total ≤ 512 MB; per-entry ≤ 256 MB; compression-ratio ≤ 500 enforced for entries expanding beyond 1 MB; extraction is atomic (staging dir + rename) |
| Corrupted archive | per-entry CRC32 verified while reading; size mismatch rejected |
| Encrypted / duplicate entries | rejected with explicit user-facing reason |
| Token theft from storage | tunnel token and AI key encrypted with Android Keystore AES-256-GCM (non-exportable key); prefs file excluded from all backups |
| Token leakage into logs | `Redact` (Go + Kotlin): `eyJ…` tokens and `Bearer` values redacted before any log ring or UI |
| Token on the command line | cloudflared receives the token via **environment only** (`TUNNEL_TOKEN`), never argv |
| Remote control of the server | **no management HTTP surface** — the engine is driven by a JSON command bus over JNI only |
| Unwanted internet exposure | default bind is LAN (or loopback); going public requires an explicit tunnel connection with user consent |
| WebView preview escape | preview loads only `http://127.0.0.1:<port>`; `allowFileAccess=false`, `allowContentAccess=false`, no JS bridge |
| Cleartext to the internet | `network_security_config` permits cleartext **only** for `127.0.0.1`/`localhost`; everything else requires TLS |
| Path traversal while serving | `fs.ValidPath`-equivalent normalisation before any open (Go), `Paths.normalize` + canonical containment (Kotlin); traversal tests cover `/../`, encoded dots, secondary-site prefix |
| AI key theft | same vault as tunnel token; key never leaves the device, never logged, masked in UI |
| Prompt injection producing malicious HTML | AI output is a static HTML file served like any other; it runs in the *visitor's* browser as plain static content — no server-side execution exists by design (static hosting only) |
| Editor writing outside site root | the editor tree is rooted at the site's root path; saves write to the exact file node that was opened (no path inputs) |
| Backup exfiltration | `dataExtractionRules` + `fullBackupContent` exclude the vault prefs and all hosted site files |

## Serving invariants

1. Only `GET` and `HEAD` are served; everything else answers `405` with `Allow`.
2. ETag/Last-Modified conditional requests answer `304` without a body.
3. Response headers always include `X-Content-Type-Options: nosniff` and a strict `Referrer-Policy`.
4. Directory requests without `index.html` answer `404` (no listings, no autoindex).
5. Percentile maths is computed from one sorted sample snapshot, so `p50 ≤ p95 ≤ max` holds by construction; the engine re-asserts the invariant defensively.

## Honest telemetry

Where Android/SELinux denies a measurement (commonly `/proc/stat` for CPU %),
the UI shows **Unavailable** — the app never fabricates numbers. This is a
product principle, not a bug, and it is covered by tests on both engines.

## What Stratos will never do

- no accounts, ads, analytics, telemetry or crash reporting
- no dynamic backends (PHP/Node/DB) — static files only, so there is no server-side execution surface at all
- no silent exposure to the internet
- no secrets in plaintext storage, argv, logs or backups
