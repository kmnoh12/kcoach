package dev.kyungmin.kcoach.phone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.kyungmin.kcoach.phone.ui.ChatGptAccountActions
import dev.kyungmin.kcoach.phone.ui.ChatGptAccountScreen

/** The deep link only returns to UI; OAuth codes are accepted exclusively on the loopback listener. */
class ChatGptAccountActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = ChatGptAuth.get(applicationContext)
        val actions = ChatGptAccountActions(
            back = ::finish,
            connect = { usePlan, newAccount ->
                auth.begin(usePlan, newAccount) { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            },
            cancel = { auth.cancel() },
            signOut = auth::signOut,
            select = auth::select,
            manageUsage = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) },
            manageConnections = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/security"))) },
            welcomeSeen = auth::acknowledgeWelcome,
        )
        setContent { ChatGptAccountScreen(auth.state.value, actions) }
    }
}
