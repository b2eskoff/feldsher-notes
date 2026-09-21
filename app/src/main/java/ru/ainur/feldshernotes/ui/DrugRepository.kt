package ru.ainur.feldshernotes.ui

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Offline registry of ACTUAL REGISTERED MEDICINAL PRODUCTS, not a prescribing guide.
 * The packaged snapshot is the 18 September 2026 official GRLS export.
 * The registry lists trade names, INN, registered dosage forms, registration numbers,
 * and registration status. It does NOT supply indication-/route-/age-specific dose schemes.
 * Never infer therapeutic doses, Latin prescription grammar or therapeutic interchangeability.
 */
internal class DrugRepository(context: Context) {
    private val app = context.applicationContext
    private val asset = "reference/grls_2026_09_18.db.gz"
    private val databaseFile = File(app.noBackupFilesDir, "reference/grls_2026_09_18.db")
    private val expectedVersion = "grls-2026-09-18-v1"
    private val expectedCount = 29325
    private val fields = "id,registration,trade,inn,form,group_name,holder,registered_on,expires_on,status"
    private fun clean(value: String?): String = value.orEmpty().trim().takeUnless { it == "~" || it == "-" }.orEmpty()

    @Synchronized private fun connect(): SQLiteDatabase {
        if (!databaseFile.isFile || !verify(databaseFile)) {
            databaseFile.parentFile?.mkdirs()
            val temp = File(databaseFile.parentFile, "grls_2026_09_18.db.tmp")
            try {
                temp.delete()
                val packedAsset = app.assets.list("reference").orEmpty().contains("grls_2026_09_18.db.gz")
                app.assets.open(if(packedAsset) asset else asset.removeSuffix(".gz")).use { packed ->
                    (if(packedAsset) GZIPInputStream(packed) else packed).use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                check(verify(temp)) { "Повреждена автономная база лекарственных препаратов" }
                check(temp.renameTo(databaseFile)) { "Не удалось установить офлайн-базу препаратов" }
            } finally { temp.delete() }
        }
        return SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    private fun verify(file: File): Boolean = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM meta WHERE key='version'", null).use { c ->
                c.moveToFirst() && c.getString(0) == expectedVersion
            } && db.rawQuery("SELECT COUNT(*) FROM drugs", null).use { c ->
                c.moveToFirst() && c.getInt(0) == expectedCount
            }
        }
    } catch (_: Exception) { false }

    /** Exact INN in the official export, active registrations only. No brand is inferred. */
    private fun brands(db: SQLiteDatabase, inn: String): List<String> {
        if (inn.isBlank()) return emptyList()
        return db.rawQuery(
            "SELECT DISTINCT trade FROM drugs WHERE inn=? ORDER BY trade COLLATE NOCASE",
            arrayOf(inn)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }

    private fun toItem(c: Cursor, db: SQLiteDatabase, includeBrands: Boolean = false): ReferenceItem {
        val inn = c.getString(3).orEmpty()
        val registration = c.getString(1).orEmpty()
        val name = c.getString(2).orEmpty()
        val forms = clean(c.getString(4))
        val status = c.getString(9).orEmpty()
        val latinName = VerifiedLatin.byExactInn(inn)
        val known = ReferenceCatalog.items.firstOrNull {
            it.kind == ReferenceKind.MEDICINE && ReferenceSearch.normalize(it.title) == ReferenceSearch.normalize(inn)
        }
        return ReferenceItem(
            id = "drug-grls-${c.getLong(0)}",
            kind = ReferenceKind.MEDICINE,
            title = name,
            code = registration,
            inn = inn,
            // Latin available only when independently checked for this exact INN.
            latin = latinName?.nominative ?: known?.latin.orEmpty(),
            prescriptionLatin = latinName?.genitive ?: known?.prescriptionLatin.orEmpty(),
            latinFormLine = VerifiedLatin.solutionLine(inn, forms).orEmpty(),
            tradeNames = if (includeBrands) brands(db, inn) else emptyList(),
            // Display only source-backed content; do not fill absent clinical fields with prose.
            description = clean(c.getString(5)),
            forms = forms,
            dosing = "", // NEVER reinterpret registered strength as a therapeutic dose.
            precautions = "",
            notes = "",
            referenceScope = "Регистрационное удостоверение $registration · статус: $status · " +
                "держатель: ${c.getString(6).orEmpty()} · дата регистрации: ${c.getString(7).orEmpty()} · " +
                "срок действия по выгрузке: ${c.getString(8).orEmpty().ifBlank { "не указан" }}",
            source = "ГРЛС Минздрава РФ, выгрузка от 18.09.2026" +
                (latinName?.let { "; латинское МНН: РЛС (${it.sourceUrl})" } ?: ""),
            sourceUrl = "https://grls.minzdrav.gov.ru/grls.aspx",
            checkedOn = "18.09.2026"
        )
    }

    /** Prefer a literal product/trade match. Translate reviewed Latin/colloquial terms
     * to an INN only when there are no direct registration-name hits. This prevents
     * a "Церукал" query from burying the exact registered product among every brand.
     */
    @Synchronized fun search(rawQuery: String, limit: Int = 65): List<ReferenceItem> {
        if (VerifiedLatin.exactSaline09Query(rawQuery)) return emptyList()
        val literal = ReferenceSearch.normalize(rawQuery)
        if (literal.isBlank()) return emptyList()
        val mapped = VerifiedLatin.russianInnForSearch(rawQuery)?.let(ReferenceSearch::normalize)
        val count = limit.coerceIn(1, 120)
        return connect().use { db ->
            fun lookup(q: String): List<ReferenceItem> {
                val like = "%$q%"
                val prefix = "$q%"
                return db.rawQuery("""
                    SELECT $fields FROM drugs
                    WHERE trade_search LIKE ? OR inn_search LIKE ? OR registration_search LIKE ?
                    ORDER BY CASE
                      WHEN trade_search = ? THEN 0
                      WHEN inn_search = ? THEN 1
                      WHEN registration_search = ? THEN 2
                      WHEN trade_search LIKE ? THEN 3
                      WHEN inn_search LIKE ? THEN 4
                      WHEN registration_search LIKE ? THEN 5
                      ELSE 6 END,
                      LENGTH(trade), trade, registration
                    LIMIT $count
                """.trimIndent(), arrayOf(like,like,like,q,q,q,prefix,prefix,prefix)).use { c ->
                    buildList { while (c.moveToNext()) add(toItem(c,db)) }
                }
            }
            val direct = lookup(literal)
            if (direct.isNotEmpty() || mapped.isNullOrBlank() || mapped == literal) direct
            else lookup(mapped)
        }
    }

    @Synchronized fun item(id: String): ReferenceItem? {
        val n = id.removePrefix("drug-grls-").toLongOrNull() ?: return null
        return connect().use { db ->
            db.rawQuery("SELECT $fields FROM drugs WHERE id=? LIMIT 1", arrayOf(n.toString())).use { c ->
                if (c.moveToFirst()) toItem(c,db, includeBrands = true) else null
            }
        }
    }

    @Synchronized fun favorites(ids: Set<String>): List<ReferenceItem> = ids.asSequence()
        .filter { it.startsWith("drug-grls-") }.take(120).mapNotNull { item(it) }.toList()

    @Synchronized fun count(): Int = connect().use { db ->
        db.rawQuery("SELECT COUNT(*) FROM drugs", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }
}
