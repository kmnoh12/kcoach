package dev.kyungmin.kcoach.wear

import android.app.Application
import dev.kyungmin.kcoach.updates.*

class KCoachApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OtaCoordinator.initialize(this, OtaConfig(OtaRole.WATCH, BuildConfig.UPDATE_MANIFEST_URL))
    }
}
