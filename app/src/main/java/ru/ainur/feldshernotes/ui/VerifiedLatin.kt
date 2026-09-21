package ru.ainur.feldshernotes.ui

import java.util.Locale

/**
 * A deliberately small, SOURCE-CHECKED Russian INN -> pharmaceutical Latin dictionary.
 * NO algorithmic transliteration or automatic genitive-form generation. Trade names
 * and multi-component drugs must never inherit the Latin of a different preparation.
 * Drug register is a separate snapshot and does not itself contain reliable Latin.
 */
internal data class LatinName(
    val innRu: String,
    val nominative: String,
    val genitive: String,
    val sourceUrl: String,
    val extraSearch: List<String> = emptyList()
)

internal object VerifiedLatin {
    private val entries = listOf(
        LatinName("Урапидил", "Urapidilum", "Urapidili", "https://www.rlsnet.ru/active-substance/urapidil-1092", listOf("Urapidil", "Эбрантил", "Эрастил")),
        LatinName("Бисопролол", "Bisoprololum", "Bisoprololi", "https://www.rlsnet.ru/active-substance/bisoprolol-678", listOf("Bisoprolol", "Конкор")),
        LatinName("Натрия хлорид", "Natrii chloridum", "Natrii chloridi", "https://www.rlsnet.ru/active-substance/natriya-xlorid-416", listOf("Sodium chloride", "физраствор", "физиологический раствор", "sol natrii chloridi", "solutio natrii chloridi")),
        LatinName("Метоклопрамид", "Metoclopramidum", "Metoclopramidi", "https://www.rlsnet.ru/active-substance/metoklopramid-186", listOf("Metoclopramide", "Cerucal", "Церукал")),
        LatinName("Эпинефрин", "Epinephrinum", "Epinephrini", "https://www.rlsnet.ru/active-substance/epinefrin-1131", listOf("Адреналин", "Adrenalin", "Epinephrine")),
        LatinName("Атропин", "Atropinum", "Atropini", "https://www.rlsnet.ru/active-substance/atropin-1217", listOf("Atropine")),
        LatinName("Дексаметазон", "Dexamethasonum", "Dexamethasoni", "https://www.rlsnet.ru/active-substance/deksametazon-478", listOf("Dexamethasone")),
        LatinName("Дротаверин", "Drotaverinum", "Drotaverini", "https://www.rlsnet.ru/active-substance/drotaverin-821", listOf("Drotaverine", "Но-шпа", "No-spa")),
        LatinName("Фуросемид", "Furosemidum", "Furosemidi", "https://www.rlsnet.ru/active-substance/furosemid-199", listOf("Furosemide", "Лазикс")),
        LatinName("Кеторолак", "Ketorolacum", "Ketorolaci", "https://www.rlsnet.ru/active-substance/ketorolak-547", listOf("Ketorolac", "Кетанов", "Кеторол")),
        LatinName("Магния сульфат", "Magnesii sulfas", "Magnesii sulfatis", "https://www.rlsnet.ru/active-substance/magniya-sulfat-1442", listOf("Magnesium sulfate", "магнезия")),
        LatinName("Преднизолон", "Prednisolonum", "Prednisoloni", "https://www.rlsnet.ru/active-substance/prednizolon-297", listOf("Prednisolone")),
        LatinName("Парацетамол", "Paracetamolum", "Paracetamoli", "https://www.rlsnet.ru/active-substance/paracetamol-63", listOf("Paracetamol", "Acetaminophen")),
        LatinName("Диазепам", "Diazepamum", "Diazepami", "https://www.rlsnet.ru/active-substance/diazepam-112", listOf("Diazepam", "Реланиум", "Сибазон")),
        LatinName("Сальбутамол", "Salbutamolum", "Salbutamoli", "https://www.rlsnet.ru/active-substance/salbutamol-101", listOf("Salbutamol", "Вентолин"))
    )
    private fun normalize(raw: String): String = ReferenceSearch.normalize(raw)
    private val byInn = entries.associateBy { normalize(it.innRu) }
    fun byExactInn(inn: String): LatinName? = byInn[normalize(inn)]

    /** An unambiguous, form-specific name used only for the curated 0.9% entry. */
    fun exactSaline09Query(rawQuery: String): Boolean {
        val q = normalize(rawQuery)
        return q == normalize("Sol. Natrii chloridi 0,9%") ||
            q == normalize("Solutio Natrii chloridi 0,9%")
    }

    /** Maps ONLY explicitly checked Latin/colloquial names, never a conflicting concentration. */
    fun russianInnForSearch(rawQuery: String): String? {
        val q = normalize(rawQuery)
        if (q.length < 4) return null
        // "Sol. Natrii chloridi 10%" MUST NOT be treated as the 0.9% reference.
        if (q.startsWith("sol natrii chloridi ") || q.startsWith("solutio natrii chloridi ")) {
            if (!exactSaline09Query(rawQuery)) return null
        }
        // Clinical shorthand with strength is accepted, but ONLY when the Latin drug name
        // is explicitly present. e.g. "Sol. Natrii chloridi 0,9%" -> sodium chloride.
        return entries.firstOrNull { e ->
            val keys = listOf(e.nominative, e.genitive) + e.extraSearch
            keys.any { key ->
                val k = normalize(key)
                k.length >= 4 && (k == q || k.startsWith(q) ||
                    (q.startsWith(k) && (q.length == k.length || q[k.length] == ' ')))
            }
        }?.innRu
    }

    /** A form-specific Latin LINE, not a prescription and not a therapeutic dose. */
    fun solutionLine(inn: String, form: String): String? {
        if (normalize(inn) != normalize("Натрия хлорид")) return null
        val f = form.lowercase(Locale.ROOT).replace(',', '.')
        // A GRLS row can bundle different concentrations/forms. Never emit one Latin
        // line for such a combined entry: it would misleadingly label other forms.
        if (';' in f || "спрей" in f || "назаль" in f) return null
        val isAqueousSolution = ("раствор для инфузий" in f || "раствор для инъекций" in f ||
            "растворитель для приготовления лекарственных форм для инъекций" in f)
        val hasStrength = Regex("(^|[^\\d])0\\.9\\s*%|(^|[^\\d])9\\s*мг/мл").containsMatchIn(f)
        return if (isAqueousSolution && hasStrength) "Sol. Natrii chloridi 0,9%" else null
    }
}
