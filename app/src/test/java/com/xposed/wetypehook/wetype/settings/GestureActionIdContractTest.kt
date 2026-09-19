package com.xposed.wetypehook.wetype.settings

import com.xposed.wetypehook.wetype.gesture.GestureAction
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Pins the gesture-action id table after 模块设置 was inserted.
 *
 * Two designs were on the table. The rejected one shifted every action from 20
 * up by one so 21 could be freed for the new action; it was dropped because
 * `gesture_bindings_json` travels through backup/WebDAV sync, and a shifted id
 * reads back as a *different live action* on an older build.
 *
 * The shipped one leaves 0..23 exactly as the old builds had them and appends
 * 模块设置 at the one id that was never used, 24. These tests hold that line:
 * a legacy config must survive a read/write cycle byte-identical, and the new
 * action must be the only thing an old build cannot resolve.
 */
class GestureActionIdContractTest {

    private fun slotOf(action: GestureAction): Int =
        JSONObject(WeTypeGestureSettings.serializeBindings(mapOf('q' to action))).getInt("q")

    private fun actionAt(storedId: Int): GestureAction? =
        WeTypeGestureSettings.parseBindings(JSONObject().put("q", storedId).toString())['q']

    @Test fun idsAreContiguousWithNoGaps() {
        val ids = GestureAction.entries.map { it.id }
        assertEquals(ids.sorted(), ids)
        assertEquals((0..24).toList(), ids)
    }

    @Test fun actionCountIsTwentyFive() {
        assertEquals(25, GestureAction.entries.size)
    }

    @Test fun settingsActionsUseDistinctIdsAndTitles() {
        assertEquals("输入法设置", GestureAction.OpenSettings.title)
        assertEquals("模块设置", GestureAction.OpenModuleSettings.title)
        assertEquals(20, GestureAction.OpenSettings.id)
        assertEquals(24, GestureAction.OpenModuleSettings.id)
    }

    /** 0..23 must stay put — these are the ids older builds and synced configs already use. */
    @Test fun legacyIdsAreUnchanged() {
        assertEquals(21, GestureAction.OpenFindWord.id)
        assertEquals(22, GestureAction.MoveCursor.id)
        assertEquals(23, GestureAction.MoveSelect.id)
    }

    /** 24 is the first id no older build ever wrote; that is why the new action lives there. */
    @Test fun newActionSitsOutsideTheLegacyIdRange() {
        assertEquals(24, GestureAction.OpenModuleSettings.id)
        GestureAction.entries
            .filter { it != GestureAction.OpenModuleSettings }
            .forEach { assertTrue("$it encroaches on the new slot", it.id <= 23) }
    }

    @Test fun bothSettingsActionsShareTheKeyLabel() {
        assertEquals("设置", GestureAction.OpenSettings.shortTitle)
        assertEquals("设置", GestureAction.OpenModuleSettings.shortTitle)
    }

    /** Key labels come from shortTitle; the two 设置 actions must not collide with 找字. */
    @Test fun findWordKeepsItsOwnLabel() {
        assertEquals("找字", GestureAction.OpenFindWord.shortTitle)
    }

    // ---- legacy configs read back exactly as written ----

    @Test fun legacySlot20ReadsAsImeSettings() {
        assertEquals(GestureAction.OpenSettings, actionAt(20))
    }

    @Test fun legacySlot21ReadsAsFindWord() {
        assertEquals(GestureAction.OpenFindWord, actionAt(21))
    }

    @Test fun legacySlot22ReadsAsMoveCursor() {
        assertEquals(GestureAction.MoveCursor, actionAt(22))
    }

    @Test fun legacySlot23ReadsAsMoveSelect() {
        assertEquals(GestureAction.MoveSelect, actionAt(23))
    }

    /**
     * The load-bearing one for synced configs: reading a legacy config and writing
     * it straight back must not change a single value, or every sync round-trip
     * would rewrite the user's bindings on other devices.
     *
     * Compared key-by-key — `JSONObject` has no value equality, so asserting on the
     * objects themselves would only ever compare identity.
     */
    @Test fun legacyConfigSurvivesReadWriteUnchanged() {
        val legacyIds = mapOf('z' to 1, 'x' to 4, 'c' to 20, 'v' to 21, 'b' to 22, 'n' to 23)
        val legacy = JSONObject().apply { legacyIds.forEach { (k, v) -> put(k.toString(), v) } }

        val rewritten = JSONObject(
            WeTypeGestureSettings.serializeBindings(WeTypeGestureSettings.parseBindings(legacy.toString()))
        )

        assertEquals(legacy.keys().asSequence().toSet(), rewritten.keys().asSequence().toSet())
        legacy.keys().forEach { key ->
            assertEquals("key $key changed", legacy.getInt(key), rewritten.getInt(key))
        }
    }

    // ---- writes ----

    @Test fun everyActionWritesItsOwnId() {
        GestureAction.entries.filter { it != GestureAction.None }.forEach { action ->
            assertEquals("$action did not write its own id", action.id, slotOf(action))
        }
    }

    /** No two actions may share a slot, and every action must read back as itself. */
    @Test fun everyActionRoundTripsThroughItsOwnSlot() {
        val usedSlots = mutableMapOf<Int, GestureAction>()
        GestureAction.entries.filter { it != GestureAction.None }.forEach { action ->
            val slot = slotOf(action)
            val clash = usedSlots.put(slot, action)
            assertNull("slot $slot claimed by both $clash and $action", clash)
            assertEquals("action $action did not round-trip", action, actionAt(slot))
        }
    }

    /**
     * An older build reads the new slot as an unknown id and degrades to 未绑定.
     * If 模块设置 had reused 0..23 it would instead surface as a *working* wrong action.
     */
    @Test fun olderBuildsSeeTheNewSlotAsUnboundNotAsAnotherAction() {
        GestureAction.entries
            .filter { it.id <= 23 }
            .forEach { assertNotEquals(GestureAction.OpenModuleSettings.id, it.id) }
    }

    @Test fun mixedConfigRoundTrips() {
        val map = mapOf(
            'q' to GestureAction.OpenSettings,
            'w' to GestureAction.OpenModuleSettings,
            'e' to GestureAction.Copy
        )
        assertEquals(
            map,
            WeTypeGestureSettings.parseBindings(WeTypeGestureSettings.serializeBindings(map))
        )
    }
}
