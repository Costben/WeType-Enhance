package com.xposed.wetypehook.wetype.hook

import org.junit.Assert.*
import org.junit.Test

class ClipboardSearchSubmitSessionTest {
    @Test fun composingTypingSpaceCandidateAndDeletionOnlyEdit() {
        val session = ClipboardSearchSubmitSession()
        session.open()
        for (text in listOf("n", "ni", "你", "你 ", "你", "")) {
            session.edit(text)
            assertEquals(ClipboardSearchSubmitSession.Phase.EDITING, session.phase)
        }
    }
    @Test fun confirmationUsesFinalCommitAndSurvivesNativeClear() {
        val s = ClipboardSearchSubmitSession(); s.open(); s.edit("ni")
        val id = s.confirm()!!
        assertNull(s.confirm())
        s.edit("你")
        assertTrue(s.committed(id, s.keyword))
        s.edit("")
        assertEquals("你", s.keyword)
        assertFalse(s.shellExited(id, true, false))
        assertFalse(s.shellExited(id, false, true))
        assertTrue(s.shellExited(id, true, true))
        assertFalse(s.shellExited(id, true, true))
        assertNull(s.confirm())
    }
    @Test fun staleCallbacksCannotNavigateOrClearNewSession() {
        val s = ClipboardSearchSubmitSession(); s.open(); val old = s.confirm()!!
        s.cancel(); s.open(); s.edit("new")
        assertFalse(s.committed(old, "old")); assertFalse(s.shellExited(old, true, true))
        s.fail(old)
        assertEquals("new", s.keyword)
        assertEquals(ClipboardSearchSubmitSession.Phase.EDITING, s.phase)
    }
    @Test fun invocationAndHiddenCachedPagesAreNotLanding() {
        val s = ClipboardSearchSubmitSession(); s.open(); val id = s.confirm()!!
        s.committed(id, "term"); s.shellExited(id, true, true)
        val hidden = ClipboardSearchSubmitSession.Page(true, false, true, true, false)
        assertFalse(s.observe(id, hidden))
        assertFalse(s.observe(id, hidden.copy(hostVisible=true, currentClipboardHost=false)))
        assertFalse(s.observe(id, hidden.copy(hostVisible=true, clipboardTabSelected=false)))
        assertFalse(s.observe(id, hidden.copy(hostVisible=true, listVisible=false)))
        assertTrue(s.observe(id, hidden.copy(hostVisible=true)))
        assertFalse(s.observe(id, hidden.copy(hostVisible=true)))
    }
    @Test fun nativeEmptyResultIsAValidLanding() {
        val s=ClipboardSearchSubmitSession(); s.open(); val id=s.confirm()!!
        s.committed(id,"not found"); s.shellExited(id,true,true)
        assertTrue(s.observe(id,ClipboardSearchSubmitSession.Page(true,true,true,false,true)))
        assertEquals("not found",s.keyword)
    }
    @Test fun timeoutKeepsSnapshotAndStopsNavigation() {
        val s=ClipboardSearchSubmitSession(); s.open(); val id=s.confirm()!!
        s.committed(id,"keep"); s.fail(id)
        assertFalse(s.shellExited(id,true,true)); assertEquals("keep",s.keyword)
    }
    @Test fun asyncNativeEmitMustDeliverAfterOriginalCommitBeforeSnapshot() {
        val s=ClipboardSearchSubmitSession(); s.open(); s.edit("ni"); val id=s.confirm()!!
        s.requireNativeCommit(id)
        // Native w() calls finishComposingText before scheduling the engine. That is not the result.
        assertFalse(s.readyToSnapshot(id, true, true))
        s.nativeTextDelivered(id, "你")
        assertFalse(s.readyToSnapshot(id, false, true))
        assertFalse(s.readyToSnapshot(id, true, false))
        assertTrue(s.readyToSnapshot(id, true, true))
        s.committed(id,s.keyword)
        s.nativeTextDelivered(id, "late")
        assertEquals("你",s.keyword)
    }
    @Test fun candidateDeliveryWithoutConfirmationCannotCreateIntent() {
        val s=ClipboardSearchSubmitSession(); val id=s.open(); s.edit("你")
        s.nativeTextDelivered(id,"你"); s.requireNativeCommit(id)
        assertFalse(s.readyToSnapshot(id,true,true))
        assertEquals(ClipboardSearchSubmitSession.Phase.EDITING,s.phase)
    }
    @Test fun cardCornerRadiusIs8dpLessThanKeyboardCornerRadiusWithZeroFloor() {
        assertEquals(8f, CARD_CORNER_RADIUS_OFFSET_DP, 0.001f)
        assertEquals(16f, resolveCardCornerRadiusDp(24f), 0.001f)
        assertEquals(2f, resolveCardCornerRadiusDp(10f), 0.001f)
        assertEquals(0f, resolveCardCornerRadiusDp(8f), 0.001f)
        assertEquals(0f, resolveCardCornerRadiusDp(5f), 0.001f)
        assertEquals(0f, resolveCardCornerRadiusDp(0f), 0.001f)
    }
}
