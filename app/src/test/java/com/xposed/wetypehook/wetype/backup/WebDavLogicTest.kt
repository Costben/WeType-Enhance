package com.xposed.wetypehook.wetype.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WebDavLogicTest {

    @Test
    fun normalizeBaseAddsHttpsAndTrimsTrailingSlash() {
        assertEquals("https://dav.example.com", WebDavPaths.normalizeBase("dav.example.com/"))
        assertEquals("https://dav.example.com", WebDavPaths.normalizeBase(" https://dav.example.com// "))
        assertEquals("http://192.168.1.10:5244/dav", WebDavPaths.normalizeBase("http://192.168.1.10:5244/dav/"))
        assertEquals("", WebDavPaths.normalizeBase("  "))
    }

    @Test
    fun joinEncodesSegments() {
        assertEquals(
            "https://dav.example.com/WeType%20Backup/2026/",
            WebDavPaths.join("https://dav.example.com", listOf("WeType Backup", "2026")) + "/"
        )
        assertEquals(
            "https://dav.example.com/%E5%A4%87%E4%BB%BD/a.zip",
            WebDavPaths.join("https://dav.example.com", listOf("备份", "a.zip"))
        )
    }

    @Test
    fun fileNameFromHrefDecodesPercentEscapes() {
        assertEquals(
            "older file.zip",
            WebDavPaths.fileNameFromHref("/dav/backup/older%20file.zip")
        )
        assertEquals("a.zip", WebDavPaths.fileNameFromHref("/dav/backup/a.zip?token=1"))
        assertEquals("备份.zip", WebDavPaths.fileNameFromHref("/dav/%E5%A4%87%E4%BB%BD.zip"))
    }

    @Test
    fun resolveHrefHandlesAbsoluteAndRelative() {
        assertEquals(
            "https://dav.example.com/dav/backup/a.zip",
            WebDavPaths.resolveHref("https://dav.example.com/dav/backup", "a.zip")
        )
        assertEquals(
            "https://dav.example.com/other/a.zip",
            WebDavPaths.resolveHref("https://dav.example.com/dav/backup", "/other/a.zip")
        )
        assertEquals(
            "https://cdn.example.com/a.zip",
            WebDavPaths.resolveHref("https://dav.example.com/dav/", "https://cdn.example.com/a.zip")
        )
    }

    @Test
    fun propfindParsesZipEntriesSortedByTime() {
        val newer = Instant.parse("2026-09-12T14:15:30Z").toEpochMilli()
        val older = Instant.parse("2026-09-01T01:02:03Z").toEpochMilli()
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/backup/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype>
                    <d:getlastmodified>Sat, 12 Sep 2026 14:15:30 GMT</d:getlastmodified>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/backup/WeTypeEnhance-clipboard-20260912-221530.zip</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype/>
                    <d:getcontentlength>123456</d:getcontentlength>
                    <d:getlastmodified>Sat, 12 Sep 2026 14:15:30 GMT</d:getlastmodified>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/backup/older%20file.zip</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype/>
                    <d:getcontentlength>10</d:getcontentlength>
                    <d:getlastmodified>Tue, 01 Sep 2026 01:02:03 GMT</d:getlastmodified>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/backup/notes.txt</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype/>
                    <d:getcontentlength>1</d:getcontentlength>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/backup/sub/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val entries = WebDavPropfindParser.parse(xml, "https://dav.example.com/dav/backup")
        assertEquals(2, entries.size)
        assertEquals("WeTypeEnhance-clipboard-20260912-221530.zip", entries[0].name)
        assertEquals(newer, entries[0].lastModified)
        assertEquals(123456L, entries[0].sizeBytes)
        assertEquals("older file.zip", entries[1].name)
        assertEquals(older, entries[1].lastModified)
        assertEquals(
            "https://dav.example.com/dav/backup/older%20file.zip",
            entries[1].url
        )
    }

    @Test
    fun propfindPrefersSuccessfulPropstat() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/a.zip</d:href>
                <d:propstat>
                  <d:prop><d:getcontentlength>0</d:getcontentlength></d:prop>
                  <d:status>HTTP/1.1 403 Forbidden</d:status>
                </d:propstat>
                <d:propstat>
                  <d:prop>
                    <d:getcontentlength>55</d:getcontentlength>
                    <d:getlastmodified>Sat, 12 Sep 2026 14:15:30 GMT</d:getlastmodified>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val entries = WebDavPropfindParser.parse(xml, "https://dav.example.com/")
        assertEquals(1, entries.size)
        assertEquals(55L, entries[0].sizeBytes)
    }

    @Test
    fun retentionKeepsNewestN() {
        val entries = listOf(
            WebDavRemoteEntry("c.zip", "https://x/c.zip", 0, 300),
            WebDavRemoteEntry("b.zip", "https://x/b.zip", 0, 200),
            WebDavRemoteEntry("a.zip", "https://x/a.zip", 0, 100)
        )
        assertEquals(listOf("a.zip"), WebDavRetention.selectForDeletion(entries, 2).map { it.name })
        assertTrue(WebDavRetention.selectForDeletion(entries, 5).isEmpty())
        assertTrue(WebDavRetention.selectForDeletion(entries, 0).isEmpty())
    }
}
