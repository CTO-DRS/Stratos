package engine

import (
	"fmt"
	"html"
	"net/url"
	"strings"
	"time"
)

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

// listingRow is one entry of the auto-index page (pure data, no I/O).
type listingRow struct {
	Name  string
	Dir   bool
	Size  int64
	Mtime time.Time
}

// dirListingPage renders the branded auto-index shown when a directory has
// no index page. Pure function so tests can assert escaping and ordering
// directly. Names are HTML-escaped for display and URL-escaped for hrefs;
// the trailing slash on directory links keeps relative navigation inside
// the listing. English copy matches the other server-side branded pages.
func dirListingPage(dirRel string, rows []listingRow) string {
	display := "/"
	if dirRel != "." && dirRel != "" {
		display = "/" + dirRel
	}
	title := html.EscapeString("Index of " + display)

	var b strings.Builder
	if dirRel != "." && dirRel != "" {
		b.WriteString(`<tr><td colspan="3"><a class="up" href="../">&larr; ../</a></td></tr>` + "\n")
	}
	for _, r := range rows {
		label := html.EscapeString(r.Name)
		href := html.EscapeString(url.PathEscape(r.Name))
		if r.Dir {
			label += "/"
			href += "/"
		}
		size := "—"
		if !r.Dir {
			size = humanBytes(r.Size)
		}
		mt := "—"
		if !r.Mtime.IsZero() {
			mt = r.Mtime.UTC().Format("2006-01-02 15:04")
		}
		cls := ""
		if r.Dir {
			cls = ` class="dir"`
		}
		fmt.Fprintf(&b,
			`<tr><td><a%s href="%s">%s</a></td><td class="dim">%s</td><td class="dim">%s</td></tr>`+"\n",
			cls, href, label, size, mt)
	}
	if len(rows) == 0 {
		b.WriteString(`<tr><td colspan="3" class="dim">Empty directory.</td></tr>` + "\n")
	}

	return fmt.Sprintf(`<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>%s · Stratos</title>
<style>
:root{color-scheme:light}
*{box-sizing:border-box;margin:0}
body{font-family:-apple-system,'Segoe UI',Roboto,'Noto Sans Arabic',sans-serif;
background:#fafafa;color:#0a0a0a;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px}
.card{background:#fff;border:1px solid #e4e4e7;border-radius:16px;padding:36px 32px;max-width:640px;width:100%%}
.code{font-size:13px;letter-spacing:.08em;color:#71717a;text-transform:uppercase}
h1{font-size:20px;margin:10px 0 18px;font-weight:600;font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
table{width:100%%;border-collapse:collapse;font-size:14.5px}
td{padding:7px 6px;border-bottom:1px solid #f0f0f2}
td:first-child{max-width:60%%;overflow-wrap:anywhere}
a{color:#0B6BFF;text-decoration:none}
a:hover{text-decoration:underline}
a.up{color:#52525b}
.dir a{font-weight:600}
.dim{color:#a1a1aa;font-size:13px;white-space:nowrap;font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
.brand{margin-top:22px;font-size:13px;color:#a1a1aa}.brand b{color:#0a0a0a;font-weight:600}
</style>
</head>
<body><div class="card">
<div class="code">Stratos · directory</div>
<h1>%s</h1>
<table>
%s</table>
<div class="brand">Served by <b>Stratos</b> · your pocket server</div>
</div></body></html>`, title, title, b.String())
}

// humanBytes formats a byte count the way the auto-index shows it.
func humanBytes(n int64) string {
	switch {
	case n >= 1<<30:
		return fmt.Sprintf("%.1f GB", float64(n)/(1<<30))
	case n >= 1<<20:
		return fmt.Sprintf("%.1f MB", float64(n)/(1<<20))
	case n >= 1<<10:
		return fmt.Sprintf("%.1f KB", float64(n)/(1<<10))
	default:
		return fmt.Sprintf("%d B", n)
	}
}
