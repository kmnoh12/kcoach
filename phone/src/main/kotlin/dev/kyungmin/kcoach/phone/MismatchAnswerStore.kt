package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.MismatchCandidate
import dev.kyungmin.kcoach.core.MismatchSide
import dev.kyungmin.kcoach.core.RepSpan
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The user's yes/no on each watch-versus-video mismatch window. These answers are kept beside the capture
 * and are never promoted to rep labels here: CalibrationReviewStore still needs a human-reviewed alignment.
 */
class MismatchAnswerStore(private val captures: CalibrationCaptureStore) {
    data class Answer(val candidate: MismatchCandidate, val realRep: Boolean)

    fun load(id: String): List<Answer> = runCatching {
        val file = captures.userFile(id, "mismatch-answers.json")
        if (!file.isFile) return@runCatching emptyList()
        val rows = JSONArray(file.readText())
        (0 until rows.length()).map { rows.getJSONObject(it) }.map { row ->
            Answer(MismatchCandidate(RepSpan(row.getInt("startMs"), row.getInt("endMs")), MismatchSide.valueOf(row.getString("side"))),
                row.getBoolean("realRep"))
        }
    }.getOrDefault(emptyList())

    fun save(id: String, candidate: MismatchCandidate, realRep: Boolean): Result<Unit> = runCatching {
        val next = load(id).filter { it.candidate != candidate } + Answer(candidate, realRep)
        val file = captures.userFile(id, "mismatch-answers.json")
        val temp = File(file.parentFile, file.name + ".incoming")
        temp.writeText(JSONArray(next.map { JSONObject().put("startMs", it.candidate.span.startMs).put("endMs", it.candidate.span.endMs)
            .put("side", it.candidate.side.name).put("realRep", it.realRep) }).toString())
        check(temp.renameTo(file) || (file.delete() && temp.renameTo(file)))
    }
}
