package dev.kyungmin.kcoach.phone

import android.app.Application
import dev.kyungmin.kcoach.updates.*

class KCoachApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OtaCoordinator.initialize(this, OtaConfig(OtaRole.PHONE, BuildConfig.UPDATE_MANIFEST_URL))
    }
}
