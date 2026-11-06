package app.stratos.host.ai

/**
 * Prompt construction for the AI site builder. Produces a tight instruction
 * pair that yields a single self-contained HTML file (no external assets),
 * mobile-first and accessible.
 */
object PromptBuilder {

    data class Request(
        val title: String,
        val description: String,
        val language: String,     // ar | en | tr
        val palette: String,      // "blue" | "emerald" | "violet" | "mono"
        val sections: List<String>
    )

    private val paletteHex = mapOf(
        "blue" to "#0A7CFF",
        "emerald" to "#0E9F6E",
        "violet" to "#7C3AED",
        "mono" to "#0A0A0A"
    )

    fun systemPrompt(): String = """
You are a senior front-end engineer. Output exactly ONE complete HTML5 document,
nothing else — no markdown fences, no explanations. Rules:
- Single self-contained file: inline <style> and <script>, zero external requests.
- Modern, minimal, clean design (Vercel/Linear aesthetic): generous whitespace,
  1px borders, rounded corners, subtle hover states, system font stack.
- Mobile-first responsive (fluid type, flex/grid), semantic HTML, WCAG-AA contrast.
- RTL (`dir="rtl"`) when the content language is Arabic; otherwise LTR.
- Include: meta viewport, <title>, favicon as inline SVG data URI.
- Language of the page content must be: %LANGUAGE%.
""".trimIndent()

    fun userPrompt(r: Request): String = """
Build a single-page website with these specs:
- Site title: ${r.title}
- About: ${r.description}
- Accent color: ${paletteHex[r.palette] ?: paletteHex.getValue("blue")}
- Sections (in order): ${r.sections.joinToString(", ")}
- Content language: ${r.language}
- Write realistic, concise copy for each section; do not use lorem ipsum.
Return only the final HTML document.
""".trimIndent()

    fun forLanguage(r: Request): Pair<String, String> =
        systemPrompt().replace("%LANGUAGE%", r.language) to userPrompt(r)
}
