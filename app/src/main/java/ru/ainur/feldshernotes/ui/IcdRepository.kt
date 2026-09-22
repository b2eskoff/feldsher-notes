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
    private data class Entry(val item: ReferenceItem,val leaf: Boolean,val title: String,val aliases: String,val words: List<String>)
    companion object {
        private val installLock=Any()
        @Volatile private var index: List<Entry>?=null
    }
    private val app = context.applicationContext
    private val databaseFile = File(app.noBackupFilesDir, "reference/icd10_ru_2_27.db")
    private val sourceAsset = "reference/icd10_ru_2_27.db"
    private val meta = "МКБ-10 РФ · ФРНСИ · версия 2.27 (02.09.2024)"
    private val origin = "https://nsi.rosminzdrav.ru/#!/refbook"
    private val acceptedVersion = "2.27"

    private var ready = false
    @Synchronized private fun connect(): SQLiteDatabase = synchronized(installLock) {
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
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY)
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

    private fun entries(): List<Entry> = index ?: synchronized(installLock) {
        index ?: connect().use { db ->
            val parents=db.rawQuery("SELECT DISTINCT parent_id FROM icd WHERE active=1 AND parent_id IS NOT NULL",null).use { c -> buildSet {while(c.moveToNext()) add(c.getLong(0))} }
            rows(db,"SELECT id,code,title FROM icd WHERE active=1").map { item ->
                val alias=IcdLanguage.aliases(item.code)
                Entry(item,item.id.removePrefix("icd-nsi-").toLong() !in parents,ReferenceSearch.normalize(item.title),ReferenceSearch.normalize(alias),IcdLanguage.words(item.title+" "+alias))
            }.also { index=it }
        }
    }
    /** Whole-phrase matching. Editor callers exclude every node with active children. */
    @Synchronized fun search(input: String, limit: Int = 120, terminalOnly: Boolean = false): List<ReferenceItem> {
        val raw=input.trim().removePrefix("!")
        val q = IcdQuery.searchPhrase(raw)
        val combined=IcdQuery.combinedCodes(raw)
        val hasStage=IcdQuery.hasStage(raw)
        if (q.isBlank()) return emptyList()
        val codeQuery=Regex("^[a-z][0-9].*").matches(q)
        val words=IcdLanguage.words(q)
        if(!codeQuery && words.isEmpty()) return emptyList()
        return entries().asSequence().filter { !terminalOnly || it.leaf }.mapNotNull { e ->
            val code=ReferenceSearch.normalize(e.item.code)
            val distance=if(codeQuery) 0 else IcdLanguage.distance(words,e.words)
            val score=when {
                codeQuery -> if(code==q) 1000 else if(code.startsWith(q)) 800 else return@mapNotNull null
                combined.isNotEmpty() -> if(combined.any {IcdLanguage.inFamily(e.item.code,it)}) 200 else return@mapNotNull null
                distance>1 -> return@mapNotNull null
                distance==1 -> 100
                e.title==q -> 900
                e.aliases.contains(q) -> 700
                e.title.startsWith(q) -> 500
                else -> 300
            }
            val common=if(e.item.code in setOf("I10","I11.9","G93.0","J18.9","E11.9","E10.9")) 30 else 0
            val hint=when {
                combined.isNotEmpty() -> "Сочетанная формулировка: это один из компонентов, не полный диагноз"
                hasStage -> "Стадия/степень не определяет код МКБ — уточни поражение органов"
                distance==1 -> "Похожее написание — проверь диагноз и уточнения"
                !codeQuery && !IcdLanguage.matches(words,IcdLanguage.words(e.item.title)) -> "Найдено по синониму — выбери нужное уточнение"
                else -> ""
            }
            e.copy(item=e.item.copy(searchHint=hint)) to (score+common+if(e.leaf) 10 else 0)
        }.sortedWith(compareByDescending<Pair<Entry,Int>> {it.second}.thenBy {it.first.item.title.length}.thenBy {it.first.item.code})
            .take(limit.coerceIn(1,200)).map {it.first.item}.toList()
    }

    @Synchronized fun chapters(): List<ReferenceItem> = connect().use { db ->
        rows(db, "SELECT id,code,title FROM icd WHERE parent_id IS NULL AND active=1 ORDER BY id")
    }
    @Synchronized fun children(parent: Long): List<ReferenceItem> = connect().use { db ->
        rows(db, "SELECT id,code,title FROM icd WHERE parent_id=? AND active=1 ORDER BY code,id LIMIT 300", arrayOf(parent.toString()))
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
