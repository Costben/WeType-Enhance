package com.xposed.wetypehook.wetype.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ClipboardBackupArchiveTest {

    @Test
    fun buildFileNameUsesTimestampAndZipSuffix() {
        val timestamp = LocalDateTime.of(2026, 9, 12, 22, 15, 30)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(
            "WeTypeEnhance-clipboard-20260912-221530.zip",
            ClipboardBackupArchive.buildFileName(timestamp)
        )
    }

    @Test
    fun manifestRoundTrip() {
        val manifest = BackupManifest(
            formatVersion = ClipboardBackupArchive.FORMAT_VERSION,
            exportedAt = 1_789_200_000_000L,
            textCount = 211,
            imageCount = 1,
            skippedRemoteImages = 3
        )
        val parsed = ClipboardBackupArchive.parseManifest(ClipboardBackupArchive.manifestJson(manifest))
        assertEquals(manifest, parsed)
    }

    @Test
    fun textItemRoundTripWithEscapes() {
        val content = "引号\" 反斜杠\\ 换行\n 制表\t 回车\r emoji😀 \u0001\u001F 结束"
        val item = BackupTextItem(content = content, createTime = 1_700_000_000_000L)
        val parsed = ClipboardBackupArchive.parseItem(ClipboardBackupArchive.textItemJson(item))
        assertEquals(item, parsed)
    }

    @Test
    fun imageItemRoundTrip() {
        val item = BackupImageItem(
            archivePath = "images/0f1e2d3c.jpg",
            md5 = "0f1e2d3c",
            createTime = 42L,
            sizeBytes = 4096L
        )
        val parsed = ClipboardBackupArchive.parseItem(ClipboardBackupArchive.imageItemJson(item))
        assertEquals(item, parsed)
    }

    @Test
    fun unknownFieldsAreTolerated() {
        val parsed = ClipboardBackupArchive.parseItem(
            """{"type":0,"content":"hello","createTime":7,"future":"x"}"""
        )
        assertEquals(BackupTextItem("hello", 7L), parsed)
    }

    @Test
    fun unicodeEscapesAreDecoded() {
        val parsed = ClipboardBackupArchive.parseItem(
            """{"type":0,"content":"a\u003db\u4e2d\ud83d\ude00","createTime":1}"""
        )
        assertEquals("a=b中😀", (parsed as BackupTextItem).content)
    }

    @Test
    fun malformedOrIncompleteItemsReturnNull() {
        assertNull(ClipboardBackupArchive.parseItem(""))
        assertNull(ClipboardBackupArchive.parseItem("{"))
        assertNull(ClipboardBackupArchive.parseItem("[]"))
        assertNull(ClipboardBackupArchive.parseItem("""{"type":0,"createTime":1}"""))
        assertNull(ClipboardBackupArchive.parseItem("""{"type":1,"file":"images/a.jpg","createTime":1}"""))
        assertNull(ClipboardBackupArchive.parseItem("""{"type":2,"content":"x","createTime":1}"""))
        assertNull(ClipboardBackupArchive.parseManifest("""{"exportedAt":1}"""))
        assertNull(ClipboardBackupArchive.parseManifest("not json"))
    }

    @Test
    fun manifestAllowsFutureFormatVersionButParsesIt() {
        val parsed = ClipboardBackupArchive.parseManifest(
            """{"generator":"WeType-Enhance","formatVersion":99,"exportedAt":5,"textCount":1,"imageCount":0,"skippedRemoteImages":0}"""
        )
        assertEquals(99, parsed?.formatVersion)
    }

    @Test
    fun imageEntryPathKeepsMd5AndExtension() {
        assertTrue(
            ClipboardBackupArchive.imageEntryPath("abc123", "png") == "images/abc123.png"
        )
    }
}
