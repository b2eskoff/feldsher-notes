package ru.ainur.feldshernotes.ui

import java.util.Locale

/** Catalog data is packaged in APK: no network, no auto-diagnosis, no drug substitution. */
internal enum class ReferenceKind { ICD, MEDICINE }
internal data class ReferenceItem(
    val id: String,
    val kind: ReferenceKind,
    val title: String,
    val code: String = "",
    val aliases: List<String> = emptyList(),
    val relatedQueries: List<String> = emptyList(),
    val latin: String = "",
    val prescriptionLatin: String = "",
    val latinFormLine: String = "",
    val inn: String = "",
    val tradeNames: List<String> = emptyList(),
    val description: String = "",
    val symptoms: String = "",
    val dangerSigns: String = "",
    val notes: String = "",
    val forms: String = "",
    val dosing: String = "",
    val precautions: String = "",
    val source: String = "",
    val sourceUrl: String = "",
    val checkedOn: String = "",
    val referenceScope: String = "",
    val medicineForms: List<MedicineForm> = emptyList(),
    val clinical: Boolean = false,
    val searchHint: String = ""
)
internal data class ReferenceHit(val item: ReferenceItem, val score: Int, val related: Boolean)

internal object ReferenceSearch {
    fun normalize(text: String): String = text.lowercase(Locale.ROOT)
        .replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        .replace(Regex("\\s+"), " ")
    private fun score(query: String, field: String): Int {
        val name = normalize(field)
        return when {
            name.isEmpty() -> 0
            name == query -> 100
            name.startsWith(query) -> 88
            name.split(' ').any { it.startsWith(query) } -> 78
            name.contains(query) -> 66
            query.length >= 4 && query.split(' ').all { (it.length < 3 && it.all(Char::isLetter)) || name.contains(it) } -> 47
            else -> 0
        }
    }
    /** Related terms are a separately labeled suggestion and never earn an exact-name score. */
    fun find(items: List<ReferenceItem>, rawQuery: String, kind: ReferenceKind? = null): List<ReferenceHit> {
        val query = normalize(rawQuery)
        if (query.isEmpty()) return emptyList()
        return items.asSequence().filter { kind == null || it.kind == kind }.mapNotNull { item ->
            val names = listOf(item.title, item.code, item.latin, item.prescriptionLatin, item.latinFormLine, item.inn) + item.aliases + item.tradeNames
            val strong = names.maxOfOrNull { score(query, it) } ?: 0
            val related = query.length >= 5 && item.relatedQueries.any { score(query, it) >= 66 }
            // A generic term within a more specific disease title is NOT an exact diagnosis.
            // E.g. "панкреатит" must not silently become "острый панкреатит" (K85).
            val explicitlyRelated = related && item.relatedQueries.any { normalize(it) == query } &&
                !normalize(item.title).startsWith(query)
            val relatedSuggestion = related && (strong == 0 || explicitlyRelated)
            val rating = if (relatedSuggestion) 35 else maxOf(strong, if (related) 35 else 0)
            if (rating == 0) null else ReferenceHit(item, rating, relatedSuggestion)
        }.sortedWith(compareByDescending<ReferenceHit> { it.score }.thenBy { it.item.title }).toList()
    }
}

/** Small SOURCE-REVIEWED subset only. Never represent as the full ICD-10 or GRLS catalog. */
internal object ReferenceCatalog {
    val items: List<ReferenceItem> = listOf(
        ReferenceItem(
            id="icd-k81", kind=ReferenceKind.ICD, title="Холецистит", code="K81",
            relatedQueries=listOf("холецистопанкреатит", "воспаление желчного пузыря и поджелудочной железы"),
            description="Воспаление желчного пузыря. K81 — группа рубрик; конкретный код уточняется по клинической форме и правилам классификатора.",
            symptoms="Возможны боль в правом подреберье и эпигастрии, тошнота, рвота, повышение температуры. Проявления различаются при остром и хроническом течении.",
            dangerSigns="Нарастающая боль, признаки перитонита, желтуха, нестабильные жизненные показатели требуют срочной оценки и исключения осложнений и других причин острого живота.",
            notes="Учитывай сопутствующие желчные камни: для части случаев применяется иная рубрика (K80). Симптомы сами по себе не подтверждают диагноз; код уточняют после оценки клинических данных.",
            referenceScope="Группа K81, не универсальный код для любого холецистита.",
            source="Клинические рекомендации Минздрава РФ «Холецистит» (2024), код K81; классификатор ICD-10 K81.",
            sourceUrl="https://cr.minzdrav.gov.ru/", checkedOn="20.09.2026"
        ),
        ReferenceItem(
            id="icd-k85", kind=ReferenceKind.ICD, title="Острый панкреатит", code="K85",
            aliases=listOf("острое воспаление поджелудочной железы"),
            relatedQueries=listOf("панкреатит", "холецистопанкреатит", "воспаление поджелудочной железы"),
            description="Острое воспалительное поражение поджелудочной железы. K85 — группа кодов; конкретная подрубрика зависит от установленной формы и причины.",
            symptoms="Характерны выраженная боль в эпигастрии с возможной иррадиацией в спину, повторная рвота, болезненность и напряжение верхней части живота.",
            dangerSigns="Нарастающая боль, нарушение сознания, гипотензия, тахикардия, признаки гипоперфузии или перитонита требуют неотложной оценки тяжести и дифференциальной диагностики.",
            notes="K85.9 относится к неуточнённому ОСТРОМУ панкреатиту; не подменяй им любой «панкреатит» без уточнения. В сочетанных состояниях не выбирай код автоматически.",
            referenceScope="Группа K85 (острый панкреатит); не универсальный код для хронических форм.",
            source="Клинические рекомендации Минздрава РФ «Острый панкреатит», ID 903_1 (2025).",
            sourceUrl="https://cr.minzdrav.gov.ru/", checkedOn="20.09.2026"
        ),
        ReferenceItem(
            id="med-sodium-chloride-09", kind=ReferenceKind.MEDICINE, title="Натрия хлорид 0,9%",
            latin="Natrii chloridum", prescriptionLatin="Natrii chloridi",
            latinFormLine="Sol. Natrii chloridi 0,9%", inn="Натрия хлорид",
            aliases=listOf("физраствор", "физиологический раствор", "NaCl 0,9%", "sol natrii chloridi", "Solutio Natrii chloridi 0,9%", "sodium chloride"),
            description="Изотонический раствор хлорида натрия. Эта карточка относится к концентрации 0,9% (9 мг/мл), а не ко всем растворам натрия хлорида.",
            forms="0,9% раствор для инфузий и отдельно выпускаемые растворители/инъекционные формы. Форма, тара и путь введения проверяются по конкретной упаковке.",
            dosing="Объём и скорость инфузии определяются показанием, состоянием пациента, электролитами и инструкцией к конкретной форме; 0,9% — КОНЦЕНТРАЦИЯ, а не назначенная доза. Универсальной дозы или протокола СМП в этой карточке нет.",
            precautions="Учитывать риск перегрузки объёмом, гипернатриемию, гиперхлоремию, нарушения функции сердца и почек. Не все лекарственные средства совместимы с этим растворителем; сверяй инструкцию к разводимому препарату.",
            notes="«Sol. Natrii chloridi 0,9%» — краткая латинская запись НАИМЕНОВАНИЯ ФОРМЫ для медицинской документации, не готовый рецепт: объём, путь введения и назначение она не задаёт. Другие концентрации и назальные формы не подходят под эту запись.",
            referenceScope="Концентрация 0,9%, без указания производителя и индивидуальной схемы лечения.",
            source="РЛС: Натрия хлорид, латинское название вещества и инструкции к конкретным лекарственным формам.",
            sourceUrl="https://www.rlsnet.ru/drugs/natriya-xlorid-2123", checkedOn="20.09.2026"
        ),
        ReferenceItem(
            id="med-metoclopramide", kind=ReferenceKind.MEDICINE, title="Метоклопрамид",
            latin="Metoclopramidum", prescriptionLatin="Metoclopramidi", inn="Метоклопрамид (Metoclopramide)",
            tradeNames=listOf("Церукал", "Перинорм", "Метоклопрамид-ЭСКОМ"),
            aliases=listOf("Реглан", "Церукал®", "Cerucal", "Metoclopramide"),
            description="Противорвотное средство с прокинетическим действием. Приведённые ниже сведения относятся к конкретной инъекционной форме Церукал, а не ко всем препаратам с данным МНН.",
            forms="Пример по инструкции к Церукал: раствор для в/в и в/м введения, 5 мг/мл, ампула 2 мл (всего 10 мг). Таблетки, другие производители и концентрации требуют отдельной карточки формы.",
            dosing="По инструкции к указанной инъекционной форме для взрослых: разовая доза 10 мг (2 мл раствора 5 мг/мл), в/м либо в/в медленно, не менее 3 мин. В инструкции приведены схемы до 3 раз в сутки для отдельных показаний; максимальная суточная доза — 30 мг или 0,5 мг/кг (меньшее из значений). Интервал между введениями — не менее 6 ч. Это НЕ универсальный протокол СМП. Детские схемы здесь не опубликованы.",
            precautions="Важные противопоказания по инструкции: желудочно-кишечное кровотечение, механическая непроходимость/перфорация ЖКТ, эпилепсия, болезнь Паркинсона, некоторые лекарственные взаимодействия. Возможны экстрапирамидные реакции и сонливость; при почечной/печёночной недостаточности требуется коррекция дозы. Перед введением сверь показание и точную инструкцию к имеющейся ампуле.",
            notes="Известные названия в локальной базе НЕ означают одинаковые формы, концентрации и действующую регистрацию. Перечень пока не полный. «Metoclopramidi» — родительный падеж для латинской записи, не готовый рецепт.",
            referenceScope="Показан один проверенный пример формы и взрослой дозы из инструкции; полнота перечня препаратов и доз пока не обеспечена.",
            source="Инструкция по медицинскому применению «Церукал», раствор 5 мг/мл, 2 мл; РЛС (латинское название); ГРЛС (зарегистрированные торговые названия).",
            sourceUrl="https://medi.ru/instrukciya/tserukal-dlya-inektsij_5868/", checkedOn="20.09.2026"
        )
    )
}
