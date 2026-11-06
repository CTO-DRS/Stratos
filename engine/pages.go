package engine

import "fmt"

// Branded error/status pages. Kept tiny and self-contained (no assets) so a
// 404 never depends on the site itself. Minimal inline CSS, light theme.
func errorPage(code int, title, msg string) string {
	return fmt.Sprintf(`<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>%d · Stratos</title>
<style>
:root{color-scheme:light}
*{box-sizing:border-box;margin:0}
body{font-family:-apple-system,'Segoe UI',Roboto,'Noto Sans Arabic',sans-serif;
background:#fafafa;color:#0a0a0a;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px}
.card{background:#fff;border:1px solid #e4e4e7;border-radius:16px;padding:48px 40px;max-width:440px;width:100%%;text-align:center}
.code{font-size:13px;letter-spacing:.08em;color:#71717a;text-transform:uppercase}
h1{font-size:28px;margin:12px 0 8px;font-weight:600}
p{color:#52525b;font-size:15px;line-height:1.6}
.brand{margin-top:28px;font-size:13px;color:#a1a1aa}
.brand b{color:#0a0a0a;font-weight:600}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%%;background:%s;margin-inline-end:6px;vertical-align:middle}
</style>
</head>
<body><div class="card">
<div class="code"><span class="dot"></span>HTTP %d</div>
<h1>%s</h1><p>%s</p>
<div class="brand">Served by <b>Stratos</b> · your pocket server</div>
</div></body></html>`, code, statusColor(code), code, title, msg)
}

func statusColor(code int) string {
	switch {
	case code >= 500:
		return "#dc2626"
	case code >= 400:
		return "#d97706"
	default:
		return "#16a34a"
	}
}

// welcomePage is served when hosting is on but no default site is set.
func welcomePage(addr string) string {
	return fmt.Sprintf(`<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>Stratos is live</title>
<style>
:root{color-scheme:light}
*{box-sizing:border-box;margin:0}
body{font-family:-apple-system,'Segoe UI',Roboto,'Noto Sans Arabic',sans-serif;
background:#fafafa;color:#0a0a0a;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px}
.card{background:#fff;border:1px solid #e4e4e7;border-radius:16px;padding:56px 44px;max-width:520px;width:100%%;text-align:center}
.pill{display:inline-flex;align-items:center;gap:8px;border:1px solid #e4e4e7;border-radius:999px;padding:6px 14px;font-size:13px;color:#52525b}
.live{width:8px;height:8px;border-radius:50%%;background:#16a34a;box-shadow:0 0 0 4px rgba(22,163,74,.15)}
h1{font-size:30px;margin:22px 0 10px;font-weight:650;letter-spacing:-.02em}
p{color:#52525b;font-size:15px;line-height:1.65;max-width:40ch;margin:0 auto}
.hint{margin-top:26px;background:#fafafa;border:1px dashed #e4e4e7;border-radius:10px;padding:14px 16px;font-size:13.5px;color:#71717a}
.addr{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;color:#0a0a0a}
.brand{margin-top:30px;font-size:13px;color:#a1a1aa}.brand b{color:#0a0a0a}
</style>
</head>
<body><div class="card">
<span class="pill"><span class="live"></span>%s</span>
<h1>Stratos is live.</h1>
<p>This server is running, but no website has been set as the default yet. Import a site in the Stratos app and it will appear right here.</p>
<div class="hint">Listening on <span class="addr">%s</span></div>
<div class="brand">Powered by <b>Stratos</b> — your pocket server</div>
</div></body></html>`, "● Server online", addr)
}
