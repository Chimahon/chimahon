package chimahon.dictionary.ru

import java.util.Locale

object RussianTextPreprocessors {

    private val RUSSIAN_LOCALE = Locale.forLanguageTag("ru")

    fun decapitalize(text: String): List<String> {
        val lower = text.lowercase(RUSSIAN_LOCALE)
        return if (lower != text) listOf(text, lower) else listOf(text)
    }

    fun yoToE(text: String): List<String> = listOf(
        text,
        text.replace('ё', 'е').replace('Ё', 'Е'),
        text.replace('е', 'ё').replace('Е', 'Ё'),
    )

    fun removeRussianDiacritics(text: String): List<String> = listOf(
        text,
        text.replace("\u0301", ""),
    )

    fun allVariants(text: String): List<String> {
        return decapitalize(text)
            .flatMap { removeRussianDiacritics(it) }
            .flatMap { yoToE(it) }
            .distinct()
    }
}
