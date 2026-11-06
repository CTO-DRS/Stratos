#!/usr/bin/env bash
# Creates a small demo site ZIP you can import into Stratos to test everything
# (import → host → editor → metrics) in one minute.
set -euo pipefail
cd "$(dirname "$0")"

OUT="${1:-demo-site.zip}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/index.html" <<'HTML'
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>Hello from Stratos</title>
<link rel="stylesheet" href="style.css"/>
</head>
<body>
<main>
  <span class="pill"><span class="dot"></span> Served by Stratos</span>
  <h1>It works. From your pocket.</h1>
  <p>This page is a real static file served by the embedded engine on your phone.
     Edit it in the built-in editor, hit refresh, and see your change live.</p>
  <ul>
    <li><strong>Server:</strong> embedded Go / Kotlin engine</li>
    <li><strong>Network:</strong> your own Wi-Fi — no VPS, no deploy</li>
    <li><strong>Metrics:</strong> watch requests appear on the Metrics tab</li>
  </ul>
</main>
<footer>Stratos demo site</footer>
</body>
</html>
HTML

cat > "$WORK/style.css" <<'CSS'
:root{color-scheme:light}
*{box-sizing:border-box;margin:0}
body{font-family:-apple-system,'Segoe UI',Roboto,sans-serif;background:#fafafa;color:#0a0a0a;
min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:32px}
main{background:#fff;border:1px solid #e4e4e7;border-radius:16px;padding:44px 38px;max-width:560px}
.pill{display:inline-flex;align-items:center;gap:8px;border:1px solid #e4e4e7;border-radius:999px;
padding:5px 12px;font-size:13px;color:#52525b;background:#fafafa}
.dot{width:8px;height:8px;border-radius:50%;background:#16a34a;box-shadow:0 0 0 4px rgba(22,163,74,.15)}
h1{font-size:30px;margin:20px 0 10px;letter-spacing:-.02em}
p{color:#52525b;line-height:1.65}
ul{margin:18px 0 0;padding-inline-start:20px;color:#3f3f46;line-height:1.9}
footer{margin-top:22px;color:#a1a1aa;font-size:13px}
CSS

mkdir -p "$WORK/assets"
printf 'console.log("hello from stratos demo");\n' > "$WORK/assets/app.js"

# NOTE: deliberately at the top level (like most AI builders export)
(cd "$WORK" && zip -qr "$(cd "$(dirname "$OUT")" 2>/dev/null && pwd || pwd)/$(basename "$OUT")" index.html style.css assets)
echo "✅ Created $OUT — import it from Stratos → Sites → ➕"
