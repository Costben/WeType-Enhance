package com.xposed.wetypehook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [REPLICA_TOOLBAR_ICON_PATHS] 的完整性约束。
 *
 * 这些路径串上千字符、要分行抄进源码，抄写时最容易丢的就是坐标之间的空格 —— 丢一个就把相邻两个
 * 坐标粘成一个数字，路径会静默画歪而不会报错。所以这里只认 `M` / `L` / `Z` 加数字，
 * 空白一律不许出现；坐标还必须落在 64×64 视口附近，截断或丢数字都会当场越界。
 */
class ReplicaToolbarIconPathsTest {

    private val allowed = Regex("^[MLZ0-9.,-]+$")
    private val command = Regex("([ML])(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)")

    @Test
    fun thereAreSevenIcons() {
        assertEquals(7, REPLICA_TOOLBAR_ICON_PATHS.size)
    }

    @Test
    fun everyPathIsFreeOfWhitespace() {
        REPLICA_TOOLBAR_ICON_PATHS.forEachIndexed { index, data ->
            assertTrue(
                "icon${index + 1} 里出现了空白：抄写时丢空格会把相邻两个坐标粘在一起",
                data.none { it.isWhitespace() }
            )
        }
    }

    @Test
    fun everyPathUsesOnlyMoveLineAndClose() {
        REPLICA_TOOLBAR_ICON_PATHS.forEachIndexed { index, data ->
            assertTrue("icon${index + 1} 含非法字符", allowed.matches(data))
            assertTrue("icon${index + 1} 必须以 M 开头", data.startsWith("M"))
            assertTrue("icon${index + 1} 必须以 Z 结尾", data.endsWith("Z"))
        }
    }

    @Test
    fun everySubpathIsClosed() {
        REPLICA_TOOLBAR_ICON_PATHS.forEachIndexed { index, data ->
            assertEquals(
                "icon${index + 1} 的 M 与 Z 数量对不上",
                data.count { it == 'M' },
                data.count { it == 'Z' }
            )
        }
    }

    @Test
    fun everyCoordinateSitsInsideTheViewport() {
        REPLICA_TOOLBAR_ICON_PATHS.forEachIndexed { index, data ->
            val matches = command.findAll(data).toList()
            assertEquals(
                "icon${index + 1} 里每条 M/L 都必须紧跟一对坐标",
                data.count { it == 'M' || it == 'L' },
                matches.size
            )
            matches.forEach { m ->
                val x = m.groupValues[2].toFloat()
                val y = m.groupValues[3].toFloat()
                assertTrue("icon${index + 1} x 越界：$x", x in -1f..ReplicaToolbarIcons.VIEWPORT_PX + 1f)
                assertTrue("icon${index + 1} y 越界：$y", y in -1f..ReplicaToolbarIcons.VIEWPORT_PX + 1f)
            }
        }
    }

    /**
     * 字形指纹：长度加一个 31 进制滚动散列。
     *
     * 上面几条只能挡住「丢空格」和「越界」，挡不住把 `15.50` 敲成 `15.05` 这种改动 ——
     * 路径照样合法、照样在视口内，只是那颗图标画歪了，而预览里歪一点点肉眼看不出来。
     * 这里把描图的结果整体钉死：改任何一个字符都会失败，要改就得连着重新量一遍。
     */
    @Test
    fun everyPathMatchesTheTracedFingerprint() {
        val fingerprints = listOf(
            727494149L, // icon1
            963455910L, // icon2
            -782401012L, // icon3
            -1211618602L, // icon4
            1216409087L, // icon5
            -693252821L, // icon6
            1644089149L // icon7
        )
        REPLICA_TOOLBAR_ICON_PATHS.forEachIndexed { index, data ->
            val hash = data.fold(0) { acc, c -> acc * 31 + c.code }
            assertEquals(
                "icon${index + 1} 的路径串与描图结果对不上（改了坐标却没重新描图？）",
                fingerprints[index],
                hash.toLong()
            )
        }
    }
}
