package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExerciseSubstitutesTest {
    private fun c(id: String, equipment: String, pattern: String, muscles: String = "등,이두", body: String = "등", fav: Boolean = false) =
        SubstituteCandidate(id, muscles.split(","), body, equipment, pattern, fav)

    private val seatedRow = c("seated_row", "머신", "row", fav = true)
    private val pool = listOf(
        seatedRow,
        c("pull_up", "맨몸", "vertical_pull", fav = true),
        c("lat_pull_down", "머신", "vertical_pull", fav = true),
        c("lying_tbar_row", "머신", "row", fav = true),
        c("barbell_row", "바벨", "row"),
        c("one_arm_dumbbell_row", "덤벨", "row"),
        c("flat_bench", "바벨", "chest_press", "가슴,삼두", "가슴"),
    )

    @Test
    fun `other rows come first and the source itself is never offered`() {
        val ranked = SubstituteCandidate::id.let { ExerciseSubstitutes.rank(seatedRow, pool).map(it) }
        assertEquals(listOf("lying_tbar_row", "barbell_row", "one_arm_dumbbell_row"), ranked.take(3))
        assertTrue("seated_row" !in ranked)
    }

    @Test
    fun `same main muscle work follows the same movement`() {
        val ranked = ExerciseSubstitutes.rank(seatedRow, pool).map { it.id }
        assertTrue(ranked.indexOf("lat_pull_down") > ranked.indexOf("one_arm_dumbbell_row"))
    }

    @Test
    fun `unrelated muscles are left out`() {
        assertTrue(ExerciseSubstitutes.rank(seatedRow, pool).none { it.id == "flat_bench" })
    }

    @Test
    fun `the list is capped`() {
        val many = (1..20).map { c("row$it", "머신", "row") } + seatedRow
        assertEquals(6, ExerciseSubstitutes.rank(seatedRow, many).size)
    }
}
