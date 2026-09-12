package com.xposed.wetypehook.wetype.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardImageEntryLogicTest {

    @Test
    fun parseJsonPayloadWithEscapedBase64() {
        val path = """{"version":1,"key":"YWJj\u003d\u003d","md5":"AABB","path":"https://example.com/a.jpg?x=1"}"""
        val payload = ClipboardImageEntryLogic.parseRemotePayload(path)
        assertEquals("https://example.com/a.jpg?x=1", payload?.url)
        assertEquals("YWJj==", payload?.key)
        assertEquals("AABB", payload?.md5)
        assertTrue(payload?.encrypted == true)
    }

    @Test
    fun parseJsonPayloadWithDifferentFieldOrder() {
        val path = """{"path":"https://cdn.example.com/x.png","md5":"11","version":2,"key":"S0VZ"}"""
        val payload = ClipboardImageEntryLogic.parseRemotePayload(path)
        assertEquals("https://cdn.example.com/x.png", payload?.url)
        assertEquals("S0VZ", payload?.key)
        assertEquals("11", payload?.md5)
        assertTrue(payload?.encrypted == true)
    }

    @Test
    fun parsePlainUrlWithoutVersionIsUnencrypted() {
        val payload = ClipboardImageEntryLogic.parseRemotePayload("https://example.com/plain.jpg")
        assertEquals("https://example.com/plain.jpg", payload?.url)
        assertFalse(payload?.encrypted == true)
    }

    @Test
    fun parseInvalidOrMissingPayloadReturnsNull() {
        assertNull(ClipboardImageEntryLogic.parseRemotePayload(null))
        assertNull(ClipboardImageEntryLogic.parseRemotePayload(""))
        assertNull(ClipboardImageEntryLogic.parseRemotePayload("""{"version":1,"key":"S0VZ"}"""))
    }

    @Test
    fun classifyOnlyImages() {
        assertEquals(
            ClipboardImageRowState.NOT_IMAGE,
            ClipboardImageEntryLogic.classify(
                itemType = 0L,
                receiveTimestampFromServer = 0L,
                pathType = 0,
                localFileExists = true
            )
        )
    }

    @Test
    fun classifyLocalImageSingleRow() {
        assertEquals(
            ClipboardImageRowState.LOCAL_THUMBNAIL,
            ClipboardImageEntryLogic.classify(
                itemType = 1L,
                receiveTimestampFromServer = 0L,
                pathType = 1,
                localFileExists = false
            )
        )
    }

    @Test
    fun classifyRemoteImageStates() {
        assertEquals(
            ClipboardImageRowState.REMOTE_PENDING,
            ClipboardImageEntryLogic.classify(
                itemType = 1L,
                receiveTimestampFromServer = 1_700_000_000_000L,
                pathType = 1,
                localFileExists = false
            )
        )
        assertEquals(
            ClipboardImageRowState.REMOTE_THUMBNAIL,
            ClipboardImageEntryLogic.classify(
                itemType = 1L,
                receiveTimestampFromServer = 1_700_000_000_000L,
                pathType = 0,
                localFileExists = true
            )
        )
        assertEquals(
            ClipboardImageRowState.REMOTE_PENDING,
            ClipboardImageEntryLogic.classify(
                itemType = 1L,
                receiveTimestampFromServer = 1_700_000_000_000L,
                pathType = 0,
                localFileExists = false
            )
        )
    }

    @Test
    fun classifyLocalImageIndependentOfPathType() {
        // 本地图片即便在不同 pathType 下，只要是本地 (receiveTimestampFromServer <= 0) 均决策为 LOCAL_THUMBNAIL
        assertEquals(
            ClipboardImageRowState.LOCAL_THUMBNAIL,
            ClipboardImageEntryLogic.classify(
                itemType = 1L,
                receiveTimestampFromServer = 0L,
                pathType = 0,
                localFileExists = true
            )
        )
    }

    @Test
    fun remotePendingTextMatchesSpec() {
        assertEquals("来自关联设备的图片", ClipboardImageEntryLogic.REMOTE_PENDING_TEXT)
    }
}
