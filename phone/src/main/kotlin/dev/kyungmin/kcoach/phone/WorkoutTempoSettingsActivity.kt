package dev.kyungmin.kcoach.phone

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.activity.ComponentActivity
import dev.kyungmin.kcoach.core.*

class WorkoutTempoSettingsActivity : ComponentActivity() {
    private lateinit var content: LinearLayout
    private val store by lazy { WorkoutTempoSettingsStore(this) }
    private var selectedExerciseId: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selected = savedInstanceState?.getString("exerciseId")?.let { id -> WorkoutData.catalog.find { it.id == id } }
        if (selected == null) showList() else showExercise(selected.id, selected.name)
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("exerciseId", selectedExerciseId); super.onSaveInstanceState(outState) }
    private fun root(title: String) {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p); setBackgroundColor(Color.rgb(9, 12, 11))
        }
        setContentView(ScrollView(this).apply { addView(content) }); content.addView(text(title, 24f))
    }
    private fun text(value: String, size: Float = 14f) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.LTGRAY); setPadding(0, 8, 0, 8) }
    private fun button(title: String, action: () -> Unit) = Button(this).apply { text = title; setOnClickListener { action() } }
    private fun label(metric: WorkoutTempoMetric) = when (metric) {
        WorkoutTempoMetric.REPETITION -> "1회 전체 시간"
        WorkoutTempoMetric.CONCENTRIC -> "힘쓰는 구간 시간"
        WorkoutTempoMetric.ECCENTRIC -> "복귀 구간 시간"
    }
    private fun input(hintText: String, value: String) = EditText(this).apply {
        hint = hintText; setText(value); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    private fun showList() {
        selectedExerciseId = null
        root("운동별 템포 알림")
        content.addView(text("기본은 꺼져 있어요. 직접 정한 범위에서 연속으로 벗어나면 워치가 알려줘요. 검증된 개인 반복 엔진을 사용한 세트에서만 동작하며, 확인되지 않은 힘쓰는·복귀 시간은 알림에 쓰지 않아요."))
        WorkoutData.catalog.distinctBy { it.id }.filter {
            ExerciseTrackingProfiles.resolve(it.id).mode != TrackingMode.MANUAL_REQUIRED
        }.forEach { exercise -> content.addView(button(exercise.name) { showExercise(exercise.id, exercise.name) }) }
        content.addView(button("설정으로 돌아가기") { finish() })
    }
    private fun showExercise(id: String, name: String) {
        selectedExerciseId = id
        root("$name · 템포")
        val previous = store.load(id).getOrDefault(WorkoutTempoSettings(id))
        val enabled = CheckBox(this).apply { this.id = 0x6f100001; text = "이 운동의 템포 알림 사용"; isChecked = previous.enabled; setTextColor(Color.WHITE) }
        content.addView(enabled)
        content.addView(text("원하는 시간 범위를 초로 입력해 주세요. 비워 둔 항목은 알림을 끄며, 목표 시간은 운동 처방이나 자세 점수가 아니에요."))
        val rows = WorkoutTempoMetric.entries.associateWith { metric ->
            content.addView(text(label(metric)))
            val range = previous.targets[metric]
            val minimum = input("최소 초", range?.let { (it.minimumMs / 1000.0).toString() }.orEmpty())
            val maximum = input("최대 초", range?.let { (it.maximumMs / 1000.0).toString() }.orEmpty())
            minimum.id = 0x6f100010 + metric.ordinal * 2; maximum.id = minimum.id + 1
            content.addView(LinearLayout(this).apply {
                addView(minimum, LinearLayout.LayoutParams(0, -2, 1f)); addView(maximum, LinearLayout.LayoutParams(0, -2, 1f))
            }); minimum to maximum
        }
        content.addView(text("몇 회 연속 벗어나면 알릴까요?"))
        val consecutive = input("연속 횟수", previous.consecutiveRepetitions.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        consecutive.id = 0x6f100020
        content.addView(consecutive)
        content.addView(text("알림 사이 최소 간격"))
        val interval = input("초", (previous.minimumAlertIntervalMs / 1000.0).toString()); content.addView(interval)
        interval.id = 0x6f100021
        content.addView(text("저장은 다음 새 세트부터 반영해요. 검증된 엔진이나 유효한 구간이 없으면 알림을 보내지 않아요."))
        content.addView(button("다음 새 세트부터 저장") {
            runCatching {
                val targets = rows.mapNotNull { (metric, fields) ->
                    val fromText = fields.first.text.toString().trim(); val untilText = fields.second.text.toString().trim()
                    if (fromText.isEmpty() && untilText.isEmpty()) null else {
                        fun milliseconds(value: String): Long {
                            val seconds = value.toDouble(); require(seconds.isFinite() && seconds > 0 && seconds <= 120)
                            return Math.round(seconds * 1000).also { require(it > 0) }
                        }
                        metric to WorkoutTempoRange(milliseconds(fromText), milliseconds(untilText))
                    }
                }.toMap()
                val count = consecutive.text.toString().toInt()
                val seconds = interval.text.toString().toDouble(); require(seconds.isFinite() && seconds in 1.0..120.0)
                store.save(WorkoutTempoSettings(id, enabled.isChecked, targets, count, Math.round(seconds * 1000))).getOrThrow()
            }.onSuccess { Toast.makeText(this, "템포 알림 설정을 저장했어요.", Toast.LENGTH_SHORT).show(); showList() }
                .onFailure { Toast.makeText(this, "시간 범위·연속 횟수·알림 간격을 확인해 주세요. 알림을 켜려면 시간 범위가 필요해요.", Toast.LENGTH_LONG).show() }
        })
        content.addView(button("운동 목록") { showList() })
    }
}
