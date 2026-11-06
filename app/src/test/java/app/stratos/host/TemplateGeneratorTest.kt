package app.stratos.host

import app.stratos.host.ai.TemplateGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The offline site generator must always produce a self-contained page. */
class TemplateGeneratorTest {

    private val input = TemplateGenerator.Input(
        title = "متجر النخبة",
        tagline = "حلول تجارة عصرية",
        about = "نساعد الشركات على البيع عبر الإنترنت.",
        services = listOf("تصميم المتاجر", "شحن سريع", "دعم 24/7"),
        phone = "+966 50 000 0000",
        email = "hi@example.com",
        address = "الرياض، السعودية",
        language = "ar",
        accent = "#0A7CFF"
    )

    @Test
    fun `generates rtl single-file html for arabic`() {
        val html = TemplateGenerator.generate(input)
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("dir=\"rtl\""))
        assertTrue(html.contains("lang=\"ar\""))
        assertTrue(html.contains("#0A7CFF"))
        assertTrue(html.contains("متجر النخبة"))
        // Self-contained: no external requests
        assertFalse(html.contains("https://fonts"))
        assertFalse(html.contains("<script src="))
        assertFalse(html.contains("<link rel=\"stylesheet\" href=\"http"))
    }

    @Test
    fun `escapes user content to prevent injection`() {
        val malicious = input.copy(title = "<script>alert(1)</script>")
        val html = TemplateGenerator.generate(malicious)
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `ltr for english and turkish`() {
        val en = TemplateGenerator.generate(input.copy(language = "en", title = "Elite Store"))
        assertTrue(en.contains("dir=\"ltr\""))
        assertTrue(en.contains("Elite Store"))
        val tr = TemplateGenerator.generate(input.copy(language = "tr", title = "Mağaza"))
        assertTrue(tr.contains("dir=\"ltr\""))
    }
}
