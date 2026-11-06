# Stratos ProGuard/R8 rules.

# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.stratos.host.**$$serializer { *; }
-keepclassmembers class app.stratos.host.** { *** Companion; }
-keepclasseswithmembers class app.stratos.host.** { kotlinx.serialization.KSerializer serializer(...); }

# Go engine (gomobile) — reflective JNI surface. Only relevant when the
# engine.aar is bundled; harmless otherwise.
-if class engine.**
-keep class engine.** { *; }

# NanoHTTPD fallback engine
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# sora-editor language-textmate: Gson parses language-configuration JSON
# reflectively into tm4e model classes; Joni/JCodings power the regex engine.
-keep class org.eclipse.tm4e.** { *; }
-keep class io.github.rosemoe.sora.langs.textmate.** { *; }
-keep class org.joni.** { *; }
-keep class org.jcodings.** { *; }
-dontwarn org.eclipse.jdt.annotation.**
-dontwarn org.snakeyaml.engine.**
