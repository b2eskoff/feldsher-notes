package ru.ainur.feldshernotes.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

enum class BlockKind(val label: String, val hint: String) {
    PERSON("Пол и возраст", "Только нужные подробности"),
    VITALS("Показатели", "АД, ЧСС, SpO₂ и другие"),
    CARE("Оказанная помощь", "Что было сделано"),
    MEDICINES("Препараты", "Начните название: урапидил, церукал…"),
    DIAGNOSIS("Диагноз", "Название или код МКБ. Можно написать своими словами"),
    ECG("ЭКГ", "Короткая заметка"),
    PROCEDURES("Манипуляции", "Что важно сохранить"),
    OUTCOME("Госпитализация / исход", "Чем закончился вызов"),
    NOTE("Дополнительная заметка", "Любые подробности")
}
data class NoteBlock(val id: String = UUID.randomUUID().toString(), val kind: BlockKind, val values: Map<String,String> = emptyMap()) {
    fun value(key: String) = values[key].orEmpty()
    fun withValue(key: String, value: String) = copy(values = values + (key to value))
    val hasContent get() = values.filterKeys { it != "references" }.values.any(String::isNotBlank)
}
data class CallRecord(
    val id: String = UUID.randomUUID().toString(), val date: String = LocalDate.now().toString(),
    val time: String? = null, val title: String = "", val description: String = "", val blocks: List<NoteBlock> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt
) {
    val hasContent get() = title.isNotBlank() || description.isNotBlank() || blocks.any { it.hasContent }
    val displayTitle get() = title.trim().ifBlank {
        description.trim().lineSequence().firstOrNull { it.isNotBlank() }?.take(100)
            ?: blocks.firstOrNull { it.hasContent }?.let(::blockSummary)?.take(100) ?: "Вызов"
    }
    val previewText get() = if (title.isNotBlank()) description.trim() else {
        val text = description.trim(); val first = text.lineSequence().firstOrNull().orEmpty()
        if (first.length > 100) text.drop(100).trim() else text.substringAfter('\n', "").trim()
    }
}
object RecordCodec {
    fun blocksToJson(blocks: List<NoteBlock>) = JSONArray().apply {
        blocks.forEach { put(JSONObject().put("id",it.id).put("kind",it.kind.name).put("values",JSONObject(it.values))) }
    }.toString()
    fun blocksFromJson(raw: String): List<NoteBlock> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index); val values = obj.getJSONObject("values")
            NoteBlock(obj.getString("id"),BlockKind.valueOf(obj.getString("kind")),values.keys().asSequence().associateWith { values.getString(it) })
        }
    }
    fun encode(r: CallRecord) = JSONObject().apply {
        put("id",r.id);put("date",r.date);put("time",r.time ?: JSONObject.NULL);put("title",r.title)
        put("description",r.description);put("blocks",JSONArray(blocksToJson(r.blocks)))
        put("createdAt",r.createdAt);put("updatedAt",r.updatedAt)
    }.toString()
    fun decode(raw: String) = JSONObject(raw).let {
        CallRecord(it.getString("id"),it.getString("date"),if (it.isNull("time")) null else it.getString("time"),
            it.getString("title"),it.getString("description"),blocksFromJson(it.getJSONArray("blocks").toString()),it.getLong("createdAt"),it.getLong("updatedAt"))
    }
}
val Russian: Locale = Locale.forLanguageTag("ru-RU")
fun longDate(date: String): String {
    val d = LocalDate.parse(date)
    return if (d == LocalDate.now()) "Сегодня, ${d.format(DateTimeFormatter.ofPattern("d MMMM",Russian))}"
    else d.format(DateTimeFormatter.ofPattern("d MMMM yyyy",Russian))
}
fun shortDate(date: String): String {
    val d = LocalDate.parse(date)
    return d.format(DateTimeFormatter.ofPattern(if (d.year == LocalDate.now().year) "d MMMM" else "d MMMM yyyy",Russian))
}
private fun plural(n: Int, one: String, few: String, many: String) = when {
    n % 100 in 11..14 -> many; n % 10 == 1 -> one; n % 10 in 2..4 -> few; else -> many
}
fun callCount(n: Int) = "$n ${plural(n,"вызов","вызова","вызовов")}"
fun ageLabel(age: String) = age.toIntOrNull()?.let { "$age ${plural(it,"год","года","лет")}" } ?: age
fun personSummary(b: NoteBlock) = listOfNotNull(b.value("sex").takeIf(String::isNotBlank),b.value("age").takeIf(String::isNotBlank)?.let(::ageLabel)).joinToString(", ")
val VitalFields = listOf("bp" to "АД","pulse" to "ЧСС","spo2" to "SpO₂","temperature" to "Температура","respiration" to "ЧДД","glucose" to "Глюкоза")
fun vitalParts(b: NoteBlock) = VitalFields.mapNotNull { (key,label) -> b.value(key).takeIf(String::isNotBlank)?.let {
    "$label $it" + when(key) { "spo2" -> "%"; "temperature" -> " °C"; "glucose" -> " ммоль/л"; else -> "" }
} }
fun blockSummary(b: NoteBlock) = when(b.kind) { BlockKind.PERSON -> personSummary(b); BlockKind.VITALS -> vitalParts(b).joinToString(" · "); else -> b.value("text").trim() }
/** Timed calls are latest first; untimed calls retain creation order even after edits. */
fun sortCalls(calls: List<CallRecord>) = calls.sortedWith(compareByDescending<CallRecord> { it.date }.thenBy { it.time == null }.thenByDescending { it.time }.thenBy { it.createdAt }.thenBy { it.id })
fun searchCalls(calls: List<CallRecord>, query: String): List<CallRecord> {
    fun norm(s: String) = s.lowercase(Russian).replace('ё','е')
    val words = norm(query).split(Regex("\\s+")).filter(String::isNotBlank)
    if (words.isEmpty()) return emptyList()
    return sortCalls(calls.filter { r ->
        val text = norm(listOf(r.title,r.description,r.date,LocalDate.parse(r.date).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),longDate(r.date)).plus(r.blocks.map { it.kind.label + " " + blockSummary(it) }).joinToString(" "))
        words.all(text::contains)
    })
}
fun validRecord(r: CallRecord) = runCatching {
    LocalDate.parse(r.date);r.time?.let { require(it.matches(Regex("\\d{2}:\\d{2}")));LocalTime.parse(it) };r.hasContent
}.getOrDefault(false)
