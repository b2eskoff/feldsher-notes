package ru.ainur.feldshernotes.ui

internal object IcdQuery {
    fun normalize(input: String): String {
        val trimmed=input.trim()
        val code=if(Regex("^[иИiI][0-9].*").matches(trimmed)) "I"+trimmed.drop(1) else trimmed
        return ReferenceSearch.normalize(code)
    }
    // Search hints expand the family, never choose an individual code for the user.
    fun relatedCodes(q: String): List<String> {
        if(q.length<3) return emptyList()
        return if(listOf("гипертоническая болезнь","гипертония","артериальная гипертензия","гб").any { it.startsWith(q) })
            listOf("I10","I11","I12","I13","I15") else emptyList()
    }
}
