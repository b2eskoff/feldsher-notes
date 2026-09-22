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
    /** Search real codes and explicit synonym families directly in bundled SQLite. */
    private fun searchSqlCodes(prefixes: List<String>, limit: Int, terminalOnly: Boolean): List<ReferenceItem> = connect().use { db ->
        val where = prefixes.joinToString(" OR ") { "(UPPER(icd.code)=? OR UPPER(icd.code) LIKE ?)" }
        val args = prefixes.flatMap { listOf(it, if (it.length == 3) "$it.%" else "$it%") }
        val leaf = if (terminalOnly) "AND NOT EXISTS (SELECT 1 FROM icd child WHERE child.parent_id=icd.id AND child.active=1)" else ""
        rows(db, """SELECT icd.id,icd.code,icd.title FROM icd WHERE icd.active=1 AND ($where) $leaf
            ORDER BY LENGTH(icd.code),icd.code,icd.id LIMIT ?""",
            (args+limit.coerceIn(1,200).toString()).toTypedArray())
    }
    /** Whole-phrase matching. Editor callers exclude every node with active children. */
    @Synchronized fun search(input: String, limit: Int = 120, terminalOnly: Boolean = false): List<ReferenceItem> {
        val raw=input.trim().removePrefix("!").trim()
        if (Regex("^[A-Za-zА-Яа-я][0-9]{1,2}(?:[.,][0-9]{0,2})?$").matches(raw)) {
            val letters=mapOf('И' to 'I','А' to 'A','В' to 'B','С' to 'C','Е' to 'E','Н' to 'H','К' to 'K','М' to 'M','О' to 'O','Р' to 'P','Т' to 'T','Х' to 'X')
            val first=raw.first().uppercaseChar()
            val code="${letters[first] ?: first}${raw.drop(1).replace(',','.')}"
            return searchSqlCodes(listOf(code),limit,terminalOnly)
        }
        val q = IcdQuery.searchPhrase(raw)
        val synonyms=listOf("гипертония","гипертоническая болезнь","гипертензия","гипертензивная болезнь","артериальная гипертензия","гб")
        if ((q.length>=4 || q=="гб") && synonyms.any { it==q || it.startsWith(q) })
            return searchSqlCodes(listOf("I10","I11","I12","I13","I15"),limit,terminalOnly)
                .map { it.copy(searchHint="Выбери клинически подходящий вариант") }
        if (q.length>=9 && "холецистопанкреатит".startsWith(q))
            return searchSqlCodes(listOf("K81","K85","K86.1","K80.0","K80.1"),limit,terminalOnly)
                .map { it.copy(searchHint="Возможный компонент сочетанной формулировки") }
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
                e.title==q -> 900
                e.aliases.contains(q) -> 700
                e.title.startsWith(q) -> 500
                e.title.contains(q) -> 450
                distance>1 -> return@mapNotNull null
                distance==1 -> 100
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
