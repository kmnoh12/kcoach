package dev.kyungmin.kcoach.phone

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import dev.kyungmin.kcoach.core.*
import dev.kyungmin.kcoach.phone.ui.WorkoutTempoScreen

class WorkoutTempoSettingsActivity : ComponentActivity() {
    private val store by lazy { WorkoutTempoSettingsStore(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var selected by rememberSaveable { mutableStateOf<String?>(null) }
            val previous = remember(selected) { selected?.let { store.load(it).getOrDefault(WorkoutTempoSettings(it)) } }
            BackHandler(selected != null) { selected = null }
            WorkoutTempoScreen(selected, previous, select = { selected = it }, back = { if (selected != null) selected = null else finish() },
                save = { next -> store.save(next).onSuccess {
                    Toast.makeText(this, "템포 알림 설정을 저장했어요.", Toast.LENGTH_SHORT).show(); selected = null
                }.isSuccess })
        }
    }
}
