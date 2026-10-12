package dev.kyungmin.kcoach.core

/**
 * How the library is browsed: body part, then a movement section inside it
 * (vertical pulls, horizontal rows, ...). Exercises whose pattern is unknown
 * land in the last section, "기타".
 */
object ExerciseTaxonomy {
    const val OTHER_ORDER = 99
    const val OTHER_TITLE = "기타"

    private val sections: Map<String, List<Pair<String, String>>> = mapOf(
        "등" to listOf("vertical_pull" to "수직 당기기", "row" to "수평 당기기"),
        "가슴" to listOf("chest_press" to "프레스", "chest_fly" to "플라이"),
        "어깨" to listOf("shoulder_press" to "프레스", "lateral_raise" to "사이드 레이즈", "rear_delt" to "후면 어깨"),
        "이두" to listOf("curl" to "컬"),
        "삼두" to listOf("triceps_pushdown" to "푸시다운", "triceps_extension" to "익스텐션"),
        "하체" to listOf("leg_press" to "프레스", "leg_curl" to "컬", "leg_extension" to "익스텐션", "hip" to "힙"),
    )

    /** Body parts in the order the filter chips show them. */
    val bodyOrder = listOf("가슴", "등", "어깨", "삼두", "이두", "하체")

    /** Order and title of the section an exercise belongs to inside its body part. */
    fun section(bodyCategory: String, pattern: String): Pair<Int, String> {
        val list = sections[bodyCategory].orEmpty()
        val index = list.indexOfFirst { it.first == pattern }
        return if (index >= 0) index to list[index].second else OTHER_ORDER to OTHER_TITLE
    }

    fun bodyRank(bodyCategory: String): Int = bodyOrder.indexOf(bodyCategory).let { if (it < 0) bodyOrder.size else it }
}
