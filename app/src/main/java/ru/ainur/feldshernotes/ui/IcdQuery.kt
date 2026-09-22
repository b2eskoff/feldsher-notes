package ru.ainur.feldshernotes.ui

internal object IcdQuery {
    private val stage=Regex("(?:\\b[1-4iv]+\\s+(?:стади[яию]|степен[ьи]))|(?:(?:стади[яию]|степен[ьи])\\s+[1-4iv]+\\b)")
    fun searchPhrase(input: String): String = stage.replace(normalize(input)," ").trim().replace(Regex("\\s+")," ")
    fun hasStage(input: String): Boolean = stage.containsMatchIn(normalize(input))
    fun combinedCodes(input: String): Set<String> = if(normalize(input)=="холецистопанкреатит") setOf("K81","K85","K86.1","K80.0","K80.1") else emptySet()
    fun normalize(input: String): String {
        val trimmed=input.trim()
        val letters=mapOf('и' to 'I','а' to 'A','в' to 'B','с' to 'C','е' to 'E','н' to 'H','к' to 'K','м' to 'M','о' to 'O','р' to 'P','т' to 'T','х' to 'X')
        val code=if(Regex("^[\\p{L}][0-9].*").matches(trimmed)) (letters[trimmed.first().lowercaseChar()]?.toString() ?: trimmed.take(1))+trimmed.drop(1) else trimmed
        return ReferenceSearch.normalize(code)
    }
    // Search hints expand the family, never choose an individual code for the user.
    fun relatedCodes(q: String): List<String> {
        if(q.length<3) return emptyList()
        return if(listOf("гипертоническая болезнь","гипертония","артериальная гипертензия","гб").any { it.startsWith(q) })
            listOf("I10","I11","I12","I13","I15") else emptyList()
    }
}
