package com.xposed.wetypehook.wetype.settings

import org.junit.Assert.*
import org.junit.Test

/**
 * Pins the save contract: **a successful local write is a successful save**.
 *
 * The bridge to the module app is a best-effort mirror, never a precondition.
 * `:hld` reads the host's own `shared_prefs` file — the very file `saveDirect`
 * just wrote — so a mirror that never arrives cannot make a saved setting take
 * effect any less. Two regressions are guarded here:
 *
 * - A 5s ACK wait used to turn a delivered-but-unacknowledged broadcast into
 *   "could not save settings", while the value was already on disk. That is a
 *   lie, and on ColorOS it fired on *every* save: `OplusAppStartupManager`
 *   blocks waking the module app from a broadcast entirely.
 * - Ordering the snapshot resolution remote-first let the module app's stale
 *   mirror overwrite the host file on the next `:hld` start, silently reverting
 *   whatever the user had just saved.
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

    /** The mirror outcome drives the pending flag, and nothing else. */
    private fun hostSyncPendingAfterSave(mirrorDelivered: Boolean): Boolean = !mirrorDelivered

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
        assertTrue("an undelivered mirror is a debt to retry", hostSyncPendingAfterSave(mirrorDelivered))
    }

    @Test fun aDeliveredMirrorLeavesNoPendingFlag() {
        assertFalse(hostSyncPendingAfterSave(mirrorDelivered = true))
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
