package dev.kyungmin.kcoach.phone

import android.app.Activity
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateInstallRecoveryTest {
    @Test fun permissionRequestSurvivesANewActivityAndOnlyLaunchesTheInstallerAfterPermission() {
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        val app = first.get().applicationContext
        File(app.cacheDir, "updates/kcoach-update.apk").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        assertNotNull(UpdateManager(first.get(), "https://example.invalid/manifest").requestInstall())
        first.pause().stop().destroy()
        val second = Robolectric.buildActivity(Activity::class.java).setup()
        val recovered = UpdateManager(second.get(), "https://example.invalid/manifest")
        assertEquals("설치 권한이 아직 허용되지 않았어요", recovered.resumePendingInstall())
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        assertEquals("설치 화면을 열었어요", recovered.resumePendingInstall())
        assertEquals(Intent.ACTION_VIEW, shadowOf(second.get()).nextStartedActivity.action)
        assertNull(UpdateManager(second.get(), "https://example.invalid/manifest").resumePendingInstall())
        second.pause().stop().destroy()
    }
    @Test fun missingCachedApkDoesNotLaunchAnInstallerOnRecovery() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val app = controller.get().applicationContext
        app.getSharedPreferences("update-install", 0).edit().putBoolean("pending-permission", true).commit()
        assertEquals("업데이트 파일이 없어 다시 다운로드해야 해요",
            UpdateManager(controller.get(), "https://example.invalid/manifest").resumePendingInstall())
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        controller.pause().stop().destroy()
    }
}
