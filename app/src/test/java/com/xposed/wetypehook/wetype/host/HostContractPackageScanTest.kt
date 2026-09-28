package com.xposed.wetypehook.wetype.host

import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「整包扫描」那批契约的语义校验回调。
 *
 * 这些回调只吃 `Class` 反射，所以能在 JVM 单测里直接钉住；「包内唯一命中」那半边靠离线
 * dexdump 在两版宿主上核对（判据要能同时区分 3.5.4 与 4.0.0 的整包类），单测覆盖不到。
 */
class HostContractPackageScanTest {

    // ---- 浮窗管理类：静态自引用字段 + 静态 9 参重排入口 ----

    private class FloatPanel {
        companion object {
            @JvmField
            val INSTANCE = FloatPanel()

            @JvmStatic
            fun visible(): Boolean = true

            @JvmStatic
            @Suppress("UNUSED_PARAMETER")
            fun u0(
                self: FloatPanel,
                a: Int,
                b: Int,
                c: Int,
                d: Boolean,
                e: Boolean,
                f: Boolean,
                g: Int,
                h: Any?
            ) {
            }
        }
    }

    /** 少了静态自引用字段。 */
    private class FloatPanelNoSingleton {
        companion object {
            @JvmStatic
            @Suppress("UNUSED_PARAMETER")
            fun u0(
                self: FloatPanelNoSingleton,
                a: Int,
                b: Int,
                c: Int,
                d: Boolean,
                e: Boolean,
                f: Boolean,
                g: Int,
                h: Any?
            ) {
            }
        }
    }

    /** 重排入口只有 8 参 —— 形状对不上。 */
    private class FloatPanelShortEntry {
        companion object {
            @JvmField
            val INSTANCE = FloatPanelShortEntry()

            @JvmStatic
            @Suppress("UNUSED_PARAMETER")
            fun u0(
                self: FloatPanelShortEntry,
                a: Int,
                b: Int,
                c: Int,
                d: Boolean,
                e: Boolean,
                g: Int,
                h: Any?
            ) {
            }
        }
    }

    // ---- 皮肤绑定工具类：无实例字段、方法全静态、三条 View 形状 ----

    private class SkinBinder {
        companion object {
            @JvmStatic
            @Suppress("UNUSED_PARAMETER")
            fun a(parent: ViewGroup, child: View, index: Int, params: ViewGroup.LayoutParams) {
            }

            @JvmStatic
            @Suppress("UNUSED_PARAMETER")
            fun b(view: View) {
            }

            @JvmStatic
            @Suppress("UNUSED_PARAMETER")
            fun c(view: View, a: Float, b: Float): Boolean = false
        }
    }

    /** 多了一个实例字段 —— 宿主的皮肤工具类没有实例状态。 */
    private class SkinBinderWithState {
        @Suppress("unused")
        val state = 0

        companion object {
            @JvmStatic
            @Suppress("UNUSED_PARAMETER")
            fun a(parent: ViewGroup, child: View, index: Int, params: ViewGroup.LayoutParams) {
            }

            @JvmStatic
            @Suppress("UNUSED_PARAMETER")
            fun b(view: View) {
            }

            @JvmStatic
            @Suppress("UNUSED_PARAMETER")
            fun c(view: View, a: Float, b: Float): Boolean = false
        }
    }

    /** 三条形状只凑得齐两条。 */
    private class SkinBinderPartial {
        companion object {
            @JvmStatic
            @Suppress("UNUSED_PARAMETER")
            fun b(view: View) {
            }

            @JvmStatic
            @Suppress("UNUSED_PARAMETER")
            fun c(view: View, a: Float, b: Float): Boolean = false
        }
    }

    // ---- 下拉语言项：int + String 两个实例字段、构造 (int,String)、两个取值方法 ----

    @Suppress("unused")
    private class DropdownItem(private val id: Int, private val label: String) {
        fun a(): Int = id

        fun b(): String = label
    }

    @Suppress("unused")
    private class DropdownItemExtraField(
        private val id: Int,
        private val label: String,
        private val extra: Int
    ) {
        fun a(): Int = id

        fun b(): String = label
    }

    @Suppress("unused")
    private class DropdownItemOtherGetterNames(private val id: Int, private val label: String) {
        fun a(): Int = id

        fun label(): String = label
    }

    // ---- 图片预览页键盘：预览宿主的直接子类 + 同时持有条目与卡片实例字段 ----

    private open class PreviewHost

    private class PreviewItem

    private class PreviewCard

    @Suppress("unused")
    private class PreviewKeyboard : PreviewHost() {
        private val item: PreviewItem? = null
        private val card: PreviewCard? = null
    }

    /** 同一个宿主的另一个子类：只挂条目，不挂卡片。 */
    @Suppress("unused")
    private class OtherKeyboard : PreviewHost() {
        private val item: PreviewItem? = null
    }

    @Test
    fun floatPanelNeedsSelfSingletonAndNineArgEntry() {
        assertTrue(looksLikeFloatPanel(FloatPanel::class.java))
        assertFalse("没有静态自引用字段就不是单例持有者", looksLikeFloatPanel(FloatPanelNoSingleton::class.java))
        assertFalse("重排入口参数个数对不上", looksLikeFloatPanel(FloatPanelShortEntry::class.java))
    }

    @Test
    fun skinBinderNeedsThreeShapesAndNoInstanceState() {
        assertTrue(looksLikeSkinBinder(SkinBinder::class.java))
        assertFalse("带实例字段的不是皮肤工具类", looksLikeSkinBinder(SkinBinderWithState::class.java))
        assertFalse("形状不齐不算命中", looksLikeSkinBinder(SkinBinderPartial::class.java))
    }

    @Test
    fun dropdownItemNeedsTwoFieldsConstructorAndGetters() {
        assertTrue(looksLikeDropdownItem(DropdownItem::class.java))
        assertFalse("字段多于两个", looksLikeDropdownItem(DropdownItemExtraField::class.java))
        assertTrue(
            "取值方法叫什么名字无所谓，形状对就行",
            looksLikeDropdownItem(DropdownItemOtherGetterNames::class.java)
        )
    }

    @Test
    fun previewKeyboardNeedsPreviewHostSuperclassAndBothFields() {
        assertTrue(
            looksLikeImagePreviewKeyboard(
                PreviewKeyboard::class.java,
                PreviewHost::class.java,
                PreviewItem::class.java,
                PreviewCard::class.java
            )
        )
        assertFalse(
            "同宿主的其它子类没有卡片字段",
            looksLikeImagePreviewKeyboard(
                OtherKeyboard::class.java,
                PreviewHost::class.java,
                PreviewItem::class.java,
                PreviewCard::class.java
            )
        )
        assertFalse(
            "父类不是预览宿主",
            looksLikeImagePreviewKeyboard(
                PreviewKeyboard::class.java,
                Any::class.java,
                PreviewItem::class.java,
                PreviewCard::class.java
            )
        )
        assertFalse(
            "依赖的契约缺失时必须 fail-closed",
            looksLikeImagePreviewKeyboard(PreviewKeyboard::class.java, null, PreviewItem::class.java, PreviewCard::class.java)
        )
    }

    @Test
    fun languageListAdapterFailsClosedWithoutRecyclerViewAncestor() {
        assertFalse(looksLikeLanguageListAdapter(PreviewItem::class.java))
        assertFalse(looksLikeLanguageListAdapter(PreviewKeyboard::class.java))
    }

    @Test
    fun imeEditTextNeedsTextViewAncestor() {
        assertFalse(looksLikeImeEditText(PreviewItem::class.java))
        assertFalse(looksLikeImeEditText(View::class.java))
    }
}
