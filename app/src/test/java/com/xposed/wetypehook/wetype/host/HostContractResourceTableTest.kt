package com.xposed.wetypehook.wetype.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R 类的字段名判据。
 *
 * 宿主 R 类的类名是 R8 产物（`plugin.hld.o` 这类单字母短名），字段名却是源码标识符、从不被改。
 * 五个 R 类长相完全一样（都是清一色静态 int），分不出形状，只能按「声明了哪些字段名」分辨。
 * 这里钉住分辨原语本身，夹具用 Java 写以还原宿主的真实形状（见 [ResourceTableFixtures]）。
 */
class HostContractResourceTableTest {

    @Test
    fun markersSeparateTwoSiblingTables() {
        assertTrue(declaresAtLeast(ResourceTableFixtures.AttrTable::class.java, RESOURCE_ATTR_MARKERS, 2))
        assertFalse("共用了一个名字也不够", declaresAtLeast(ResourceTableFixtures.ColorTable::class.java, RESOURCE_ATTR_MARKERS, 2))
        assertTrue(declaresAtLeast(ResourceTableFixtures.ColorTable::class.java, RESOURCE_COLOR_MARKERS, 2))
        assertFalse(declaresAtLeast(ResourceTableFixtures.AttrTable::class.java, RESOURCE_COLOR_MARKERS, 2))
        assertTrue(declaresAtLeast(ResourceTableFixtures.DimenTable::class.java, RESOURCE_DIMEN_MARKERS, 3))
    }

    @Test
    fun resourceTableMustBeAllStaticInts() {
        assertTrue(looksLikeResourceTable(ResourceTableFixtures.AttrTable::class.java, minFields = 3))
        assertFalse(
            "有非 int 静态字段就不是资源表",
            looksLikeResourceTable(ResourceTableFixtures.NotATable::class.java, minFields = 1)
        )
        assertFalse("字段太少不算表", looksLikeResourceTable(ResourceTableFixtures.TinyTable::class.java, minFields = 3))
        assertFalse("真实 R 类有上千个字段，门槛设在 300 上", looksLikeResourceTable(ResourceTableFixtures.AttrTable::class.java))
    }

    @Test
    fun fieldAnchorsNeedDexKitAndFailClosedWithoutIt() {
        val ctx = HostContext(ResourceTableFixtures.AttrTable::class.java.classLoader, null) { null }

        assertNull(
            "拿不到 DexKit 时必须返回 null，让名字候选接手",
            ctx.classByFieldCandidates(HostContractId.RESOURCE_CLASS_ATTR, RESOURCE_ATTR_MARKERS) { true }
        )
        assertNull(ctx.winner)
    }

    @Test
    fun markerListsAreDisjoint() {
        val attr = RESOURCE_ATTR_MARKERS.toSet()
        val color = RESOURCE_COLOR_MARKERS.toSet()
        val dimen = RESOURCE_DIMEN_MARKERS.toSet()

        assertEquals("三个 R 类的分辨锚不能共用一个名字", 9, (attr + color + dimen).size)
        assertTrue((attr intersect color).isEmpty())
        assertTrue((attr intersect dimen).isEmpty())
        assertTrue((color intersect dimen).isEmpty())
    }
}
