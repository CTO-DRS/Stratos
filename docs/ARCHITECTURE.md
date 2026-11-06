# Stratos — Architecture

This document describes how Stratos is built, why each decision was made, and
how the pieces talk to each other. It is written to be useful both for
reviewing the code and for extending it.

## 1. Bird's-eye view

```
┌──────────────────────────────────────────────────────────────────────┐
│ Android app (single process)                                         │
│                                                                      │
│  Compose UI ── ViewModels ── Repositories ── Room (sites)            │
│       │                          │                                   │
│       │                          ├── DataStore (settings)            │
│       │                          ├── Keystore vault (secrets)        │
│       │                          │                                   │
│       ├── HostingController ──────┤                                   │
│       │         │                 │                                   │
│       │   EngineApi (interface)  ←── single contract                │
│       │         │                                                     │
│       │   ┌─────┴──────────────┐                                     │
│       │   │ GoEngine (JNI bus) │  NanoEngine (NanoHTTPD, fallback)   │
│       │   └─────┬──────────────┘                                     │
│       │         │ JSON command bus                                    │
│       │   Go engine (engine.aar → libgojni.so): net/http + os.Root    │
│       │                                                               │
│  HostingService (foreground, specialUse) — wake lock + Wi-Fi lock    │
│  TunnelManager (cloudflared child process, env-only token)           │
└──────────────────────────────────────────────────────────────────────┘
        │                                   │
        ▼                                   ▼
  http://<lan-ip>:8080            Cloudflare edge (HTTPS)
  (same Wi-Fi)                    via cloudflared tunnel
```

## 2. The two engines

Both engines implement one interface (`engine/EngineApi.kt`):

```kotlin
interface EngineApi {
    val kind: EngineKind               // GO | KOTLIN
    suspend fun start(bind, port, sites): Result<String>
    suspend fun stop()
    suspend fun status(): EngineStatus?
    suspend fun setSites(sites)        // hot reload, listener untouched
    suspend fun resetMetrics()
    suspend fun extractZip(zipPath, destDir): Result<ZipSummary>
}
```

| | GoEngine | NanoEngine |
|---|---|---|
| Availability | needs `engine.aar` built with gomobile | always |
| Selection | reflection: `Class.forName("engine.Engine")` | default |
| Confinement | `os.Root` (symlink-escape-proof) | canonical-path prefix check per request |
| Range requests | single byte-range | single byte-range |
| gzip | on the fly, 200 B–10 MB | on the fly, 201 B–10 MB |
| ZIP validation | authoritative (Go) | same rules in Kotlin |

**Why a fallback engine?** The original PocketHost requires a working
gomobile build before the app runs at all. Stratos must open and serve on the
first Android Studio *Run* — the Go engine is an optimization, not a
prerequisite. Both engines are covered by tests (Go: `go test`; Kotlin: JVM
unit tests for the shared helpers).

## 3. The JSON command bus (Kotlin ↔ Go)

There is deliberately **no management HTTP surface**. The Kotlin layer drives
the engine through one JNI entry point:

```
Command(request: String) -> String      // JSON in, JSON out
```

Commands: `ping`, `start`, `stop`, `status`, `sites.set`,
`metrics.reset`, `zip.extract`, `log.tail`. Responses are always
`{"ok":true,"data":…}` or `{"ok":false,"error":"…"}`. This mirrors (and
matches field-for-field) the original's command-bus design, while adding
`zip.extract` so the strongest validator runs before anything is committed.

## 4. Import pipeline (security-critical)

```
SAF Uri ──copy──▶ cache/import-*.zip
                       │
        ┌──────────────▼───────────────┐
        │ ValidateAndExtract (Go | Kotlin)
        │  · entry count ≤ 20 000       │
        │  · total ≤ 512 MB, entry ≤ 256 MB
        │  · ratio ≤ 500 (uncompressed > 1 MB)
        │  · reject: symlinks, encrypted entries,
        │    duplicates, "..", absolute, drive letters, NUL
        │  · per-entry CRC32 on read
        │  · atomic: sites/staging-x → rename
        └──────────────┬───────────────┘
                       ▼
        root detection: index.html | single top dir | public/
                       ▼
        Room: SiteEntity(rootPath = extractDir + root)
```

Serving then re-confines every request through `os.Root` (Go) or
canonical-path containment (Kotlin), and `fs.ValidPath`-equivalent checks
reject traversal strings before any I/O.

## 5. Foreground service lifecycle

`HostingService` (foreground, `specialUse`) acquires a partial wake lock and a
high-perf Wi-Fi lock, publishes a persistent notification with a STOP action,
and drives `HostingController.start()/stop()`. It stops itself on `Failed` or
a full `Running → Idle` cycle — never on the initial `Idle`. START_STICKY plus
idempotent restart in the bus keeps reboots and process kills recoverable.

## 6. Tunnel

`TunnelManager` spawns `filesDir/native/cloudflared`:

- **Quick:** `tunnel --url http://127.0.0.1:<port> --no-autoupdate --protocol http2`
  → the `https://*.trycloudflare.com` URL is parsed from stdout.
- **Named:** `TUNNEL_TOKEN` is passed via **environment only** (never argv),
  decrypted from the Keystore vault at spawn time.

Logs are consumed into a bounded ring; every line passes through `Redact`
(JWT/bearer patterns) before storage or display.

## 7. AI site builder

Two modes behind one screen:

1. **Offline template engine** (`TemplateGenerator`) — composes a modern
   single-file site from structured input; RTL-aware, injection-escaped,
   zero network. Covered by JVM tests.
2. **AI API** (`AiClient`) — any OpenAI-compatible `/chat/completions`
   endpoint; prompt hardening in `PromptBuilder` forces one self-contained
   HTML document. The API key lives only in the Keystore vault and is passed
   as a `CharArray`.

Generated HTML is written to `sites/<id>/index.html` and registered through
`commitGeneratedSite`, so it becomes a first-class hosted site.

## 8. Per-app languages

`values/` (en) + `values-ar/` + `values-tr/`, `android:localeConfig`, and
`AppCompatDelegate.setApplicationLocales` with `autoStoreLocales`. Compose
mirrors automatically for RTL; monospace/numeric strings use
`Formatters` (locale-stable) to avoid Arabic-Indic digit surprises in URLs.

## 9. Building the Go engine AAR

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
cd engine && go test ./... -count=1
gomobile bind -target=android -androidapi 26 -o build/engine.aar .
```

`app/build.gradle.kts` attaches `engine/build/engine.aar` when present.

## 10. Building cloudflared for Android

The binary must be an android-arm64 build. Recommended path (matches the
original project's approach):

```bash
git clone https://github.com/cloudflare/cloudflared
cd cloudflared
# Go mobile cross-compile for android/arm64:
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -trimpath \
  -ldflags '-s -w' -o cloudflared-android-arm64 ./cmd/cloudflared
adb push cloudflared-android-arm64 /data/local/tmp/
# then in-app shell (or via run-as): copy to filesDir/native/cloudflared && chmod 700
```

Stratos also patches DNS the same way the original documented
(`net.DefaultResolver` → 1.1.1.1 / 8.8.8.8) if you need to build from a
patched source tree.

## 11. Code editor and syntax highlighting

`EditorScreen` hosts sora-editor with TextMate grammars loaded by
`ui/editor/SyntaxSupport.kt`:

- The grammars are the official vscode TextMate definitions (HTML, CSS,
  JavaScript, JSON, Markdown, XML) plus Red Hat's YAML definition, stored in
  `assets/textmate/` (MIT-licensed — see `THIRD_PARTY_NOTICES.md`).
- `SyntaxSupport` maps file extensions to scope names, registers grammars and
  themes once per process, and swaps the light/dark TextMate theme in step
  with the app theme.
- Loading is fully guarded (`runCatching`): a malformed grammar degrades to
  plain text instead of crashing the editor.

## 12. Custom-domain wizard

`DomainWizardScreen` is a 5-step flow on top of the named tunnel:

1. **Hostname** — normalized and validated by `CustomDomain` (RFC 1123
   subset: lowercase, label length, no leading/trailing hyphen).
2. **Token paste** — `TunnelToken.decodeTunnelId` decodes the base64url
   payload and extracts **only the public tunnel UUID (`t`)**. The secret
   (`s`) is never extracted, displayed, logged or stored by the wizard.
3. **Review** — hostname, tunnel ID and the resulting CNAME target
   `<tunnel-id>.cfargotunnel.com`, each with a copy action.
4. **DNS** — CNAME instructions (proxied/orange-cloud) for `@` or `www`.
5. **Verify** — an OkHttp `GET https://<domain>` on `Dispatchers.IO`; a 2xx
   response persists the hostname into `SettingsStore.customDomain`
   (DataStore — a convenience record, never a secret).

The wizard never touches the connection secret: the tunnel token keeps
living only in the Keystore vault and reaches cloudflared through the
environment, exactly as described in section 6.
