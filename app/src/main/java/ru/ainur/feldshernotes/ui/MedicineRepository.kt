package ru.ainur.feldshernotes.ui

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.io.File

internal data class MedicineForm(val label: String, val brands: List<String>)

/** An immutable derived index, physically separate from personal records. */
internal class MedicineRepository(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.noBackupFilesDir, "reference/medicines_v1.db")
    private var ready = false
    private fun valid(f: File): Boolean = runCatching {
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val version = db.rawQuery("SELECT value FROM meta WHERE key='version'", null).use { it.moveToFirst() && it.getString(0) == "1" }
            val count = db.rawQuery("SELECT COUNT(*) FROM medicines", null).use { it.moveToFirst() && it.getInt(0) > 1000 }
            version && count
        }
    }.getOrDefault(false)
    @Synchronized private fun connect(): SQLiteDatabase {
        if (!ready) {
            if (!file.isFile || !valid(file)) {
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, "medicines_v1.tmp")
                try {
                    app.assets.open("reference/medicines_v1.db").use { input -> temp.outputStream().use { input.copyTo(it) } }
                    check(valid(temp)) { "Не удалось проверить справочник. Повторите открытие." }
                    check(temp.renameTo(file)) { "Недостаточно места для справочника." }
                } finally { temp.delete() }
            }
            ready = true
        }
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    }
    private fun strings(raw: String): List<String> = JSONArray(raw).let { a -> List(a.length()) { a.getString(it) } }
    private fun row(c: Cursor): ReferenceItem {
        val title = c.getString(1)
        val guide = ClinicalGuides.find(title)
        val latin = VerifiedLatin.byExactInn(title)
        val forms = JSONArray(c.getString(5)).let { a -> List(a.length()) { n -> a.getJSONObject(n).let { MedicineForm(it.getString("label"), strings(it.getJSONArray("brands").toString())) } } }
        return ReferenceItem(id=c.getString(0), kind=ReferenceKind.MEDICINE, title=title, inn=title,
            tradeNames=strings(c.getString(3)), latin=guide?.latin ?: latin?.nominative.orEmpty(),
            prescriptionLatin=guide?.genitive ?: latin?.genitive.orEmpty(),
            description=guide?.purpose ?: c.getString(6), forms=forms.joinToString("\n") { it.label },
            dosing=guide?.administration.orEmpty(), precautions=guide?.cautions.orEmpty(),
            notes=guide?.dilution.orEmpty(), source=if(guide != null) "Краткая справка по инструкции / фармакологическому справочнику; формы и торговые названия — ГРЛС" else "ГРЛС: формы и фармакотерапевтическая группа",
            sourceUrl=guide?.url ?: "https://grls.minzdrav.gov.ru/grls.aspx", checkedOn=if(guide != null) "21.09.2026" else "18.09.2026",
            medicineForms=forms, clinical=guide != null)
    }
    @Synchronized fun search(raw: String, limit: Int = 40): List<ReferenceItem> {
        val q = ReferenceSearch.normalize(raw)
        if(q.length < 2) return emptyList()
        val mapped = ClinicalGuides.aliasInn(q) ?: VerifiedLatin.russianInnForSearch(raw)
        val terms = q.split(' ').filter(String::isNotBlank)
        val where = terms.joinToString(" AND ") { "(title_search LIKE ? OR search LIKE ?)" }
        val args = terms.flatMap { listOf("%$it%", "%$it%") }.toMutableList()
        val extra = if(mapped != null) " OR title_search=?" else ""
        if(mapped != null) args.add(ReferenceSearch.normalize(mapped))
        args.addAll(listOf(q, ReferenceSearch.normalize(mapped.orEmpty()), "$q%", "%$q%"))
        return connect().use { db -> db.rawQuery("""SELECT * FROM medicines WHERE ($where)$extra
            ORDER BY CASE WHEN title_search=? THEN 0 WHEN title_search=? THEN 1 WHEN title_search LIKE ? THEN 2 WHEN search LIKE ? THEN 3 ELSE 4 END,
            length(title), title LIMIT ${limit.coerceIn(1,120)}""", args.toTypedArray()).use { c -> buildList { while(c.moveToNext()) add(row(c)) } } }
    }
    @Synchronized fun item(id: String): ReferenceItem? = connect().use { db ->
        val legacy = id.removePrefix("drug-grls-").toLongOrNull()
        val sql = if(legacy != null) "SELECT * FROM medicines WHERE id=(SELECT medicine_id FROM legacy WHERE id=?)" else "SELECT * FROM medicines WHERE id=?"
        db.rawQuery(sql,arrayOf(legacy?.toString() ?: id)).use { if(it.moveToFirst()) row(it) else null }
    }
    fun favorites(ids: Set<String>) = ids.filter { it.startsWith("med-") || it.startsWith("drug-grls-") }.mapNotNull { item(it) }.distinctBy { it.id }
    @Synchronized fun quick(): List<ReferenceItem> = connect().use { db ->
        val names = ClinicalGuides.quick.map(ReferenceSearch::normalize)
        db.rawQuery("SELECT * FROM medicines WHERE title_search IN (${names.joinToString { "?" }})",names.toTypedArray()).use { c -> buildList { while(c.moveToNext()) add(row(c)) }.sortedBy { ClinicalGuides.quick.indexOf(it.title) } }
    }
}
