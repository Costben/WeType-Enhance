package com.xposed.wetypehook.wetype.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KeyboardPreviewLiveReloadTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun missingFilesProduceNullStamp() {
        val file = File(temporaryFolder.root, "wetype_settings.xml")

        assertNull(preferencesFileStamp(file))
    }

    @Test
    fun contentChangeMovesTheStamp() {
        val file = temporaryFolder.newFile("wetype_settings.xml")
        file.writeText("<map/>")
        val before = preferencesFileStamp(file)

        file.writeText("<map><int name=\"corner\" value=\"24\"/></map>")

        assertNotEquals(before, preferencesFileStamp(file))
    }

    @Test
    fun timestampChangeAloneMovesTheStamp() {
        val file = temporaryFolder.newFile("wetype_settings.xml")
        file.writeText("<map/>")
        file.setLastModified(1_000_000L)
        val before = preferencesFileStamp(file)

        file.setLastModified(2_000_000L)

        assertNotEquals(before, preferencesFileStamp(file))
    }

    @Test
    fun idleFileKeepsTheSameStamp() {
        val file = temporaryFolder.newFile("wetype_settings.xml")
        file.writeText("<map/>")
        file.setLastModified(1_000_000L)

        assertEquals(preferencesFileStamp(file), preferencesFileStamp(file))
    }

    /** 写盘期间有效内容在 `.bak` 里，只看 `.xml` 会漏掉那半个窗口。 */
    @Test
    fun backupFileChangeAlsoMovesTheStamp() {
        val file = temporaryFolder.newFile("wetype_settings.xml")
        val backup = File(temporaryFolder.root, "wetype_settings.xml.bak")
        file.writeText("<map/>")
        val before = preferencesFileStamp(file, backup)

        backup.writeText("<map/>")

        assertNotEquals(before, preferencesFileStamp(file, backup))
    }
}
