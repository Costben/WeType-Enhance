package com.xposed.wetypehook

/**
 * 宿主 WeType 的 manifest 没有声明 `enableOnBackInvokedCallback`，框架会给窗口挂一个只注入
 * KEYCODE_BACK 的兼容回调，并且拒绝注册 androidx 的动画回调，二级页面因而拿不到手势进度、
 * 无法跟手。
 *
 * 内嵌设置弹窗存在期间把框架的判定放宽成已 opt-in，让 miuix-nav 的预测性返回真正接上平台。
 * 作用域只覆盖弹窗生命周期，弹窗关闭后宿主的窗口判定恢复原状。
 */
internal object PredictiveBackOptIn {
    @Volatile
    var active: Boolean = false
}
