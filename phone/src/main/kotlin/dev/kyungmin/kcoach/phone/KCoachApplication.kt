package dev.kyungmin.kcoach.phone

import android.app.Application
import dev.kyungmin.kcoach.updates.*

class KCoachApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LocalExitDiagnostics.install(this)
        WorkoutTermination(this).recover().onFailure {
            android.util.Log.e("KCoachEvent", "Stopped workout archive recovery deferred", it)
        }
        val profiles = UserProfileStore(this)
        runCatching { profiles.migrate() }.onFailure {
            android.util.Log.e("KCoach", "Local profile migration failed; original personal facts retained", it)
        }
        runCatching { profiles.removeRetiredConnectionData() }.onFailure {
            android.util.Log.e("KCoach", "Retired connection cleanup will retry on next launch", it)
        }
        OtaCoordinator.initialize(this, OtaConfig(OtaRole.PHONE, BuildConfig.UPDATE_MANIFEST_URL))
    }
}
