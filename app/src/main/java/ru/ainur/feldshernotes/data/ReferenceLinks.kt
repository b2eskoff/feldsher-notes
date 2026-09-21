package ru.ainur.feldshernotes.data

import org.json.JSONArray
import org.json.JSONObject

/** Offsets refer to the visible UTF-16 text. Plain text stays readable in TXT backups. */
data class ReferenceLink(val start: Int, val end: Int, val label: String, val target: String)
object ReferenceLinks {
    fun decode(raw: String, text: String): List<ReferenceLink> = runCatching {
        val a=JSONArray(raw.ifBlank { "[]" })
        List(a.length()) { i -> a.getJSONObject(i).let { ReferenceLink(it.getInt("start"),it.getInt("end"),it.getString("label"),it.getString("target")) } }
            .filter { it.start >= 0 && it.end > it.start && it.end <= text.length && text.substring(it.start,it.end)==it.label }
            .sortedBy { it.start }
    }.getOrDefault(emptyList())
    fun encode(links: List<ReferenceLink>): String = JSONArray().apply { links.forEach { r -> put(JSONObject().put("start",r.start).put("end",r.end).put("label",r.label).put("target",r.target)) } }.toString()
    /** Shift untouched links; edited/deleted labels become ordinary text, never point elsewhere. */
    fun edit(old: String, new: String, links: List<ReferenceLink>): List<ReferenceLink> {
        if(old==new) return links
        var start=0
        while(start<minOf(old.length,new.length) && old[start]==new[start]) start++
        var oldEnd=old.length; var newEnd=new.length
        while(oldEnd>start && newEnd>start && old[oldEnd-1]==new[newEnd-1]) { oldEnd--;newEnd-- }
        return replace(links,start,oldEnd,newEnd-start)
    }
    fun replace(links: List<ReferenceLink>, start: Int, end: Int, insertedLength: Int): List<ReferenceLink> {
        val shift=insertedLength-(end-start)
        return links.mapNotNull { r -> when {
            r.end <= start -> r
            r.start >= end -> r.copy(start=r.start+shift,end=r.end+shift)
            else -> null
        } }
    }
    /** Candidate suffixes stop at a newline/punctuation or a previously inserted reference. */
    fun candidates(text: String, cursor: Int, links: List<ReferenceLink>): List<Pair<Int,String>> {
        if(cursor !in 0..text.length || links.any { cursor>it.start && cursor<=it.end }) return emptyList()
        val prefix=text.take(cursor)
        val boundary=maxOf(prefix.indexOfLast { it=='\n' || it==':' || it==';' || it=='(' || it==')' }+1,
            links.filter { it.end<=cursor }.maxOfOrNull { it.end } ?: 0)
        val tail=prefix.substring(boundary)
        val words=Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}.,%-]*").findAll(tail).toList().takeLast(5)
        return words.map { (boundary+it.range.first) to prefix.substring(boundary+it.range.first).trimEnd() }
            .filter { it.second.length>=2 && it.second.any(Char::isLetter) }
    }
}
