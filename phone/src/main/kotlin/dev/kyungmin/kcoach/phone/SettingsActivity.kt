package dev.kyungmin.kcoach.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.kyungmin.kcoach.phone.ui.SettingsActions
import dev.kyungmin.kcoach.phone.ui.SettingsScreen

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actions = SettingsActions(back = ::finish,
            tempo = { startActivity(Intent(this, WorkoutTempoSettingsActivity::class.java)) },
            profile = { startActivity(Intent(this, WorkoutProfileActivity::class.java)) })
        setContent { SettingsScreen(actions) }
    }
}
