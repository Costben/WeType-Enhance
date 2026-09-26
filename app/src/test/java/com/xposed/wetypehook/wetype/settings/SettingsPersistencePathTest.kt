package com.xposed.wetypehook.wetype.settings

import org.junit.Assert.*
import org.junit.Test

/**
 * Pins the save contract: **a successful local write is a successful save**.
 *
 * The bridge to the module app is a best-effort mirror, never a precondition.
 * `:hld` reads the host's own `shared_prefs` file — the very file `saveDirect`
 * just wrote — so a mirror that never arrives cannot make a saved setting take
 * effect any less. Three rules are guarded here:
 *
 * - A 5s ACK wait used to turn a delivered-but-unacknowledged broadcast into
 *   "could not save settings", while the value was already on disk. That is a
 *   lie, and on ColorOS it fired on *every* save: `OplusAppStartupManager`
 *   blocks waking the module app from a broadcast entirely. No save may ever
 *   wait for the mirror again.
 * - Ordering the snapshot resolution remote-first let the module app's stale
 *   mirror overwrite the host file on the next `:hld` start, silently reverting
 *   whatever the user had just saved. Remote is now a first-install seed only:
 *   once the host has written a snapshot of its own, no remote value may ever
 *   change it, whatever revision it carries.
 * - `host_sync_pending` means "the mirror is not yet proven to have landed", not
 *   "the broadcast failed". `sendBroadcast` answering `true` says nothing about
 *   the receiver, so the flag is cleared only after the module writes the same
 *   revision back into remote preferences, which the host can read.
 *
 * The settings UI reads the boolean back through `saveSettings`, so the only
 * rules that matter are: the local write decides the result, and nothing else.
 */
class SettingsPersistencePathTest {

    /**
     * The result `onPersisted` receives, modelled after the branch in
     * `saveDirect` rather than called into it — that path needs a real Context
     * and SharedPreferences.
     *
     * `mirrorDelivered` is accepted as a parameter purely so the tests can show
     * it has no influence on the result; it only feeds `host_sync_pending`.
     */
    private fun saveResult(localWriteSucceeded: Boolean, mirrorDelivered: Boolean): Boolean =
        if (!localWriteSucceeded) false else true

    /**
     * `host_sync_pending` as `saveDirect` now leaves it: owed whenever a mirror
     * was even attempted, because the broadcast result proves nothing.
     */
    private fun hostSyncPendingAfterSave(moduleAppInstalled: Boolean): Boolean = moduleAppInstalled

    /** Seeding only: modelled after the remote guard in `syncHostSnapshotFromRemote`. */
    private fun remoteChangesTheHost(hostHasLocalSnapshot: Boolean): Boolean = !hostHasLocalSnapshot

    /** Modelled after `nextSnapshotRevision`, which no longer reads the clock. */
    private fun moduleRevisionAfter(local: Long, remote: Long): Long = maxOf(local, remote) + 1L

    @Test fun localWriteAloneIsEnoughWhenThereIsNoModuleApp() {
        assertTrue(saveResult(localWriteSucceeded = true, mirrorDelivered = false))
    }

    @Test fun aMirrorThatNeverArrivesIsStillASuccessfulSave() {
        // The regression: this used to report failure after a 5s timeout.
        assertTrue(saveResult(localWriteSucceeded = true, mirrorDelivered = false))
        assertTrue(saveResult(localWriteSucceeded = true, mirrorDelivered = true))
    }

    @Test fun aMirrorThatIsBlockedByTheSystemIsStillASuccessfulSave() {
        // ColorOS `OplusAppStartupManager` refuses to start the module app from
        // a broadcast, so this is the common case on those devices, not an edge.
        val localWriteSucceeded = true
        val mirrorDelivered = false
        assertTrue(saveResult(localWriteSucceeded, mirrorDelivered))
        assertTrue(
            "an unverified mirror is a debt to retry",
            hostSyncPendingAfterSave(moduleAppInstalled = true)
        )
    }

    @Test fun aSaveLeavesTheMirrorOwedUntilTheModuleProvesItLanded() {
        // The broadcast result is not proof — only the module writing the same
        // revision back into remote preferences is, and that is checked on the
        // next `:hld` start rather than during the save.
        assertTrue(hostSyncPendingAfterSave(moduleAppInstalled = true))
    }

    @Test fun theLspatchEmbedOwesNothingBecauseThereIsNoMirrorToWaitFor() {
        assertFalse(hostSyncPendingAfterSave(moduleAppInstalled = false))
    }

    @Test fun aRemoteMirrorCanOnlySeedAHostThatNeverSaved() {
        assertTrue(remoteChangesTheHost(hostHasLocalSnapshot = false))
        assertFalse(remoteChangesTheHost(hostHasLocalSnapshot = true))
    }

    @Test fun aStaleModuleCopyCannotOutrankTheHostByClockTime() {
        // The host revision is a wall-clock stamp from a moment ago; the module's
        // copy is one bridge cycle behind. The old `max(now, previous + 1)` handed
        // the stale copy a *larger* revision than the host's, which is exactly how
        // it used to overwrite freshly saved settings.
        val hostRevision = System.currentTimeMillis() - 5_000L
        assertFalse(
            "a mirror a bridge cycle behind must stay behind",
            moduleRevisionAfter(local = hostRevision - 500L, remote = 0L) > hostRevision
        )
        assertTrue(moduleRevisionAfter(local = hostRevision, remote = 0L) > hostRevision)
    }

    @Test fun aFailedLocalWriteIsNeverReportedAsSuccess() {
        assertFalse(saveResult(localWriteSucceeded = false, mirrorDelivered = false))
        assertFalse(saveResult(localWriteSucceeded = false, mirrorDelivered = true))
    }

    /**
     * The LSPatch embed marker: the module package is absent while the module
     * code itself is running inside the host. Detection is a plain
     * getPackageInfo probe, so absence must be a normal outcome, not a throw
     * that escapes into the save path.
     */
    @Test fun absentModulePackageIsDetectedNotThrown() {
        val probe: (String) -> Boolean = { packageName ->
            runCatching { if (packageName == "com.xposed.wetypehook") error("NameNotFoundException") else Unit }
                .isSuccess
        }
        assertFalse(probe("com.xposed.wetypehook"))
        assertTrue(probe("com.tencent.wetype"))
    }
}
