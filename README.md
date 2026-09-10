<div align="center">
<img src="./assets/icon.png" width="120px"/>

# WeType Enhance

一个面向 **微信输入法（WeType）** 的 Xposed 增强模块：在保留原生输入体验的前提下，提供界面美化、按键手势、剪贴板增强与系统防护，并为作用域其它输入法解锁 MIUI 全面屏优化限制。


<p align="center">

![Android 12 or later](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&amp;logoColor=white)
![LSPosed 102](https://img.shields.io/badge/LSPosed-Modern_API_102-5C6BC0)
![Jetpack Compose](https://img.shields.io/badge/Jetpack-Compose-blue)
![License](https://img.shields.io/badge/License-AGPLv3-orange)

</p>
</div>

---

## 功能

### 界面美化

- 自定义浅色 / 深色模式下的窗口背景颜色与透明度
- 自定义浅色 / 深色按键颜色、透明度与圆角
- 自定义背景模糊强度、平滑圆角及边缘高光效果
- 自定义输入法全局品牌强调色
- 调节工具栏图标背景透明度
- 调节候选词背景透明度与圆角
- 调节首个候选词及候选栏拼音边距

### 按键手势

- 支持 26 键 QWERTY 与九宫格 T9 按键下滑手势
- 24 种可绑定动作：全选 / 剪切 / 复制 / 粘贴 / 全复制 / 全剪切、撤销 / 重做、段首段尾与文首文尾跳转及选至、剪切板 / 常用语面板拉起、手写找字、滑移 / 滑选光标等
- 默认预置经典 Ctrl+Z / X / C / V 键位
- 支持触觉反馈与触发阈值调节
- 可在按键上显示动作角标，自定义字号、透明度、位置与边距

### 功能增强

- 键盘 Logo 替换：支持显示 / 隐藏与主体颜色自定义
- 字体替换：支持微信官方、模块内置（WE-Regular）、跟随系统三种来源
- 剪贴板增强：
  - 跨设备同步条目可见化持久保存
  - 解除条数保留上限与时长限制（上限提升至 100,000 条，留存时长永久）
  - 解除单条文本长度限制
  - 剪贴板原生搜索，支持中文分词与拼音 / 首字母匹配
- 阻止微信输入法热更新

### MIUI / HyperOS 附加功能

在支持小米全面屏键盘优化的 MIUI / HyperOS 系统上，提供三方输入法解锁全面屏键盘优化限制，解锁小米短语的包名校验，修复三方输入法无法获取系统剪贴板列表的问题。

该部分在非小米系统上不会启用，也不影响 WeType 相关增强功能。

## 效果预览

默认效果为 iOS 27 Apple 官方设计稿内的配色、圆角等数值
<details open>
<summary>#FB7299 主题色截图</summary>
<table>
  <tr>
    <td><img src="./assets/prew/dark_1.jpg" width="200" alt="深色模式"></td>
    <td><img src="./assets/prew/dark_2.jpg" width="200" alt="深色模式"></td>
    <td><img src="./assets/prew/light_1.jpg" width="200" alt="浅色模式"></td>
    <td><img src="./assets/prew/light_2.jpg" width="200" alt="浅色模式"></td>
  </tr>
</table>
</details>

## 使用要求

- Android 12+
- LSPosed / 兼容的 Xposed 框架
- Xposed API Version ≥ 102
- 微信输入法

安装模块后，在 LSPosed 中启用模块并勾选 **微信输入法** 作用域，然后重启微信输入法。

模块生效后，可通过桌面入口进入设置；也可以点击微信输入法「关于」页面中的 Logo 打开寄生设置页。

部分设置修改后需要重启微信输入法进程才能完全生效。

## 测试环境

设备：Xiaomi 17 Pro

HyperOS 4.0.0.27 Beta

Android 17

LSPosed v2.1.1-it (7846)

微信输入法：3.5.3.56201

## 兼容性

模块主要针对微信输入法进行适配。微信输入法内部实现、资源名称或云端热修复发生变化时，部分功能可能暂时失效。

本仓库为基于上游的增强分支，会持续合入上游改动并叠加独立增强功能，两者版本号与更新节奏可能不同。

MIUI / HyperOS 相关附加功能仅针对小米系统，不适用于其他厂商的系统级输入法优化实现。

## 下载

请前往本仓库或下方模块仓库的 Releases 下载最新版本。

- 本仓库：https://github.com/Costben/WeType-Enhance
- Xposed 模块仓库：https://github.com/Xposed-Modules-Repo/com.xposed.wetypehook

## 上游与开源致谢

本项目 Fork 自 [NEORUAA/WeType_UI_Enhanced](https://github.com/NEORUAA/WeType_UI_Enhanced)。上游提供了界面美化与 MIUI 全面屏优化解锁的基础实现，本仓库在此基础上扩展为完整的微信输入法增强模块，新增按键手势、剪贴板增强、Logo / 字体替换等能力。

感谢项目 [MIUI_IME_Unlock(MIT)](https://github.com/RC1844/MIUI_IME_Unlock) 提供的解锁 MIUI 全面屏优化限制功能

感谢 [miuix](https://github.com/compose-miuix-ui/miuix) 提供的 Compose UI 库

## 开源许可

本项目自 2026.8.16 起换用 AGPL-3.0 许可协议，要求修改和分发的同时也公开源码，且使用相同的许可协议。
