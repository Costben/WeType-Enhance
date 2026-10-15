package com.xposed.wetypehook.wetype.host

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RecyclerView 家族的结构判据。形状取自 4.0.0 dex 里被改名的 `RecyclerView$m`（ItemAnimator）
 * 与同族的 `$h`（Adapter）、LayoutManager，判据必须只认前者。
 */
class HostRecyclerShapesTest {

    /** 4.0.0 `RecyclerView$m`：两个抽象 `()V`、一个抽象 `()Z`，另有带参数的抽象方法与具体方法。 */
    private abstract class AnimatorLike {
        abstract fun runPending()
        abstract fun endAll()
        abstract fun running(): Boolean
        abstract fun animate(holder: Any, info: Any): Boolean
        abstract fun end(holder: Any)
        fun duration(): Long = 0L
    }

    /** 4.0.0 `RecyclerView$h`：抽象方法只有 `getItemCount` / `onBind` / `onCreate`。 */
    private abstract class AdapterLike {
        abstract fun itemCount(): Int
        abstract fun bind(holder: Any, position: Int)
        abstract fun create(parent: Any, type: Int): Any
    }

    /** LayoutManager：唯一的抽象方法返回 LayoutParams。 */
    private abstract class LayoutManagerLike {
        abstract fun defaultParams(): Any
    }

    /** `OnFlingListener`：抽象方法带参数。 */
    private abstract class FlingListenerLike {
        abstract fun onFling(x: Int, y: Int): Boolean
    }

    /** 具体子类即使继承了全部形状，自己没有抽象方法，也不是要找的「类型」。 */
    private class ConcreteAnimator : AnimatorLike() {
        override fun runPending() = Unit
        override fun endAll() = Unit
        override fun running(): Boolean = false
        override fun animate(holder: Any, info: Any): Boolean = false
        override fun end(holder: Any) = Unit
    }

    private interface AnimatorInterface {
        fun runPending()
        fun endAll()
        fun running(): Boolean
    }

    /** 只有一个抽象 void 方法，不够。 */
    private abstract class OneVoidOnly {
        abstract fun runPending()
        abstract fun running(): Boolean
    }

    @Test
    fun itemAnimatorShapeMatchesOnlyTheAnimatorFamily() {
        assertTrue(looksLikeItemAnimatorType(AnimatorLike::class.java))
        assertFalse("Adapter 不是 ItemAnimator", looksLikeItemAnimatorType(AdapterLike::class.java))
        assertFalse("LayoutManager 不是 ItemAnimator", looksLikeItemAnimatorType(LayoutManagerLike::class.java))
        assertFalse("OnFlingListener 不是 ItemAnimator", looksLikeItemAnimatorType(FlingListenerLike::class.java))
    }

    @Test
    fun itemAnimatorShapeRejectsConcreteAndInterfaceTypes() {
        assertFalse("具体类不是抽象基类", looksLikeItemAnimatorType(ConcreteAnimator::class.java))
        assertFalse("接口不算", looksLikeItemAnimatorType(AnimatorInterface::class.java))
        assertFalse("抽象 void 方法不足两个", looksLikeItemAnimatorType(OneVoidOnly::class.java))
        assertFalse(looksLikeItemAnimatorType(String::class.java))
        assertFalse(looksLikeItemAnimatorType(Int::class.javaPrimitiveType!!))
    }

    /**
     * 框架里与 ItemAnimator 同形的抽象类（真机上是 `ContentCaptureSession`：抽象 `()V` x2 + 抽象 `()Z`），
     * 且不是 RecyclerView 的嵌套类。
     */
    private abstract class ContentCaptureLike {
        abstract fun flush()
        abstract fun close()
        abstract fun isActive(): Boolean
    }

    /** 模拟 RecyclerView 与它的嵌套类：嵌套类名是 `外部类$内部名`，与 R8 改名后的 `RecyclerView$m` 同一结构。 */
    private class FakeRecyclerView {
        abstract class Animator {
            abstract fun runPending()
            abstract fun endAll()
            abstract fun running(): Boolean
        }

        abstract class Adapter {
            abstract fun itemCount(): Int
        }

        fun setItemAnimator(animator: Animator?) = Unit
        fun setAdapter(adapter: Adapter?) = Unit
        fun setContentCaptureSession(session: ContentCaptureLike?) = Unit
        fun setScrollY(y: Int) = Unit
        fun getItemAnimator(): Animator? = null
    }

    private fun method(name: String) = FakeRecyclerView::class.java.declaredMethods.single { it.name == name }

    @Test
    fun itemAnimatorSetterNeedsNestingInTheDeclaringClassAndTheAnimatorShape() {
        assertTrue(isItemAnimatorSetter(method("setItemAnimator")))
        assertFalse("Adapter 形状不对", isItemAnimatorSetter(method("setAdapter")))
        assertFalse(
            "同形但不是嵌套类的框架类（ContentCaptureSession）必须被挡住",
            isItemAnimatorSetter(method("setContentCaptureSession"))
        )
        assertFalse("参数不是对象类型", isItemAnimatorSetter(method("setScrollY")))
        assertFalse("有返回值的不是 setter", isItemAnimatorSetter(method("getItemAnimator")))
    }

    /** 模拟 `RecyclerView$ViewHolder`：`public final View itemView`，另有包内可见的杂项字段。 */
    private open class ViewHolderLike {
        @JvmField
        val itemView: View? = null

        @JvmField
        var flags: Int = 0
    }

    /** 模拟 `clipboard.z`：自己声明了一堆 View 类型字段，名字按混淆后的顺序排在 `itemView` 前面。 */
    private class RowHolderLike : ViewHolderLike() {
        @JvmField
        var a: View? = null

        @JvmField
        var c: View? = null

        @JvmField
        var d: View? = null
    }

    private open class NoPublicItemView {
        @JvmField
        var hidden: View? = null
    }

    private open class TwoPublicFinalViews {
        @JvmField
        val first: View? = null

        @JvmField
        val second: View? = null
    }

    private class TwoViewsChild : TwoPublicFinalViews()

    @Test
    fun itemViewFieldIsTheRootHoldersPublicFinalView() {
        val field = viewHolderItemViewField(RowHolderLike::class.java)
        assertNotNull(field)
        assertEquals(ViewHolderLike::class.java, field!!.declaringClass)
        assertEquals("itemView", field.name)
    }

    @Test
    fun itemViewFieldIsFoundOnTheRootHolderItself() {
        assertEquals("itemView", viewHolderItemViewField(ViewHolderLike::class.java)?.name)
    }

    @Test
    fun itemViewFieldFailsClosedWhenShapeIsAmbiguousOrAbsent() {
        assertNull("没有 public final View 字段", viewHolderItemViewField(NoPublicItemView::class.java))
        assertNull("两个同形字段不猜", viewHolderItemViewField(TwoViewsChild::class.java))
        assertNull(viewHolderItemViewField(Any::class.java))
    }
}
