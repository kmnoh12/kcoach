package dev.kyungmin.kcoach.core

/** What the substitute ranking needs to know about an exercise. [pattern] is the movement family, e.g. "row". */
data class SubstituteCandidate(
    val id: String,
    val muscles: List<String>,
    val bodyCategory: String,
    val equipment: String,
    val pattern: String,
    val favorite: Boolean = false,
)

/**
 * Picks replacements for an exercise: the same movement first (a seated row is
 * replaced by other rows), then other work for the same main muscle. A
 * replacement on the same equipment ranks ahead of one that needs a different
 * machine, so the closest feel comes first.
 */
object ExerciseSubstitutes {
    fun rank(source: SubstituteCandidate, pool: List<SubstituteCandidate>, limit: Int = 6): List<SubstituteCandidate> {
        fun score(c: SubstituteCandidate): Int {
            var s = 0
            if (source.pattern.isNotEmpty() && c.pattern == source.pattern) s += 100
            if (source.muscles.firstOrNull() != null && source.muscles.first() == c.muscles.firstOrNull()) s += 30
            s += source.muscles.intersect(c.muscles.toSet()).size * 10
            if (c.bodyCategory == source.bodyCategory) s += 15
            if (c.equipment == source.equipment) s += 5
            return s
        }
        return pool
            .filter { it.id != source.id && score(it) > 0 }
            .sortedWith(compareByDescending<SubstituteCandidate> { score(it) }.thenByDescending { it.favorite }.thenBy { it.id })
            .take(limit)
    }
}
