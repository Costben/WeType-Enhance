package com.xposed.wetypehook.wetype.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardBackupMergeLogicTest {

    @Test
    fun textsSkipExistingAndInBatchDuplicates() {
        val items = listOf(
            BackupTextItem("a", 1L),
            BackupTextItem("b", 2L),
            BackupTextItem("a", 3L),
            BackupTextItem("c", 4L)
        )
        val outcome = ClipboardBackupMergeLogic.mergeTexts(items, setOf("c"))
        assertEquals(listOf(BackupTextItem("a", 1L), BackupTextItem("b", 2L)), outcome.kept)
        assertEquals(2, outcome.skippedDuplicates)
    }

    @Test
    fun textsEmptyExistingKeepsFirstOccurrencesInOrder() {
        val items = List(5) { BackupTextItem("same", it.toLong()) }
        val outcome = ClipboardBackupMergeLogic.mergeTexts(items, emptySet())
        assertEquals(listOf(BackupTextItem("same", 0L)), outcome.kept)
        assertEquals(4, outcome.skippedDuplicates)
    }

    @Test
    fun imagesSkipEmptyMd5ExistingAndInBatchDuplicates() {
        val items = listOf(
            BackupImageItem("images/a.jpg", "aa", 1L, 10L),
            BackupImageItem("images/b.jpg", "bb", 2L, 20L),
            BackupImageItem("images/c.jpg", "aa", 3L, 30L),
            BackupImageItem("images/d.jpg", "", 4L, 40L),
            BackupImageItem("images/e.jpg", "cc", 5L, 50L)
        )
        val outcome = ClipboardBackupMergeLogic.mergeImages(items, setOf("cc"))
        assertEquals(
            listOf(
                BackupImageItem("images/a.jpg", "aa", 1L, 10L),
                BackupImageItem("images/b.jpg", "bb", 2L, 20L)
            ),
            outcome.kept
        )
        assertEquals(3, outcome.skippedDuplicates)
    }
}
