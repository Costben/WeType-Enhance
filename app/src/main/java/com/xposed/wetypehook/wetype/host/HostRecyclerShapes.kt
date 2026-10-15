package com.xposed.wetypehook.wetype.host

import android.view.View
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 宿主里 RecyclerView 家族的几个类型，按**结构**认，不按名字认。
 *
 * 宿主把自带的 `androidx.recyclerview` 一并过了 R8，但只改了一部分类名：4.0.0 的 dex 里
 * `RecyclerView` / `RecyclerView$LayoutManager` / `RecyclerView$ViewHolder` 还叫原名，
 * `ItemAnimator` 已经是 `RecyclerView$m`、`Adapter` 是 `$h`、`ItemDecoration` 是 `$o`。
 * 拿原名全称去比对被改名的那几个，查找会静默落空 —— 瀑布流「关列表动画」就是这样一直没生效。
 * 哪些类名会被改取决于宿主当版的混淆配置，不能靠猜，所以改按成员形状判。
 */

/**
 * `RecyclerView.ItemAnimator` 的形状：抽象类，且至少有两个无参抽象 `void` 方法
 * （`runPendingAnimations` / `endAnimations`）与一个无参抽象 `boolean` 方法（`isRunning`）。
 *
 * 同族里别的抽象类都不满足：`Adapter` 的抽象方法是 `getItemCount` / `onBindViewHolder` /
 * `onCreateViewHolder`，`LayoutManager` 只有一个返回 LayoutParams 的 `generateDefaultLayoutParams`，
 * `OnFlingListener` 只有 `onFling(int, int)`。
 */
internal fun looksLikeItemAnimatorType(type: Class<*>): Boolean {
    if (type.isInterface || !Modifier.isAbstract(type.modifiers)) return false
    val abstractNoArg = type.declaredMethods.filter {
        Modifier.isAbstract(it.modifiers) && it.parameterTypes.isEmpty()
    }
    return abstractNoArg.count { it.returnType == Void.TYPE } >= 2 &&
        abstractNoArg.any { it.returnType == Boolean::class.javaPrimitiveType }
}

/**
 * [setter] 是 `RecyclerView.setItemAnimator(ItemAnimator)`：单参 void，参数类型是**声明它的类的嵌套类**
 * （`RecyclerView$…`，R8 改名后仍是 `外部类$内部名`），并且形状是 ItemAnimator。
 *
 * 只靠 [looksLikeItemAnimatorType] 不够：`View.setContentCaptureSession(ContentCaptureSession)` 的参数
 * 也是「抽象类 + 两个抽象 `()V` + 一个抽象 `()Z`」，真机上就曾把它当成 setter 调了
 * `setContentCaptureSession(null)`。嵌套关系把框架里同形的类挡在外面。
 */
internal fun isItemAnimatorSetter(setter: Method): Boolean {
    if (setter.returnType != Void.TYPE || setter.parameterTypes.size != 1) return false
    val type = setter.parameterTypes[0]
    return type.name.startsWith(setter.declaringClass.name + "$") && looksLikeItemAnimatorType(type)
}

/**
 * `RecyclerView.ViewHolder.itemView`：继承链最顶层那个类（父类是 `Object`）上**唯一**的
 * `public final View` 实例字段。
 *
 * 行 ViewHolder 的子类自己还声明了十几个 View 类型字段（4.0.0 的 `clipboard.z` 有 `c` ~ `q`），
 * 按「第一个非空 View 字段」去取，拿到哪个全看 R8 给字段起的名字排在哪，换一版就可能拿到卡片
 * 或文本控件。`itemView` 是 RecyclerView 的公开 API，可见性与 final 都不会因为改名而变。
 */
internal fun viewHolderItemViewField(holderClass: Class<*>): Field? {
    var root: Class<*> = holderClass
    while (true) {
        val parent = root.superclass ?: return null
        if (parent == Any::class.java) break
        root = parent
    }
    val hits = root.declaredFields.filter {
        val modifiers = it.modifiers
        Modifier.isPublic(modifiers) && Modifier.isFinal(modifiers) && !Modifier.isStatic(modifiers) &&
            it.type == View::class.java
    }
    return hits.singleOrNull()?.also { it.isAccessible = true }
}
