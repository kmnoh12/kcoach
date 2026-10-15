package dev.kyungmin.kcoach.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import dev.kyungmin.kcoach.phone.ui.AiCoachActions
import dev.kyungmin.kcoach.phone.ui.AiCoachScreen

class AiCoachActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = ChatGptAuth.get(applicationContext)
        val controller = AiCoachController.get(applicationContext)
        val actions = AiCoachActions(::finish, { startActivity(Intent(this, SettingsActivity::class.java)) },
            controller::saveProfile, controller::linkRecords, controller::send, controller::cancel, controller::importDeviceBody)
        setContent {
            val connection = auth.state.value
            LaunchedEffect(connection.activeProfileId, connection.connected, connection.connectionRevision) {
                controller.bindAccount(connection.activeProfileId.takeIf { connection.connected })
                if (connection.connected && connection.planAllowed) auth.loadModels()
            }
            key(connection.activeProfileId, connection.connected) {
                AiCoachScreen(controller.state.value, connection, auth.modelSettings.value, actions,
                    startProfile = intent.getBooleanExtra(EXTRA_PROFILE, false))
            }
        }
    }
    override fun onResume() {
        super.onResume()
        val auth = ChatGptAuth.get(applicationContext)
        AiCoachController.get(applicationContext).bindAccount(auth.state.value.activeProfileId.takeIf { auth.state.value.connected }, force = true)
    }
    companion object { const val EXTRA_PROFILE = "open_profile" }
}
