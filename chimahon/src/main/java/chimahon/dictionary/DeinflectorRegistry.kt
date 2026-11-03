package chimahon.dictionary

import chimahon.dictionary.arabic.ArabicDeinflector
import chimahon.dictionary.chinese.ChineseDeinflector
import chimahon.dictionary.english.EnglishDeinflector
import chimahon.dictionary.ko.KoreanDeinflector
import chimahon.dictionary.de.GermanDeinflector
import chimahon.dictionary.fr.FrenchDeinflector
import chimahon.dictionary.ru.RussianDeinflector
import chimahon.dictionary.es.SpanishDeinflector
import chimahon.dictionary.it.ItalianDeinflector

/**
 * Registry to provide the appropriate [Deinflector] for a given language code.
 */
object DeinflectorRegistry {

    fun get(languageCode: String): Deinflector? = when (languageCode) {
        "ar" -> ArabicDeinflector
        "ko" -> KoreanDeinflector
        "en" -> EnglishDeinflector
        "zh" -> ChineseDeinflector
        "de" -> GermanDeinflector
        "fr" -> FrenchDeinflector
        "ru" -> RussianDeinflector
        "es" -> SpanishDeinflector
        "it" -> ItalianDeinflector
        else -> null
    }
}
