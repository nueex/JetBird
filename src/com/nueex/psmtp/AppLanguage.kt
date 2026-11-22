package com.nueex.psmtp

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.text.NumberFormat
import java.util.Locale

internal enum class AppLanguage(val tag: String, val label: String, val font: String = "inter") {
    ENGLISH("en", "English"),
    FRENCH("fr", "Français"),
    ARABIC("ar", "العربية", "readex_arabic"),
    SPANISH("es", "Español"),
    RUSSIAN("ru", "Русский", "noto_cyrillic"),
    CHINESE("zh-Hans-CN", "简体中文", "noto_chinese"),
    HINDI("hi", "हिन्दी", "noto_devanagari");
    PORTUGUESE("pt-BR", "Português (Brasil)");

    val locale: Locale get() = Locale.forLanguageTag(tag)

    fun save(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }

    companion object {
        private const val PREFERENCES = "appearance"
        private const val KEY = "app_language"

        fun selected(context: Context): AppLanguage {
            val tag = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.tag == tag } ?: ENGLISH
        }

        fun wrap(base: Context): Context {
            val locale = selected(base).locale
            val configuration = Configuration(base.resources.configuration).apply {
                setLocales(LocaleList(locale))
                setLayoutDirection(locale)
            }
            return base.createConfigurationContext(configuration)
        }
    }
}

internal fun Context.tr(source: String, vararg values: Any): String {
    val language = AppLanguage.selected(this)
    val template = Messages.translation(this, language, source)
    return Messages.slot.replace(template) {
        val value = values.getOrNull(it.groupValues[1].toInt()) ?: return@replace it.value
        if (value is Number) NumberFormat.getNumberInstance(language.locale).format(value) else value.toString()
    }
}

// Transfer records keep their original messages on disk so old records and status checks
// work across language changes. Resolve those messages when presenting them to the user.
internal fun Context.transferMessage(source: String): String =
    Messages.transfer(this, AppLanguage.selected(this), source)

internal fun Context.displayBytes(value: Long): String {
    if (value < 0) return tr("Unknown size")
    return bytes(value, AppLanguage.selected(this).locale) { amount, unit -> tr("{0} $unit", amount) }
}

internal fun Context.displayPercent(value: Int): String =
    NumberFormat.getPercentInstance(AppLanguage.selected(this).locale).format(value / 100.0)

private object Messages {
    val slot = Regex("\\{([0-9]+)\\}")
    private val catalogs = mutableMapOf<AppLanguage, Catalog>()
    private data class Template(val pattern: Regex, val order: List<Int>, val translation: String)
    private data class Catalog(val lines: Map<String, String>, val templates: List<Template>)

    fun translation(context: Context, language: AppLanguage, source: String): String =
        if (language == AppLanguage.ENGLISH) source else catalog(context, language).lines[source] ?: source

    fun transfer(context: Context, language: AppLanguage, source: String): String {
        if (source.isEmpty() || language == AppLanguage.ENGLISH) return source
        val catalog = catalog(context, language)
        catalog.lines[source]?.let { return it }
        for (template in catalog.templates) {
            val match = template.pattern.matchEntire(source) ?: continue
            return slot.replace(template.translation) { token ->
                val index = template.order.indexOf(token.groupValues[1].toInt())
                if (index < 0) token.value else {
                    val value = match.groupValues[index + 1]
                    if (value.length < source.length) transfer(context, language, value) else value
                }
            }
        }
        return source
    }

    @Synchronized
    private fun catalog(context: Context, language: AppLanguage): Catalog = catalogs.getOrPut(language) {
        val lines = linkedMapOf<String, String>()
        val templates = mutableListOf<Template>()
        context.assets.open("languages.tsv").bufferedReader().useLines { rows ->
            rows.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { row ->
                val columns = row.split('\t')
                val storedMessage = columns[0].startsWith("~")
                val source = columns[0].removePrefix("~")
                val translated = columns[language.ordinal]
                lines[source] = translated
                if (storedMessage) {
                    val tokens = slot.findAll(source).toList()
                    val pattern = buildString {
                        var end = 0
                        for (token in tokens) {
                            append(Regex.escape(source.substring(end, token.range.first)))
                            append("(.+?)")
                            end = token.range.last + 1
                        }
                        append(Regex.escape(source.substring(end)))
                    }
                    templates += Template(Regex(pattern), tokens.map { it.groupValues[1].toInt() }, translated)
                }
            }
        }
        Catalog(lines, templates)
    }
}
