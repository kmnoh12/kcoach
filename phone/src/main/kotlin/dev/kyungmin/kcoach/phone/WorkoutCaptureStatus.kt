package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject

internal class WorkoutCaptureStatus(context: Context) {
    private val prefs = context.getSharedPreferences("wear-sync", Context.MODE_PRIVATE)
    private val inbox = CaptureInbox(context, prefs)
    fun read(snapshot: WorkoutSnapshot): JSONObject {
        val manifests = prefs.all.filterKeys { it.startsWith("capture:") && it.endsWith(":manifest") }.values
            .filterIsInstance<String>().mapNotNull { runCatching { WireCodec.decode(it) }.getOrNull() }
            .filter { it.sessionId == snapshot.sessionId }.associateBy { it.payload[WirePayloadKeys.CAPTURE_ID].orEmpty() }
        val ids = (snapshot.captureStates.keys + manifests.keys).filter { it.isNotBlank() }.sorted()
        val rows = JSONArray(ids.map { id ->
            val manifest = manifests[id]
            val state = inbox.state(id) ?: snapshot.captureStates[id]
            JSONObject().put("capture_id", id).put("set_id", manifest?.setId ?: JSONObject.NULL)
                .put("state", state?.name ?: "UNKNOWN").put("analysis_complete", inbox.analysisSucceeded(id))
                .put("manifest_known", manifest != null)
        })
        val received = ids.count { inbox.state(it) in setOf(CaptureState.RECEIVED, CaptureState.ANALYZING, CaptureState.ACKED) || inbox.analysisSucceeded(it) }
        val pending = ids.count { (inbox.state(it) ?: snapshot.captureStates[it]) in setOf(CaptureState.LOCAL_ONLY, CaptureState.PENDING_UPLOAD, CaptureState.UPLOADING) }
        val analyzing = ids.count { inbox.state(it) == CaptureState.ANALYZING || inbox.state(it) == CaptureState.RECEIVED && !inbox.analysisSucceeded(it) }
        val failed = ids.count { inbox.state(it) == CaptureState.ANALYSIS_FAILED }
        val message = when {
            pending > 0 -> "워치 기록을 받는 중이에요 · 알려진 원본 ${received}/${ids.size}개 수신"
            analyzing > 0 -> "속도와 움직임을 분석하고 있어요"
            failed > 0 -> "일부 세트의 분석이 남아 있어요"
            ids.isEmpty() -> "이 기록에서 확인된 센서 원본이 없어요"
            else -> "받은 원본의 세부 분석을 확인할 수 있어요"
        }
        return JSONObject().put("known_capture_count", ids.size).put("received", received).put("pending", pending)
            .put("analyzing", analyzing).put("failed", failed).put("message", message).put("captures", rows)
            .put("coverage_note", "확인된 캡처 ID·manifest 기준. 세트 수를 기대 원본 수로 간주하지 않음. 미수신 manifest의 전체 개수는 확정할 수 없음.")
    }
}
