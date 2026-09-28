package com.xposed.wetypehook.wetype.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板条目 / 图片 / 搜索链那批契约的语义校验回调。
 *
 * 这几个回调只吃 `Class` 反射，不碰 DexKit，所以能在 JVM 单测里直接钉住；字符串锚「两版唯一
 * 命中」那半边靠离线 dexdump 核对，单测覆盖不到。
 */
class HostContractClipboardAnchorTest {

    /** 宿主条目行的形状：十余个简单类型实例字段（`clipboard.C` 是 18 个）。 */
    private class EntityRow {
        var a = 0
        var b = 0
        var c = 0
        var d = 0
        var e = 0
        var f = 0
        var g = 0
        var h = 0
        var i = 0
        var j = 0
        var k = 0
        var l = 0
    }

    /** 字段够多，但混进了非简单类型 —— 不是行对象。 */
    private class NotARow {
        var a = 0
        var b = 0
        var c = 0
        var d = 0
        var e = 0
        var f = 0
        var g = 0
        var h = 0
        var i = 0
        var j = 0
        var k = 0
        var l = StringBuilder()
    }

    /** 字段太少。 */
    private class TooFewFields {
        var a = 0
        var b = 0
    }

    private class ListSetterHost {
        @Suppress("unused")
        fun setList(list: List<*>) {
        }
    }

    private class ArraySetterHost {
        @Suppress("unused")
        fun setList(list: ArrayList<*>) {
        }
    }

    @Test
    fun entityRowNeedsManySimpleFields() {
        assertTrue(looksLikeEntityRow(EntityRow::class.java))
        assertFalse("混了非简单类型就不是行对象", looksLikeEntityRow(NotARow::class.java))
        assertFalse("字段太少不算行对象", looksLikeEntityRow(TooFewFields::class.java))
    }

    @Test
    fun listSetterNeedsExactlyListParameter() {
        assertTrue(takesListSetter(ListSetterHost::class.java))
        assertFalse("参数是 ArrayList 不算 setList(List)", takesListSetter(ArraySetterHost::class.java))
        assertFalse(takesListSetter(EntityRow::class.java))
    }

    @Test
    fun localChainIsNeitherConstraintLayoutNorRecyclerViewHolder() {
        assertFalse(isConstraintLayoutSubclass(EntityRow::class.java))
        assertFalse(bindsRecyclerViewHolder(EntityRow::class.java))
    }

    @Test
    fun stringAnchorsFailClosedWithoutDexKit() {
        val ctx = HostContext(EntityRow::class.java.classLoader, null) { null }

        assertNull(
            "拿不到 DexKit 时必须返回 null，让名字候选接手",
            ctx.classByDexStrings(HostContractId.CLIPBOARD_MANAGER, listOf("WxIme.ImeClipboardMgr")) { true }
        )
        assertNull(ctx.winner)
    }
}
