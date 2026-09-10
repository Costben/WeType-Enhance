<div align="center">
<img src="./assets/icon.png" width="120px"/>

# WeType Enhance

**微信输入法增强模块 · 不止于美化**

在保留原生输入体验的前提下，为微信输入法提供界面美化、按键手势、剪贴板增强与系统防护，并为作用域其它输入法解锁 MIUI 全面屏键盘优化限制。

<p align="center">
  <a href="https://github.com/Costben/WeType-Enhance/releases"><img src="https://img.shields.io/github/v/release/Costben/WeType-Enhance?label=Release&amp;color=FB7299&amp;style=flat-square" alt="Release"></a>
  <img src="https://img.shields.io/badge/Android-12%2B-3DDC84?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 12+">
  <img src="https://img.shields.io/badge/LSPosed-API%20102-5C6BC0?style=flat-square" alt="LSPosed API 102">
  <img src="https://img.shields.io/badge/Stack-Jetpack%20Compose-4285F4?style=flat-square" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/License-AGPL--3.0-orange?style=flat-square" alt="AGPL-3.0">
</p>

<p align="center">
  <a href="#功能特性">功能特性</a> ·
  <a href="#效果预览">效果预览</a> ·
  <a href="#安装与使用">安装与使用</a> ·
  <a href="#兼容性">兼容性</a> ·
  <a href="#常见问题">常见问题</a> ·
  <a href="#从源码构建">从源码构建</a> ·
  <a href="#上游与致谢">上游与致谢</a>
</p>
</div>

---

## 功能特性

WeType Enhance 覆盖微信输入法从外观到能力的完整增强链路，所有能力均可在模块设置中独立开关，修改在运行时生效，不替换输入法本体。

| 模块 | 能力 |
| :--- | :--- |
| 界面美化 | 背景 / 按键 / 候选词颜色、透明度、圆角、模糊与边缘高光 |
| 按键手势 | 26 键与九宫格下滑手势、24 种动作绑定、触觉反馈与动作角标 |
| 剪贴板增强 | 原生搜索、跨设备条目持久保存、解除条数与长度限制 |
| 个性替换 | 键盘 Logo 替换、字体来源切换 |
| 系统防护 | 阻止微信输入法热更新 |
| MIUI 附加 | 三方输入法全面屏优化解锁、小米短语校验解锁、剪贴板列表修复 |

### 界面美化

- 浅色 / 深色模式独立配置，支持实时效果预览
- 窗口背景颜色与不透明度自定义
- 按键颜色、不透明度与圆角，按分组映射批量调整
- 背景模糊强度、平滑圆角与边缘高光（支持关闭与强度调节）
- 输入法全局品牌强调色
- 工具栏图标背景不透明度
- 候选词背景透明度与圆角
- 首个候选词左边距、候选栏拼音左边距

### 按键手势

- 26 键 QWERTY 与九宫格 T9 按键下滑手势，独立开关
- 内置 24 种下滑动作，除未绑定外均可指定给任意按键：全选、剪切、复制、粘贴、全复制、全剪切、撤销、重做、段首、段尾、选至段首、选至段尾、文首、文尾、选至文首、选至文尾、剪切板、常用语、手写找字、设置、滑移、滑选、禁用下滑
- 默认预置经典 Ctrl+Z / X / C / V 键位（Z=全选、X=剪切、C=复制、V=粘贴）
- 可视化键位映射编辑器，支持一键恢复默认预设或清空全部
- QWERTY / T9 独立触觉反馈与触发阈值调节
- 动作角标支持字号、不透明度、位置（顶部 / 底部）与四向边距自定义

### 功能增强

**键盘 Logo**

- 支持启用 / 关闭 Logo 替换，并可单独控制显示或隐藏
- 颜色模式：跟随品牌色（官方彩色）、跟随系统（自适应黑白）、自定义 #RRGGBB

**字体替换**

- 三种来源：微信官方、模块内置（WE-Regular 优化字体）、跟随系统

**剪贴板增强**

- 跨设备同步条目可见化并持久保存
- 解除保留条数上限与时长限制（上限提升至 100,000 条，留存时长永久）
- 解除单条文本长度限制（上限提升至 1 亿字符）
- 剪贴板页面原生搜索，支持中文分词与拼音 / 首字母匹配

**系统防护**

- 阻止微信输入法热更新，降低 Hook 因云端热修复失效的风险

### MIUI / HyperOS 附加功能

在支持小米全面屏键盘优化的 MIUI / HyperOS 系统上：

- 为作用域三方输入法解锁全面屏键盘优化限制
- 解锁小米短语的包名校验
- 修复三方输入法无法获取系统剪贴板列表的问题

该部分在非小米系统上不会启用，也不影响微信输入法相关增强功能。

## 效果预览

默认效果参考 iOS 27 Apple 官方设计稿内的配色与圆角数值。

<details open>
<summary>#FB7299 主题色截图</summary>
<table>
  <tr>
    <td align="center"><img src="./assets/prew/dark_1.jpg" width="200" alt="深色模式"><br>深色模式</td>
    <td align="center"><img src="./assets/prew/dark_2.jpg" width="200" alt="深色模式"><br>深色模式</td>
    <td align="center"><img src="./assets/prew/light_1.jpg" width="200" alt="浅色模式"><br>浅色模式</td>
    <td align="center"><img src="./assets/prew/light_2.jpg" width="200" alt="浅色模式"><br>浅色模式</td>
  </tr>
</table>
</details>

## 安装与使用

### 下载

- 本仓库 Releases：https://github.com/Costben/WeType-Enhance/releases
- Xposed 模块仓库：https://github.com/Xposed-Modules-Repo/com.xposed.wetypehook

### 使用要求

- Android 12+
- LSPosed / 兼容的 Xposed 框架，Xposed API Version ≥ 102
- 微信输入法

### 安装步骤

1. 安装模块 APK。
2. 在 LSPosed 中启用模块并勾选 **微信输入法** 作用域；如需 MIUI 附加功能，同时勾选对应的输入法。
3. 重启微信输入法进程。
4. 通过桌面入口进入模块设置；也可以点击微信输入法「关于」页面中的 Logo 打开寄生设置页。
5. 保存设置后按提示重启微信输入法进程，使改动完全生效。

## 兼容性

- 模块主要针对微信输入法进行适配。微信输入法内部实现、资源名称或云端热修复发生变化时，部分功能可能暂时失效，可在「功能增强 → 系统防护」中开启禁用热更新。
- 本仓库为基于上游的增强分支，会持续合入上游改动并叠加独立增强功能，版本号与更新节奏可能与上游不同。
- MIUI / HyperOS 相关附加功能仅针对小米系统，不适用于其他厂商的系统级输入法优化实现。

## 测试环境

| 项目 | 版本 |
| :--- | :--- |
| 设备 | Xiaomi 17 Pro |
| 系统 | HyperOS 4.0.0.27 Beta / Android 17 |
| 框架 | LSPosed v2.1.1-it (7846) |
| 微信输入法 | 3.5.3.56201 |

## 常见问题

<details>
<summary>修改设置后没有生效？</summary>

在设置页保存后，按提示重启微信输入法进程；如果在 LSPosed 中调整了作用域，也需要重启对应进程后才会加载。

</details>

<details>
<summary>支持其他输入法吗？</summary>

界面美化、按键手势与剪贴板增强仅针对微信输入法；MIUI / HyperOS 全面屏优化解锁部分对作用域内的三方输入法生效。

</details>

<details>
<summary>部分功能突然失效了怎么办？</summary>

微信输入法可能通过云端热修复更新了内部实现，导致依赖的类或资源名称变化。可开启「禁用热更新」降低风险，并等待模块适配版本更新。

</details>

<details>
<summary>和上游项目有什么区别？</summary>

本仓库 Fork 自 [NEORUAA/WeType_UI_Enhanced](https://github.com/NEORUAA/WeType_UI_Enhanced)，在上游界面美化能力之上扩展了按键手势、剪贴板搜索与增强、Logo / 字体替换等能力，并持续合入上游更新。

</details>

<details>
<summary>需要 Root 吗？</summary>

模块本身通过 LSPosed 运行。设置页中的「重启微信输入法」快捷操作需要 Root 权限；没有 Root 时也可以在系统设置中手动强行停止输入法。

</details>

## 从源码构建

需要 JDK 21 与 Android SDK（compileSdk 37）：

```bash
./gradlew :app:assembleRelease
```

产物位于 `app/build/outputs/apk/release/WeType_Enhance-<versionName>_release.apk`。release 包未签名，请自行签名后再安装。

## 上游与致谢

- 本项目 Fork 自 [NEORUAA/WeType_UI_Enhanced](https://github.com/NEORUAA/WeType_UI_Enhanced)，上游提供了界面美化与 MIUI 全面屏优化解锁的基础实现。
- 感谢 [MIUI_IME_Unlock (MIT)](https://github.com/RC1844/MIUI_IME_Unlock) 提供的解锁 MIUI 全面屏优化限制功能。
- 感谢 [miuix](https://github.com/compose-miuix-ui/miuix) 提供的 Compose UI 库。

## 开源许可

本项目自 2026.8.16 起换用 AGPL-3.0 许可协议，要求修改和分发的同时也公开源码，且使用相同的许可协议。
