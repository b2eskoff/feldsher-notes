package ru.ainur.feldshernotes.ui

/** Offline wording equivalences, not a diagnostic inference engine. All query terms must match. */
internal object IcdLanguage {
    private val ignored=setOf("в","во","на","с","со","и","у","из","для","по","диагноз","основной","сопутствующий","пациента")
    private val endings=listOf("иями","ями","ого","ему","ому","ыми","ими","ией","иям","иях","ов","ев","ий","ый","ая","яя","ое","ее","ые","ие","ой","ей","ам","ах","ом","ем","ию","ия","ью","ы","и","а","я","у","ю","е")
    fun words(s: String): List<String> = ReferenceSearch.normalize(s).split(' ').filter { it.isNotBlank() && it !in ignored }.map { word ->
        if(word.length<4) word else {
            val ending=endings.firstOrNull { word.endsWith(it) && word.length-it.length>=3 }
            if(ending==null) word else word.dropLast(ending.length)
        }
    }
    private fun alternatives(word: String): List<String> = when {
        word.startsWith("гиперто") -> listOf(word,"гипертенз")
        word.startsWith("гипертенз") -> listOf(word,"гиперто")
        word.startsWith("головн") -> listOf(word,"церебр")
        word=="мозг" -> listOf(word,"церебр")
        else -> listOf(word)
    }
    fun matches(query: List<String>, document: List<String>): Boolean = query.all { q ->
        alternatives(q).any { part -> document.any { it.startsWith(part) } }
    }
    fun aliases(code: String): String = when {
        code=="G93.0" -> "киста головного мозга церебральная киста киста гм"
        code=="Q04.6" -> "врожденная киста головного мозга врожденная киста гм"
        code=="I10" -> "гипертония гипертоническая болезнь артериальная гипертензия гб"
        code.startsWith("I11") || code.startsWith("I12") || code.startsWith("I13") -> "гипертония гипертоническая болезнь артериальная гипертензия гб"
        code.startsWith("J12") || code.startsWith("J13") || code.startsWith("J14") || code.startsWith("J15") || code.startsWith("J16") || code.startsWith("J17") || code.startsWith("J18") -> "пневмония воспаление легких"
        code.startsWith("E11") -> "сахарный диабет 2 типа сд 2"
        code.startsWith("E10") -> "сахарный диабет 1 типа сд 1"
        code=="J06.9" -> "орви острая респираторная инфекция верхних дыхательных путей неуточненная"
        code=="N20.0" -> "камень почки камни почек нефролитиаз"
        code=="N20.1" -> "камень мочеточника уретеролитиаз"
        code=="K80.2" -> "камни желчного пузыря без холецистита"
        code=="R51" -> "головная боль цефалгия"
        code=="R55" -> "обморок синкопе синкопальное состояние"
        code=="R04.0" -> "носовое кровотечение кровь из носа"
        code=="R73.9" -> "повышенный сахар гипергликемия неуточненная"
        code=="E16.2" -> "низкий сахар гипогликемия неуточненная"
        else -> ""
    }
}
