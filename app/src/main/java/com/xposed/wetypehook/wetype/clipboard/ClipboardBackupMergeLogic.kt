package com.xposed.wetypehook.wetype.clipboard

/** 合并导入结果：保留的条目与因重复而跳过的条数。 */
internal data class MergeOutcome<T>(
    val kept: List<T>,
    val skippedDuplicates: Int
)

/**
 * 合并导入去重决策（ADR-0004）：
 * 文本按正文精确去重，图片按内容 md5 去重；同时覆盖备份包内重复与库内已存在两种情况。
 */
internal object ClipboardBackupMergeLogic {

    fun mergeTexts(
        items: List<BackupTextItem>,
        existingContents: Set<String>
    ): MergeOutcome<BackupTextItem> {
        val seen = HashSet<String>(items.size)
        val kept = ArrayList<BackupTextItem>(items.size)
        var skipped = 0
        for (item in items) {
            if (!seen.add(item.content) || item.content in existingContents) {
                skipped++
                continue
            }
            kept.add(item)
        }
        return MergeOutcome(kept, skipped)
    }

    fun mergeImages(
        items: List<BackupImageItem>,
        existingMd5: Set<String>
    ): MergeOutcome<BackupImageItem> {
        val seen = HashSet<String>(items.size)
        val kept = ArrayList<BackupImageItem>(items.size)
        var skipped = 0
        for (item in items) {
            if (item.md5.isEmpty() || !seen.add(item.md5) || item.md5 in existingMd5) {
                skipped++
                continue
            }
            kept.add(item)
        }
        return MergeOutcome(kept, skipped)
    }
}
