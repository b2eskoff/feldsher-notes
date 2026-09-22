package ru.ainur.feldshernotes.ui

import android.content.Context
import org.json.JSONArray

internal data class IcdSource(val title: String,val url: String,val type: String)
internal data class IcdGuide(val title: String,val codes: List<String>,val scope: String,val summary: String,val symptoms: String,val danger: String,val tactics: String,val medications: String,val sources: List<IcdSource>,val reviewed: String)
internal object IcdGuides {
    @Volatile private var cache: List<IcdGuide>?=null
    fun all(context: Context): List<IcdGuide> = cache ?: synchronized(this) {
        cache ?: context.applicationContext.assets.open("reference/icd_guides.json").bufferedReader().use {reader ->
            val array=JSONArray(reader.readText())
            List(array.length()) {i ->val o=array.getJSONObject(i);val codes=o.getJSONArray("codes");val sources=o.getJSONArray("sources")
                IcdGuide(o.getString("title"),List(codes.length()) {codes.getString(it)},o.getString("scope"),o.getString("summary"),o.getString("symptoms"),o.optString("danger"),o.optString("tactics"),o.optString("medications"),List(sources.length()) {j ->val s=sources.getJSONObject(j);IcdSource(s.getString("title"),s.getString("url"),s.getString("type"))},o.getString("reviewed"))
            }
        }.also {cache=it}
    }
    fun forCode(context: Context,code: String): IcdGuide? = all(context).mapNotNull {g ->
        val specificity=g.codes.filter {IcdLanguage.inFamily(code,it)}.maxOfOrNull(String::length) ?: return@mapNotNull null
        g to specificity
    }.maxByOrNull {it.second}?.first
}
