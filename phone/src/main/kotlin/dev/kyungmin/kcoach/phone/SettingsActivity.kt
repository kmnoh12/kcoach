package dev.kyungmin.kcoach.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import dev.kyungmin.kcoach.phone.ui.SettingsActions
import dev.kyungmin.kcoach.phone.ui.SettingsScreen

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = ChatGptAuth.get(applicationContext)
        val actions = SettingsActions(
            back = ::finish,
            account = { startActivity(Intent(this, ChatGptAccountActivity::class.java)) },
            refreshModels = { auth.loadModels(force = true) },
            chooseModel = auth::chooseModel,
            chooseEffort = auth::chooseEffort,
        )
        setContent {
            val connection = auth.state.value
            LaunchedEffect(connection.activeProfileId, connection.connected, connection.planAllowed, connection.connectionRevision) {
                if (connection.connected && connection.planAllowed) auth.loadModels()
            }
            SettingsScreen(connection, auth.modelSettings.value, actions)
        }
    }

    override fun onResume() {
        super.onResume()
        ChatGptAuth.get(applicationContext).loadModels(force = true)
    }
}
