package dev.kyungmin.kcoach.phone

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.kyungmin.kcoach.core.ProgressionSettings
import dev.kyungmin.kcoach.phone.ui.SettingsActions
import dev.kyungmin.kcoach.phone.ui.SettingsScreen

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actions = SettingsActions(back = ::finish,
            tempo = { startActivity(Intent(this, WorkoutTempoSettingsActivity::class.java)) },
            posture = { startActivity(Intent(this, WorkoutPostureActivity::class.java)) },
            profile = { startActivity(Intent(this, WorkoutProfileActivity::class.java)) })
        val store = ProgressionSettingsStore(this)
        val progression = store.load().getOrElse {
            Log.w("KCoachEvent", "progression settings unreadable; using defaults", it)
            ProgressionSettings()
        }
        setContent {
            SettingsScreen(actions, progression) { next ->
                store.save(next).onFailure { Log.e("KCoachEvent", "progression settings not saved", it) }
            }
        }
    }
}
