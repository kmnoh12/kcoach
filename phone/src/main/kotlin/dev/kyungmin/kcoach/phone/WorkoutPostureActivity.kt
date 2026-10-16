package dev.kyungmin.kcoach.phone

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kyungmin.kcoach.phone.ui.WorkoutPostureScreen

/** Settings screen for the per-exercise start posture answers. */
class WorkoutPostureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = WorkoutWearingStore(applicationContext)
        var answers by mutableStateOf(store.preparationAnswers().getOrDefault(emptyMap()))
        setContent {
            WorkoutPostureScreen(answers, ::finish) { id, value ->
                store.savePreparationAnswer(id, value)
                    .onSuccess { answers = it }
                    .onFailure { Toast.makeText(this, "자세 설정을 저장하지 못했어요. 기존 선택을 유지해요.", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
