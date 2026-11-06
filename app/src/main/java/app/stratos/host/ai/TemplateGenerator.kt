package app.stratos.host.ai

/**
 * 100% offline site generator — the AI builder's no-account/no-internet mode.
 * Composes a modern, clean single-file site (Vercel-like light aesthetic)
 * from the user's structured input. Trilingual and RTL-aware.
 */
object TemplateGenerator {

    data class Input(
        val title: String,
        val tagline: String,
        val about: String,
        val services: List<String>,
        val phone: String,
        val email: String,
        val address: String,
        val language: String,   // ar | en | tr
        val accent: String      // any CSS color
    )

    fun generate(input: Input): String {
        val dir = if (input.language == "ar") "rtl" else "ltr"
        val labels = labels(input.language)

        val servicesHtml = if (input.services.isEmpty()) "" else """
      <section id="services">
        <h2>${labels.services}</h2>
        <div class="grid">
            ${input.services.filter { it.isNotBlank() }.joinToString("\n") { svc ->
                "<div class=\"card\"><h3>${esc(svc)}</h3></div>"
            }}
        </div>
      </section>"""

        val contactHtml = """
      <section id="contact">
        <h2>${labels.contact}</h2>
        <div class="contact-grid">
          ${if (input.phone.isNotBlank()) contactItem(labels.phone, input.phone, "tel:" + input.phone.replace(" ", "")) else ""}
          ${if (input.email.isNotBlank()) contactItem(labels.email, input.email, "mailto:${input.email}") else ""}
          ${if (input.address.isNotBlank()) contactItem(labels.address, input.address, null) else ""}
        </div>
      </section>"""

        return """<!DOCTYPE html>
<html lang="${input.language}" dir="$dir">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>${esc(input.title)}</title>
<meta name="description" content="${esc(input.tagline)}"/>
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='7' fill='${input.accent}'/%3E%3Cpath d='M9 22 16 9l7 13z' fill='white'/%3E%3C/svg%3E"/>
<style>
:root{--accent:${input.accent};--bg:#fafafa;--card:#ffffff;--text:#0a0a0a;--muted:#71717a;--border:#e4e4e7}
*{box-sizing:border-box;margin:0;padding:0}
html{scroll-behavior:smooth}
body{font-family:-apple-system,'Segoe UI',Roboto,'Noto Sans Arabic',sans-serif;background:var(--bg);color:var(--text);line-height:1.65}
.container{max-width:960px;margin:0 auto;padding:0 24px}
header{position:sticky;top:0;background:rgba(250,250,250,.85);backdrop-filter:blur(10px);border-bottom:1px solid var(--border);z-index:10}
nav{display:flex;align-items:center;justify-content:space-between;height:64px}
.logo{display:flex;align-items:center;gap:10px;font-weight:700;text-decoration:none;color:var(--text)}
.logo-mark{width:26px;height:26px;border-radius:7px;background:var(--accent);display:inline-flex;align-items:center;justify-content:center}
.logo-mark svg{width:14px;height:14px}
.nav-links{display:flex;gap:22px;font-size:14px}
.nav-links a{color:var(--muted);text-decoration:none;transition:color .15s}
.nav-links a:hover{color:var(--text)}
@media(max-width:640px){.nav-links{display:none}}
.hero{padding:96px 0 72px;text-align:center}
.hero h1{font-size:clamp(32px,6vw,52px);font-weight:700;letter-spacing:-.02em;line-height:1.15}
.hero p{margin:18px auto 0;max-width:56ch;color:var(--muted);font-size:18px}
.hero .cta{margin-top:32px;display:inline-flex;gap:12px;flex-wrap:wrap;justify-content:center}
.btn{display:inline-flex;align-items:center;gap:8px;padding:11px 20px;border-radius:10px;font-size:15px;font-weight:600;text-decoration:none;transition:transform .12s,box-shadow .12s}
.btn-primary{background:var(--accent);color:#fff}
.btn-primary:hover{transform:translateY(-1px);box-shadow:0 6px 20px rgba(0,0,0,.12)}
.btn-ghost{border:1px solid var(--border);background:var(--card);color:var(--text)}
.btn-ghost:hover{border-color:#cfcfd4}
section{padding:64px 0}
section h2{font-size:clamp(24px,3.4vw,32px);font-weight:650;letter-spacing:-.01em;margin-bottom:28px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:16px}
.card{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:22px;transition:border-color .15s}
.card:hover{border-color:var(--accent)}
.card h3{font-size:16px;font-weight:600;margin-bottom:8px}
.about p{color:var(--muted);max-width:70ch;font-size:16px}
.contact-grid{display:grid;gap:12px;grid-template-columns:repeat(auto-fit,minmax(220px,1fr))}
.contact-item{background:var(--card);border:1px solid var(--border);border-radius:12px;padding:16px 18px;font-size:15px}
.contact-item .k{font-size:12px;text-transform:uppercase;letter-spacing:.08em;color:var(--muted);display:block;margin-bottom:4px}
.contact-item a{color:var(--text);text-decoration:none}
footer{border-top:1px solid var(--border);padding:28px 0;color:var(--muted);font-size:14px;text-align:center}
footer b{color:var(--text)}
</style>
</head>
<body>
<header>
  <div class="container">
    <nav>
      <a class="logo" href="#top" id="top"><span class="logo-mark"><svg viewBox="0 0 24 24" fill="none"><path d="M12 3 4 21h16z" fill="white"/></svg></span>${esc(input.title)}</a>
      <div class="nav-links">
        <a href="#about">${labels.about}</a>
        <a href="#contact">${labels.contact}</a>
      </div>
    </nav>
  </div>
</header>
<main>
  <div class="hero container">
    <h1>${esc(input.tagline.ifBlank { input.title })}</h1>
    <p>${esc(input.about)}</p>
    <div class="cta">
      <a class="btn btn-primary" href="#contact">${labels.ctaPrimary}</a>
      <a class="btn btn-ghost" href="#about">${labels.about}</a>
    </div>
  </div>
  <div class="container">
    <section id="about" class="about">
      <h2>${labels.about}</h2>
      <p>${esc(input.about)}</p>
    </section>
    $servicesHtml
    $contactHtml
  </div>
</main>
<footer>
  <div class="container">© ${java.time.Year.now().value} <b>${esc(input.title)}</b> · ${labels.madeWith} Stratos</div>
</footer>
</body>
</html>"""
    }

    private fun contactItem(key: String, value: String, href: String?): String {
        val content = if (href != null) "<a href=\"${esc(href)}\">${esc(value)}</a>" else esc(value)
        return """<div class="contact-item"><span class="k">${esc(key)}</span>$content</div>"""
    }

    private data class Labels(
        val about: String, val services: String, val contact: String,
        val phone: String, val email: String, val address: String,
        val ctaPrimary: String, val madeWith: String
    )

    private fun labels(lang: String): Labels = when (lang) {
        "ar" -> Labels("من نحن", "خدماتنا", "تواصل معنا", "الهاتف", "البريد الإلكتروني", "العنوان", "ابدأ الآن", "صُنع بواسطة")
        "tr" -> Labels("Hakkımızda", "Hizmetler", "İletişim", "Telefon", "E-posta", "Adres", "Başlayın", "Yapan")
        else -> Labels("About", "Services", "Contact", "Phone", "Email", "Address", "Get started", "Made with")
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}
