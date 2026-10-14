package dev.kyungmin.kcoach.updates

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OtaCoordinatorTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val hash = "a".repeat(64)
    private class Link : OtaLink {
        val commands = mutableListOf<OtaCommand>()
        val states = mutableListOf<OtaDeviceState>()
        override fun command(command: OtaCommand) { commands.add(command) }
        override fun state(state: OtaDeviceState) { states.add(state) }
        override fun recover(receive: (String, String, String) -> Unit) {}
    }
    private fun version(code: Long) {
        val info = PackageInfo().apply { packageName = app.packageName; longVersionCode = code; versionName = "test-$code" }
        shadowOf(app.packageManager).installPackage(info)
    }
    private fun coordinator(role: OtaRole, link: Link, scheduled: MutableList<String>) =
        OtaCoordinator.initialize(app, OtaConfig(role, "https://example.invalid/manifest"), link) { scheduled.add(it) }
    private fun finishSelf(c: OtaCoordinator, newer: Boolean) {
        val id = c.store.local()!!.id
        c.change(id) { it.copy(phase = if (newer) OtaPhase.READY else OtaPhase.UP_TO_DATE,
            targetCode = if (newer) 6 else 5, targetName = "target", sha256 = hash, progress = 100) }
    }
    private fun peer(c: OtaCoordinator, newer: Boolean, revision: Long = 1) = OtaDeviceState(
        c.store.command()!!.id, "watch-instance", revision, c.store.command()!!.sequence,
        if (newer) OtaPhase.READY else OtaPhase.UP_TO_DATE, 5, "watch-5", if (newer) 6 else 5, "watch-target", hash, 100)

    @Test fun phoneOnlyUpdateLaunchesOnlyItsOwnInstaller() {
        version(5); val link = Link(); val c = coordinator(OtaRole.PHONE, link, mutableListOf())
        c.checkBoth(); finishSelf(c, true)
        c.receive(OtaDataLayer.STATE_PATH, peer(c, false).encode(), "watch-node")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(c.ui().readyToInstall); c.installBoth(activity.get())
        assertEquals(OtaOperation.CHECK, link.commands.last().operation)
        assertEquals(OtaInstallActivity::class.java.name, shadowOf(activity.get()).nextStartedActivity.component!!.className)
        activity.pause().stop().destroy()
    }
    @Test fun watchOnlyUpdateDoesNotLaunchThePhoneInstaller() {
        version(5); val link = Link(); val c = coordinator(OtaRole.PHONE, link, mutableListOf())
        c.checkBoth(); finishSelf(c, false)
        c.receive(OtaDataLayer.STATE_PATH, peer(c, true).encode(), "watch-node")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        c.installBoth(activity.get())
        assertEquals(OtaOperation.INSTALL, link.commands.last().operation)
        assertEquals("watch-instance", link.commands.last().targetInstance)
        assertNull(shadowOf(activity.get()).nextStartedActivity)
        activity.pause().stop().destroy()
    }
    @Test fun bothUpdatesPersistTheWatchCommandAndWaitForItsAcceptanceBeforeLaunchingThePhone() {
        version(5); val link = Link(); val c = coordinator(OtaRole.PHONE, link, mutableListOf())
        c.checkBoth(); finishSelf(c, true)
        val ready = peer(c, true)
        c.receive(OtaDataLayer.STATE_PATH, ready.encode(), "watch-node")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        c.installBoth(activity.get())
        assertTrue(c.store.waitingForPeer())
        assertNull(shadowOf(activity.get()).nextStartedActivity)
        val command = link.commands.last()
        assertEquals(command, OtaStore(app).command())
        // A delayed pre-install reply cannot release the phone install.
        c.receive(OtaDataLayer.STATE_PATH, ready.copy(revision = 2).encode(), "watch-node")
        assertTrue(c.store.waitingForPeer())
        val accepted = ready.copy(revision = 3, commandSequence = command.sequence, phase = OtaPhase.USER_ACTION_REQUIRED)
        c.receive(OtaDataLayer.STATE_PATH, accepted.encode(), "watch-node")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(c.store.waitingForPeer())
        assertEquals(OtaInstallActivity::class.java.name, shadowOf(activity.get()).nextStartedActivity.component!!.className)
        assertEquals(5, c.store.peer()!!.installedCode)
        activity.pause().stop().destroy()
    }
    @Test fun noUpdatesMeansNoInstallAndMissingWatchIsNotCalledCurrent() {
        version(5); val link = Link(); val c = coordinator(OtaRole.PHONE, link, mutableListOf())
        c.checkBoth(); finishSelf(c, false)
        assertTrue(c.ui().message.contains("응답 대기"))
        c.receive(OtaDataLayer.STATE_PATH, peer(c, false).encode(), "watch-node")
        assertFalse(c.ui().readyToInstall); assertFalse(c.ui().busy)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        assertEquals("설치할 새 버전이 없어요", c.installBoth(activity.get()))
        assertNull(shadowOf(activity.get()).nextStartedActivity)
        activity.pause().stop().destroy()
    }
    @Test fun oldRequestWrongWatchAndReorderedRevisionsCannotReplaceCurrentPeerEvidence() {
        version(5); val c = coordinator(OtaRole.PHONE, Link(), mutableListOf())
        c.checkBoth(); finishSelf(c, false)
        val ready = peer(c, true, 3)
        c.receive(OtaDataLayer.STATE_PATH, ready.encode(), "watch-node")
        c.receive(OtaDataLayer.STATE_PATH, ready.copy(id = "old-request", revision = 100).encode(), "watch-node")
        c.receive(OtaDataLayer.STATE_PATH, ready.copy(revision = 2).encode(), "watch-node")
        c.receive(OtaDataLayer.STATE_PATH, ready.copy(revision = 4).encode(), "different-node")
        assertEquals(ready, c.store.peer())
    }
    @Test fun duplicateCheckSchedulesOnlyOnceAndOppositeInstanceCannotReceiveInstall() {
        version(5); val link = Link(); val scheduled = mutableListOf<String>(); val c = coordinator(OtaRole.WATCH, link, scheduled)
        val check = OtaCommand("request", "phone", 1, OtaOperation.CHECK)
        c.receive(OtaDataLayer.COMMAND_PATH, check.encode(), "phone-node")
        c.receive(OtaDataLayer.COMMAND_PATH, check.encode(), "phone-node")
        assertEquals(listOf("request"), scheduled)
        c.change("request") { it.copy(phase = OtaPhase.READY, targetCode = 6, targetName = "six", sha256 = hash) }
        val wrong = OtaCommand("request", "phone", 2, OtaOperation.INSTALL, "other-watch", 6, hash)
        c.receive(OtaDataLayer.COMMAND_PATH, wrong.encode(), "phone-node")
        assertEquals(OtaPhase.READY, c.store.local()!!.phase)
        val actual = wrong.copy(targetInstance = c.store.instance())
        c.receive(OtaDataLayer.COMMAND_PATH, actual.encode(), "phone-node")
        assertEquals(OtaPhase.USER_ACTION_REQUIRED, c.store.local()!!.phase)
        c.receive(OtaDataLayer.COMMAND_PATH, actual.encode(), "phone-node")
        assertEquals(1, scheduled.size)
        assertEquals(5, c.store.local()!!.installedCode)
    }
    @Test fun installSuccessCallbackWithoutTheNewPackageVersionNeverClaimsInstallation() {
        version(5); val c = coordinator(OtaRole.WATCH, Link(), mutableListOf())
        c.receive(OtaDataLayer.COMMAND_PATH, OtaCommand("request", "phone", 1, OtaOperation.CHECK).encode(), "phone")
        c.change("request") { it.copy(phase = OtaPhase.INSTALLING, targetCode = 6, targetName = "six", sha256 = hash) }
        val receiver = OtaInstallReceiver()
        receiver.onReceive(app, Intent().putExtra("id", "request").putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS))
        assertEquals(OtaPhase.INSTALLING, c.store.local()!!.phase)
        version(6)
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(OtaPhase.INSTALLED, c.store.local()!!.phase)
        assertEquals(6, c.store.local()!!.installedCode)
    }
    @Test fun peerFailureClearsAcceptanceWaitSoAnExplicitRetryRemainsPossible() {
        version(5); val c = coordinator(OtaRole.PHONE, Link(), mutableListOf())
        c.checkBoth(); finishSelf(c, true)
        val ready = peer(c, true); c.receive(OtaDataLayer.STATE_PATH, ready.encode(), "watch-node")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        c.installBoth(activity.get())
        c.receive(OtaDataLayer.STATE_PATH, ready.copy(revision = 2, commandSequence = c.store.command()!!.sequence,
            phase = OtaPhase.FAILED, message = "cancelled").encode(), "watch-node")
        assertFalse(c.store.waitingForPeer()); assertTrue(c.ui().readyToInstall)
        activity.pause().stop().destroy()
    }
}
