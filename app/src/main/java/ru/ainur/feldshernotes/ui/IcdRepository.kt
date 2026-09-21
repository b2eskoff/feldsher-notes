package ru.ainur.feldshernotes.ui

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * ICD-10 classification text is an immutable, locally bundled reference database.
 * It never touches the user's Room call database or protected document files.
 * Codes/titles are NOT clinical guidelines or automated diagnosis suggestions.
 */
internal class IcdRepository(context: Context) {
    private val app = context.applicationContext
    private val databaseFile = File(app.noBackupFilesDir, "reference/icd10_ru_2_27.db")
    private val sourceAsset = "reference/icd10_ru_2_27.db"
    private val meta = "МКБ-10 РФ · ФРНСИ · версия 2.27 (02.09.2024)"
    private val origin = "https://nsi.rosminzdrav.ru/#!/refbook"
    private val acceptedVersion = "2.27"

    private var ready = false
    @Synchronized private fun connect(): SQLiteDatabase {
        if (!ready && (!databaseFile.isFile || !verify(databaseFile))) {
            databaseFile.parentFile?.mkdirs()
            val temp = File(databaseFile.parentFile, "icd10_ru_2_27.db.tmp")
            try {
                app.assets.open(sourceAsset).use { input -> temp.outputStream().use { input.copyTo(it) } }
                check(verify(temp)) { "Повреждена автономная база МКБ-10" }
                check(temp.renameTo(databaseFile)) { "Не удалось установить базу МКБ-10" }
            } finally { temp.delete() }
        }
        ready = true
        return SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY)
    }
    private fun verify(file: File): Boolean = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM meta WHERE name='version'", null).use { c -> c.moveToFirst() && c.getString(0) == acceptedVersion } &&
            db.rawQuery("SELECT COUNT(*) FROM icd", null).use { c -> c.moveToFirst() && c.getInt(0) == 15038 }
        }
    } catch (_: Exception) { false }
    private fun cursorToItem(c: Cursor): ReferenceItem {
        val id = c.getInt(0)
        val code = c.getString(1)
        val name = c.getString(2)
        val curated = ReferenceCatalog.items.firstOrNull { it.kind == ReferenceKind.ICD && it.code.equals(code, true) }
        return if (curated != null) curated.copy(id = "icd-nsi-$id", title = name, code = code) else ReferenceItem(
            id = "icd-nsi-$id", kind = ReferenceKind.ICD, title = name, code = code,
            description = "",
            referenceScope = "",
            source = meta, sourceUrl = origin, checkedOn = "02.09.2024"
        )
    }
    private fun rows(db: SQLiteDatabase, sql: String, params: Array<String> = emptyArray()): List<ReferenceItem> =
        db.rawQuery(sql, params).use { c -> buildList { while (c.moveToNext()) add(cursorToItem(c)) } }

    /** Only first 120 hits are returned; 15,038 records are never composed as a single list. */
    @Synchronized fun search(input: String, limit: Int = 120): List<ReferenceItem> {
        val q = IcdQuery.normalize(input)
        if (q.isBlank()) return emptyList()
        val extraCodes = IcdQuery.relatedCodes(q)
        val extraSql = if(extraCodes.isEmpty()) "" else " OR (" + extraCodes.joinToString(" OR ") { "code = ? OR code LIKE ?" } + ")"
        val escaped = q
        val prefix = "$escaped%"
        val anywhere = "%$escaped%"
        val word = "% $escaped%"
        return connect().use { db ->
            rows(db, """
                SELECT id, code, title FROM icd
                WHERE active=1 AND (search_code LIKE ? OR search_title LIKE ? $extraSql)
                ORDER BY CASE
                  WHEN search_code = ? THEN 0
                  WHEN search_title = ? THEN 1
                  WHEN search_code LIKE ? THEN 2
                  WHEN search_title LIKE ? THEN 3
                  WHEN search_title LIKE ? THEN 4
                  ELSE 5 END, LENGTH(code), title
                LIMIT ${limit.coerceIn(1, 200)}
            """.trimIndent(), (listOf(anywhere, anywhere) + extraCodes.flatMap { listOf(it, "$it.%") } + listOf(q, q, prefix, prefix, word)).toTypedArray())
        }
    }

    @Synchronized fun chapters(): List<ReferenceItem> = connect().use { db ->
        rows(db, "SELECT id,code,title FROM icd WHERE parent_id IS NULL AND active=1 ORDER BY id")
    }
    @Synchronized fun children(parent: Long): List<ReferenceItem> = connect().use { db ->
        rows(db, "SELECT id,code,title FROM icd WHERE parent_id=? AND active=1 ORDER BY id LIMIT 300", arrayOf(parent.toString()))
    }
    @Synchronized fun item(id: String): ReferenceItem? {
        val n = id.removePrefix("icd-nsi-").toLongOrNull() ?: return null
        return connect().use { db -> rows(db,"SELECT id,code,title FROM icd WHERE id=? AND active=1 LIMIT 1", arrayOf(n.toString())).firstOrNull() }
    }
    /** Show genuine child rubrics when a broad code is opened from search or favorites. */
    @Synchronized fun childrenByCode(code: String): List<ReferenceItem> = connect().use { db ->
        rows(db, """SELECT id,code,title FROM icd WHERE active=1 AND parent_id=
            (SELECT id FROM icd WHERE code=? AND active=1 ORDER BY id LIMIT 1)
            ORDER BY id LIMIT 120""", arrayOf(code))
    }

    @Synchronized fun parentId(id: String): Long? {
        val n = id.removePrefix("icd-nsi-").toLongOrNull() ?: return null
        return connect().use { db -> db.rawQuery("SELECT parent_id FROM icd WHERE id=?",arrayOf(n.toString())).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null } }
    }
    @Synchronized fun hasChildren(id: String): Boolean {
        val n = id.removePrefix("icd-nsi-").toLongOrNull() ?: return false
        return connect().use { db -> db.rawQuery("SELECT 1 FROM icd WHERE parent_id=? AND active=1 LIMIT 1",arrayOf(n.toString())).use { it.moveToFirst() } }
    }
    @Synchronized fun favorites(ids: Set<String>): List<ReferenceItem> = ids.take(200).mapNotNull { item(it) }
}
