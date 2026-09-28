package com.xposed.wetypehook.wetype.host

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 宿主契约缓存（[WeTypeHostContracts] 的 encode / decode）的往返。
 *
 * 剪贴板搜索的 companion 契约挂在「静态字段 + 零参 getter」两个载体上。编码只写方法时，
 * 从缓存读回来的句柄字段为空，搜索在每个走缓存的进程里 fail-closed；这里钉住双载体不丢
 * 字段，同时钉住单载体仍然沿用旧描述符，避免格式升级把既有条目一起改掉。
 */
class HostContractCacheRoundTripTest {

    private val classLoader: ClassLoader =
        requireNotNull(HostContractCacheRoundTripTest::class.java.classLoader)

    @Test
    fun keepsFieldWhenHandleCarriesMethodAndField() {
        val handle = HostHandle(
            owner = ArrayList::class.java,
            method = ArrayList::class.java.getDeclaredMethod("size"),
            hostField = Locale::class.java.getDeclaredField("ROOT")
        )

        val encoded = WeTypeHostContracts.encode(handle)
        assertEquals("mf|java.util.ArrayList|size||java.util.Locale|ROOT", encoded)

        val restored = WeTypeHostContracts.decode(encoded!!, classLoader)
        assertNotNull(restored)
        assertEquals("ROOT", restored!!.hostField?.name)
        assertEquals("size", restored.method?.name)
        assertEquals(ArrayList::class.java, restored.owner)
    }

    @Test
    fun keepsMethodOnlyFormForMethodHandles() {
        val handle = HostHandle(
            owner = ArrayList::class.java,
            method = ArrayList::class.java.getDeclaredMethod("size")
        )

        val encoded = WeTypeHostContracts.encode(handle)
        assertEquals("m|java.util.ArrayList|size|", encoded)

        val restored = WeTypeHostContracts.decode(encoded!!, classLoader)
        assertNotNull(restored)
        assertEquals("size", restored!!.method?.name)
        assertNull(restored.hostField)
    }

    @Test
    fun keepsFieldOnlyFormForFieldHandles() {
        val handle = HostHandle(
            owner = Locale::class.java,
            hostField = Locale::class.java.getDeclaredField("ROOT")
        )

        val encoded = WeTypeHostContracts.encode(handle)
        assertEquals("f|java.util.Locale|ROOT", encoded)

        val restored = WeTypeHostContracts.decode(encoded!!, classLoader)
        assertNotNull(restored)
        assertEquals("ROOT", restored!!.hostField?.name)
        assertNull(restored.method)
    }

    @Test
    fun returnsNullWhenMethodDescriptorNoLongerMatches() {
        assertNull(WeTypeHostContracts.decode("m|java.util.ArrayList|noSuchMethod|", classLoader))
    }
}
