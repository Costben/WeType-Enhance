package com.xposed.wetypehook.wetype.hook

/** Main-thread search lifecycle. Text delivery is deliberately not a submit event. */
internal class ClipboardSearchSubmitSession {
    enum class Phase { CLOSED, EDITING, COMMITTING, EXITING, OPENING, COMPLETE, FAILED }
    var id = 0L
        private set
    var phase = Phase.CLOSED
        private set
    private var nativeCommitRequired = false
    private var nativeCommitDelivered = false
    var keyword = ""
        private set

    fun open(): Long {
        id++
        phase = Phase.EDITING
        keyword = ""
        return id
    }

    fun edit(text: String) {
        if (phase == Phase.EDITING || phase == Phase.COMMITTING) keyword = text
    }

    fun confirm(): Long? {
        if (phase != Phase.EDITING) return null
        phase = Phase.COMMITTING
        nativeCommitRequired = false
        nativeCommitDelivered = false
        return id
    }

    fun requireNativeCommit(token: Long) {
        if (!at(token, Phase.COMMITTING)) return
        nativeCommitRequired = true
        nativeCommitDelivered = false
    }

    fun nativeTextDelivered(token: Long, text: String) {
        if (!at(token, Phase.COMMITTING)) return
        nativeCommitDelivered = true
        keyword = text
    }

    fun readyToSnapshot(token: Long, pendingEmpty: Boolean, composingEnded: Boolean): Boolean =
        at(token, Phase.COMMITTING) && pendingEmpty && composingEnded &&
            (!nativeCommitRequired || nativeCommitDelivered)

    fun committed(token: Long, text: String): Boolean {
        if (!at(token, Phase.COMMITTING)) return false
        keyword = text.replace("\r", "").replace("\n", "")
        phase = Phase.EXITING
        return true
    }

    /** The caller dispatches native navigation only on this one successful transition. */
    fun shellExited(token: Long, managerIdle: Boolean, cardDetached: Boolean): Boolean {
        if (!at(token, Phase.EXITING) || !managerIdle || !cardDetached) return false
        phase = Phase.OPENING
        return true
    }

    fun observe(token: Long, page: Page): Boolean {
        if (!at(token, Phase.OPENING) || !page.visibleResult) return false
        phase = Phase.COMPLETE
        return true
    }

    fun fail(token: Long) {
        if (token == id && phase != Phase.CLOSED && phase != Phase.COMPLETE) phase = Phase.FAILED
    }

    fun cancel() { id++; phase = Phase.CLOSED; keyword = "" }
    fun at(token: Long, expected: Phase) = token == id && phase == expected

    data class Page(val currentClipboardHost: Boolean, val hostVisible: Boolean,
                    val clipboardTabSelected: Boolean, val listVisible: Boolean,
                    val emptyVisible: Boolean) {
        val visibleResult get() = currentClipboardHost && hostVisible && clipboardTabSelected &&
            (listVisible || emptyVisible)
    }
}

/** 搜索框卡片圆角偏移量（dp）：卡片半径比输入法圆角少 8dp（下限 0dp） */
internal const val CARD_CORNER_RADIUS_OFFSET_DP = 8f

internal fun resolveCardCornerRadiusDp(baseRadiusDp: Float): Float =
    maxOf(0f, baseRadiusDp - CARD_CORNER_RADIUS_OFFSET_DP)
