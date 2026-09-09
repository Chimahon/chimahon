package chimahon.dictionary.arabic

import chimahon.dictionary.DeinflectionResult
import chimahon.dictionary.Deinflector
import chimahon.dictionary.Rule
import chimahon.dictionary.RuleDeinflector

object ArabicDeinflector : Deinflector {

    private val arabicLetters = "[\u0620-\u065F\u066E-\u06D3\u06D5\u06EE\u06EF\u06FA-\u06FC\u06FF]"

    private val directObjectPronouns1st = listOf("ني", "نا")
    private val directObjectPronouns2nd = listOf("ك", "كما", "كم", "كن")
    private val directObjectPronouns3rd = listOf("ه", "ها", "هما", "هم", "هن")
    private val directObjectPronouns = directObjectPronouns1st + directObjectPronouns2nd + directObjectPronouns3rd
    private val possessivePronouns = listOf("ي", "نا") + directObjectPronouns2nd + directObjectPronouns3rd
    private val nonAssimilatingPossessivePronouns = listOf("نا") + directObjectPronouns2nd + directObjectPronouns3rd

    private fun prefixInflection(
        inflectedPrefix: String,
        deinflectedPrefix: String,
        conditionsIn: Set<String>,
        conditionsOut: Set<String>,
        initialStemSegment: String = "",
    ) = Rule.Prefix(
        inflectedPrefix,
        deinflectedPrefix,
        conditionsIn,
        conditionsOut,
        Regex("^${Regex.escape(inflectedPrefix)}$initialStemSegment"),
    )

    private fun suffixInflection(
        inflectedSuffix: String,
        deinflectedSuffix: String,
        conditionsIn: Set<String>,
        conditionsOut: Set<String>,
        finalStemSegment: String = "",
    ) = Rule.Suffix(
        inflectedSuffix,
        deinflectedSuffix,
        conditionsIn,
        conditionsOut,
        Regex("$finalStemSegment${Regex.escape(inflectedSuffix)}$"),
    )

    private fun conditionalPrefixInflection(
        inflectedPrefix: String,
        deinflectedPrefix: String,
        initialStemSegment: String,
        conditionsIn: Set<String>,
        conditionsOut: Set<String>,
    ) = Rule.Prefix(
        inflectedPrefix,
        deinflectedPrefix,
        conditionsIn,
        conditionsOut,
        Regex("^${Regex.escape(inflectedPrefix)}$initialStemSegment"),
    )

    private fun conditionalSuffixInflection(
        inflectedSuffix: String,
        deinflectedSuffix: String,
        finalStemSegment: String,
        conditionsIn: Set<String>,
        conditionsOut: Set<String>,
    ) = Rule.Suffix(
        inflectedSuffix,
        deinflectedSuffix,
        conditionsIn,
        conditionsOut,
        Regex("$finalStemSegment${Regex.escape(inflectedSuffix)}$"),
    )

    private fun sandwichInflection(
        inflectedPrefix: String,
        deinflectedPrefix: String,
        inflectedSuffix: String,
        deinflectedSuffix: String,
        conditionsIn: Set<String>,
        conditionsOut: Set<String>,
        initialStemSegment: String = "",
        finalStemSegment: String = "",
    ): Rule {
        if (inflectedSuffix.isEmpty() && deinflectedSuffix.isEmpty()) {
            return prefixInflection(
                inflectedPrefix = inflectedPrefix,
                deinflectedPrefix = deinflectedPrefix,
                conditionsIn = conditionsIn,
                conditionsOut = conditionsOut,
                initialStemSegment = initialStemSegment,
            )
        }

        if (inflectedPrefix.isEmpty() && deinflectedPrefix.isEmpty()) {
            return suffixInflection(
                inflectedSuffix = inflectedSuffix,
                deinflectedSuffix = deinflectedSuffix,
                conditionsIn = conditionsIn,
                conditionsOut = conditionsOut,
                finalStemSegment = finalStemSegment,
            )
        }

        return Rule.Sandwich(
            inflectedPrefix = inflectedPrefix,
            deinflectedPrefix = deinflectedPrefix,
            inflectedSuffix = inflectedSuffix,
            deinflectedSuffix = deinflectedSuffix,
            conditionsIn = conditionsIn,
            conditionsOut = conditionsOut,
            isInflected = Regex(
                "^${Regex.escape(inflectedPrefix)}$initialStemSegment$arabicLetters+$finalStemSegment${Regex.escape(inflectedSuffix)}$",
            ),
        )
    }

    private fun getImperfectPrefixes(prefix: String, includeLiPrefix: Boolean = true): List<String> {
        val prefixes = mutableListOf(prefix, "و$prefix", "ف$prefix", "س$prefix", "وس$prefix", "فس$prefix")
        if (includeLiPrefix) prefixes += listOf("ل$prefix", "ول$prefix", "فل$prefix")
        return prefixes
    }

    private fun getImperfectRules(
        inflectedPrefix: String,
        deinflectedPrefix: String,
        inflectedSuffix: String,
        deinflectedSuffix: String,
        attachedSuffix: String = inflectedSuffix,
        attachesTo1st: Boolean = true,
        attachesTo2nd: Boolean = true,
        includeLiPrefix: Boolean = true,
        initialStemSegment: String = "",
        finalStemSegment: String = "",
    ): List<Rule> {
        val stemSegments = initialStemSegment to finalStemSegment
        val rules = mutableListOf<Rule>()

        getImperfectPrefixes(inflectedPrefix, includeLiPrefix).forEach { pre ->
            rules += sandwichInflection(
                inflectedPrefix = pre,
                deinflectedPrefix = deinflectedPrefix,
                inflectedSuffix = inflectedSuffix,
                deinflectedSuffix = deinflectedSuffix,
                conditionsIn = setOf("iv_p"),
                conditionsOut = setOf("iv"),
                initialStemSegment = stemSegments.first,
                finalStemSegment = stemSegments.second,
            )

            if (attachesTo1st) {
                directObjectPronouns1st.forEach { p ->
                    rules += sandwichInflection(
                        inflectedPrefix = pre,
                        deinflectedPrefix = deinflectedPrefix,
                        inflectedSuffix = attachedSuffix + p,
                        deinflectedSuffix = deinflectedSuffix,
                        conditionsIn = setOf("iv_p"),
                        conditionsOut = setOf("iv"),
                        initialStemSegment = stemSegments.first,
                        finalStemSegment = stemSegments.second,
                    )
                }
            }

            if (attachesTo2nd) {
                directObjectPronouns2nd.forEach { p ->
                    rules += sandwichInflection(
                        inflectedPrefix = pre,
                        deinflectedPrefix = deinflectedPrefix,
                        inflectedSuffix = attachedSuffix + p,
                        deinflectedSuffix = deinflectedSuffix,
                        conditionsIn = setOf("iv_p"),
                        conditionsOut = setOf("iv"),
                        initialStemSegment = stemSegments.first,
                        finalStemSegment = stemSegments.second,
                    )
                }
            }

            directObjectPronouns3rd.forEach { p ->
                rules += sandwichInflection(
                    inflectedPrefix = pre,
                    deinflectedPrefix = deinflectedPrefix,
                    inflectedSuffix = attachedSuffix + p,
                    deinflectedSuffix = deinflectedSuffix,
                    conditionsIn = setOf("iv_p"),
                    conditionsOut = setOf("iv"),
                    initialStemSegment = stemSegments.first,
                    finalStemSegment = stemSegments.second,
                )
            }
        }

        if (deinflectedPrefix.isEmpty()) {
            rules += getImperfectRules(
                inflectedPrefix = inflectedPrefix,
                deinflectedPrefix = "أ",
                inflectedSuffix = inflectedSuffix,
                deinflectedSuffix = deinflectedSuffix,
                attachedSuffix = attachedSuffix,
                attachesTo1st = attachesTo1st,
                attachesTo2nd = attachesTo2nd,
                includeLiPrefix = includeLiPrefix,
                initialStemSegment = initialStemSegment,
                finalStemSegment = finalStemSegment,
            )
            rules += getImperfectRules(
                inflectedPrefix = inflectedPrefix,
                deinflectedPrefix = "ا",
                inflectedSuffix = inflectedSuffix,
                deinflectedSuffix = deinflectedSuffix,
                attachedSuffix = attachedSuffix,
                attachesTo1st = attachesTo1st,
                attachesTo2nd = attachesTo2nd,
                includeLiPrefix = includeLiPrefix,
                initialStemSegment = initialStemSegment,
                finalStemSegment = finalStemSegment,
            )
        }

        return rules
    }

    private val rules: List<Rule> = buildList {
        addAll(listOf("و", "ف").map { prefixInflection(it, "", setOf("n_wa"), setOf("n")) })
        addAll(listOf("ب", "وب", "فب").map { prefixInflection(it, "", setOf("n_bi"), setOf("n")) })
        addAll(listOf("ك", "وك", "فك").map { prefixInflection(it, "", setOf("n_ka"), setOf("n")) })
        addAll(listOf("ل", "ول", "فل").map { prefixInflection(it, "", setOf("n_li"), setOf("n")) })
        addAll(listOf("ال", "وال", "فال").map { prefixInflection(it, "", setOf("n_al"), setOf("n")) })
        addAll(listOf("بال", "وبال", "فبال").map { prefixInflection(it, "", setOf("n_bi_al"), setOf("n")) })
        addAll(listOf("كال", "وكال", "فكال").map { prefixInflection(it, "", setOf("n_ka_al"), setOf("n")) })
        addAll(listOf("لل", "ولل", "فلل").map { conditionalPrefixInflection(it, "", "(?!ل)", setOf("n_lil"), setOf("n")) })
        addAll(listOf("لل", "ولل", "فلل").map { prefixInflection(it, "ل", setOf("n_li_al"), setOf("n")) })

        addAll(nonAssimilatingPossessivePronouns.map { suffixInflection(it, "", setOf("n_s"), setOf("n_indef", "n")) })
        add(conditionalSuffixInflection("ي", "", "(?<!ي)", setOf("n_s"), setOf("n_indef", "n")))
        add(suffixInflection("ة", "", setOf("n_s"), setOf("n_p", "n")))
        possessivePronouns.forEach {
            add(suffixInflection("ت$it", "", setOf("n_s"), setOf("n_indef", "n")))
            add(suffixInflection("ت$it", "ة", setOf("n_s"), setOf("n_indef", "n")))
        }

        addAll(listOf("ا", "اً", "ًا").map { suffixInflection(it, "", setOf("n_s"), setOf("n_wa", "n")) })
        add(suffixInflection("ان", "", setOf("n_s"), setOf("n_nom", "n")))
        add(suffixInflection("آن", "أ", setOf("n_s"), setOf("n_nom", "n")))

        add(suffixInflection("ا", "", setOf("n_s"), setOf("n_nom_indef", "n")))
        add(suffixInflection("آ", "أ", setOf("n_s"), setOf("n_nom_indef", "n")))
        possessivePronouns.forEach { add(suffixInflection("ا$it", "", setOf("n_s"), setOf("n_nom_indef", "n"))) }
        possessivePronouns.forEach { add(suffixInflection("آ$it", "أ", setOf("n_s"), setOf("n_nom_indef", "n"))) }

        add(suffixInflection("ين", "", setOf("n_s"), setOf("n_p", "n")))
        add(suffixInflection("ي", "", setOf("n_s"), setOf("n_indef", "n")))
        nonAssimilatingPossessivePronouns.forEach { add(suffixInflection("ي$it", "", setOf("n_s"), setOf("n_indef", "n"))) }

        add(suffixInflection("تان", "", setOf("n_s"), setOf("n_nom", "n")))
        add(suffixInflection("تان", "ة", setOf("n_s"), setOf("n_nom", "n")))
        add(suffixInflection("تا", "", setOf("n_s"), setOf("n_nom_indef", "n")))
        add(suffixInflection("تا", "ة", setOf("n_s"), setOf("n_nom_indef", "n")))
        possessivePronouns.forEach { add(suffixInflection("تا$it", "", setOf("n_s"), setOf("n_nom_indef", "n"))) }
        possessivePronouns.forEach { add(suffixInflection("تا$it", "ة", setOf("n_s"), setOf("n_nom_indef", "n"))) }

        add(suffixInflection("تين", "", setOf("n_s"), setOf("n_p", "n")))
        add(suffixInflection("تين", "ة", setOf("n_s"), setOf("n_p", "n")))
        add(suffixInflection("تي", "", setOf("n_s"), setOf("n_indef", "n")))
        add(suffixInflection("تي", "ة", setOf("n_s"), setOf("n_indef", "n")))
        nonAssimilatingPossessivePronouns.forEach { add(suffixInflection("تي$it", "", setOf("n_s"), setOf("n_indef", "n"))) }
        nonAssimilatingPossessivePronouns.forEach { add(suffixInflection("تي$it", "ة", setOf("n_s"), setOf("n_indef", "n"))) }

        add(suffixInflection("ات", "", setOf("n_s"), setOf("n_p", "n")))
        add(suffixInflection("ات", "ة", setOf("n_s"), setOf("n_p", "n")))
        add(suffixInflection("آت", "أ", setOf("n_s"), setOf("n_p", "n")))
        add(suffixInflection("آت", "أة", setOf("n_s"), setOf("n_p", "n")))
        possessivePronouns.forEach { add(suffixInflection("ات$it", "", setOf("n_s"), setOf("n_indef", "n"))) }
        possessivePronouns.forEach { add(suffixInflection("ات$it", "ة", setOf("n_s"), setOf("n_indef", "n"))) }
        possessivePronouns.forEach { add(suffixInflection("آت$it", "أ", setOf("n_s"), setOf("n_indef", "n"))) }
        possessivePronouns.forEach { add(suffixInflection("آت$it", "أة", setOf("n_s"), setOf("n_indef", "n"))) }

        add(suffixInflection("ون", "", setOf("n_s"), setOf("n_nom", "n")))
        add(suffixInflection("و", "", setOf("n_s"), setOf("n_nom_indef", "n")))
        nonAssimilatingPossessivePronouns.forEach { add(suffixInflection("و$it", "", setOf("n_s"), setOf("n_nom_indef", "n"))) }

        addAll(listOf("و", "ف").map { prefixInflection(it, "", setOf("pv_p"), setOf("pv_s", "pv")) })
        add(prefixInflection("ل", "", setOf("pv_p"), setOf("pv_s", "pv")))
        directObjectPronouns.forEach { add(suffixInflection(it, "", setOf("pv_s"), setOf("pv"))) }

        add(conditionalSuffixInflection("ن", "", "(?<!ن)", setOf("pv_s"), setOf("pv")))
        directObjectPronouns.forEach { add(conditionalSuffixInflection("ن$it", "", "(?<!ن)", setOf("pv_s"), setOf("pv"))) }
        add(conditionalSuffixInflection("نا", "", "(?<!ن)", setOf("pv_s"), setOf("pv")))
        directObjectPronouns2nd.forEach { add(conditionalSuffixInflection("نا$it", "", "(?<!ن)", setOf("pv_s"), setOf("pv"))) }
        directObjectPronouns3rd.forEach { add(conditionalSuffixInflection("نا$it", "", "(?<!ن)", setOf("pv_s"), setOf("pv"))) }

        directObjectPronouns.forEach { add(suffixInflection("ن$it", "ن", setOf("pv_s"), setOf("pv"))) }
        add(suffixInflection("نا", "ن", setOf("pv_s"), setOf("pv")))
        directObjectPronouns2nd.forEach { add(suffixInflection("نا$it", "ن", setOf("pv_s"), setOf("pv"))) }
        directObjectPronouns3rd.forEach { add(suffixInflection("نا$it", "ن", setOf("pv_s"), setOf("pv"))) }

        add(suffixInflection("ت", "", setOf("pv_s"), setOf("pv")))
        directObjectPronouns.forEach { add(suffixInflection("ت$it", "", setOf("pv_s"), setOf("pv"))) }
        add(conditionalSuffixInflection("تما", "", "(?<!ت)", setOf("pv_s"), setOf("pv")))
        directObjectPronouns1st.forEach { add(conditionalSuffixInflection("تما$it", "", "(?<!ت)", setOf("pv_s"), setOf("pv"))) }
        directObjectPronouns3rd.forEach { add(conditionalSuffixInflection("تما$it", "", "(?<!ت)", setOf("pv_s"), setOf("pv"))) }

        add(conditionalSuffixInflection("تم", "", "(?<!ت)", setOf("pv_s"), setOf("pv")))
        directObjectPronouns1st.forEach { add(conditionalSuffixInflection("تمو$it", "", "(?<!ت)", setOf("pv_s"), setOf("pv"))) }
        directObjectPronouns3rd.forEach { add(conditionalSuffixInflection("تمو$it", "", "(?<!ت)", setOf("pv_s"), setOf("pv"))) }
        add(conditionalSuffixInflection("تن", "", "(?<!ت)", setOf("pv_s"), setOf("pv")))
        directObjectPronouns1st.forEach { add(conditionalSuffixInflection("تن$it", "", "(?<!ت)", setOf("pv_s"), setOf("pv"))) }
        directObjectPronouns3rd.forEach { add(conditionalSuffixInflection("تن$it", "", "(?<!ت)", setOf("pv_s"), setOf("pv"))) }

        directObjectPronouns.forEach { add(suffixInflection("ت$it", "ت", setOf("pv_s"), setOf("pv"))) }
        add(suffixInflection("تما", "ت", setOf("pv_s"), setOf("pv")))
        directObjectPronouns1st.forEach { add(suffixInflection("تما$it", "ت", setOf("pv_s"), setOf("pv"))) }
        directObjectPronouns3rd.forEach { add(suffixInflection("تما$it", "ت", setOf("pv_s"), setOf("pv"))) }
        add(suffixInflection("تم", "ت", setOf("pv_s"), setOf("pv")))
        directObjectPronouns1st.forEach { add(suffixInflection("تمو$it", "ت", setOf("pv_s"), setOf("pv"))) }
        directObjectPronouns3rd.forEach { add(suffixInflection("تمو$it", "ت", setOf("pv_s"), setOf("pv"))) }
        add(suffixInflection("تن", "ت", setOf("pv_s"), setOf("pv")))
        directObjectPronouns1st.forEach { add(suffixInflection("تن$it", "ت", setOf("pv_s"), setOf("pv"))) }
        directObjectPronouns3rd.forEach { add(suffixInflection("تن$it", "ت", setOf("pv_s"), setOf("pv"))) }

        add(suffixInflection("تا", "", setOf("pv_s"), setOf("pv")))
        directObjectPronouns.forEach { add(suffixInflection("تا$it", "", setOf("pv_s"), setOf("pv"))) }
        add(suffixInflection("ا", "", setOf("pv_s"), setOf("pv")))
        directObjectPronouns.forEach { add(suffixInflection("ا$it", "", setOf("pv_s"), setOf("pv"))) }
        add(suffixInflection("آ", "أ", setOf("pv_s"), setOf("pv")))
        directObjectPronouns.forEach { add(suffixInflection("آ$it", "أ", setOf("pv_s"), setOf("pv"))) }
        add(suffixInflection("وا", "", setOf("pv_s"), setOf("pv")))
        directObjectPronouns.forEach { add(suffixInflection("و$it", "", setOf("pv_s"), setOf("pv"))) }

        addAll(getImperfectRules("ي", "", "", ""))
        addAll(getImperfectRules("ت", "", "", ""))
        addAll(getImperfectRules("ي", "", "ان", "", includeLiPrefix = false))
        addAll(getImperfectRules("ي", "", "آن", "أ", includeLiPrefix = false))
        addAll(getImperfectRules("ي", "", "ا", ""))
        addAll(getImperfectRules("ي", "", "آ", "أ"))
        addAll(getImperfectRules("ت", "", "ان", "", includeLiPrefix = false))
        addAll(getImperfectRules("ت", "", "آن", "أ", includeLiPrefix = false))
        addAll(getImperfectRules("ت", "", "ا", ""))
        addAll(getImperfectRules("ت", "", "آ", "أ"))
        addAll(getImperfectRules("ي", "", "ون", "", includeLiPrefix = false))
        addAll(getImperfectRules("ي", "", "وا", "", attachedSuffix = "و"))
        addAll(getImperfectRules("ي", "", "ن", "", finalStemSegment = "(?<!ن)"))
        addAll(getImperfectRules("ي", "", "ن", "ن"))

        addAll(getImperfectRules("ت", "", "", "", attachesTo2nd = false))
        addAll(getImperfectRules("ت", "", "ين", "", attachesTo2nd = false, includeLiPrefix = false))
        addAll(getImperfectRules("ت", "", "ي", "", attachesTo2nd = false))
        addAll(getImperfectRules("ت", "", "ان", "", attachesTo2nd = false, includeLiPrefix = false))
        addAll(getImperfectRules("ت", "", "آن", "أ", attachesTo2nd = false, includeLiPrefix = false))
        addAll(getImperfectRules("ت", "", "ا", "", attachesTo2nd = false))
        addAll(getImperfectRules("ت", "", "آ", "أ", attachesTo2nd = false))
        addAll(getImperfectRules("ت", "", "ون", "", attachesTo2nd = false, includeLiPrefix = false))
        addAll(getImperfectRules("ت", "", "وا", "", attachesTo2nd = false, attachedSuffix = "و"))
        addAll(getImperfectRules("ت", "", "ن", "", attachesTo2nd = false, finalStemSegment = "(?<!ن)"))
        addAll(getImperfectRules("ت", "", "ن", "ن", attachesTo2nd = false))

        addAll(getImperfectRules("أ", "", "", "", attachesTo1st = false))
        addAll(getImperfectRules("آ", "أ", "", "", attachesTo1st = false))
        addAll(getImperfectRules("ن", "", "", "", attachesTo1st = false))

        add(prefixInflection("و", "", setOf("cv_p"), setOf("cv_s")))
        add(prefixInflection("ف", "", setOf("cv_p"), setOf("cv_s")))
        add(prefixInflection("ا", "", setOf("cv_p"), setOf("cv_s", "cv")))
        add(prefixInflection("وا", "", setOf("cv_p"), setOf("cv_s", "cv")))
        add(prefixInflection("فا", "", setOf("cv_p"), setOf("cv_s", "cv")))

        addAll(directObjectPronouns1st.map { suffixInflection(it, "", setOf("cv_s"), setOf("cv")) })
        addAll(directObjectPronouns3rd.map { suffixInflection(it, "", setOf("cv_s"), setOf("cv")) })

        add(suffixInflection("ي", "", setOf("cv_s"), setOf("cv")))
        directObjectPronouns1st.forEach { add(suffixInflection("ي$it", "", setOf("cv_s"), setOf("cv"))) }
        directObjectPronouns3rd.forEach { add(suffixInflection("ي$it", "", setOf("cv_s"), setOf("cv"))) }

        add(suffixInflection("ا", "", setOf("cv_s"), setOf("cv")))
        directObjectPronouns1st.forEach { add(suffixInflection("ا$it", "", setOf("cv_s"), setOf("cv"))) }
        directObjectPronouns3rd.forEach { add(suffixInflection("ا$it", "", setOf("cv_s"), setOf("cv"))) }

        add(suffixInflection("وا", "", setOf("cv_s"), setOf("cv")))
        directObjectPronouns1st.forEach { add(suffixInflection("و$it", "", setOf("cv_s"), setOf("cv"))) }
        directObjectPronouns3rd.forEach { add(suffixInflection("و$it", "", setOf("cv_s"), setOf("cv"))) }

        add(suffixInflection("ن", "", setOf("cv_s"), setOf("cv")))
        directObjectPronouns1st.forEach { add(suffixInflection("ن$it", "", setOf("cv_s"), setOf("cv"))) }
        directObjectPronouns3rd.forEach { add(suffixInflection("ن$it", "", setOf("cv_s"), setOf("cv"))) }
    }

    private val deinflector = RuleDeinflector(rules)

    override fun preProcess(text: String): List<String> = ArabicTextPreprocessors.process(text)

    override fun deinflect(
        text: String,
        languageCode: String,
    ): List<DeinflectionResult> {
        return deinflector.deinflect(text)
    }
}
