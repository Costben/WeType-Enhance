<div align="center">

# WeType Enhance

**微信输入法增强模块 · 外观、手势、剪贴板一次补齐**

在运行时为微信输入法叠加自定义能力：界面外观、按键手势、剪贴板增强与系统防护；在小米系统上，还会为作用域内的三方输入法解锁全面屏键盘优化。

<p align="center">
  <a href="https://github.com/Costben/WeType-Enhance/releases"><img src="https://img.shields.io/github/v/release/Costben/WeType-Enhance?label=Release&amp;color=FB7299&amp;style=flat-square" alt="Release"></a>
  <img src="https://img.shields.io/badge/Android-12%2B-3DDC84?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 12+">
  <img src="https://img.shields.io/badge/LSPosed-API%20102-5C6BC0?style=flat-square" alt="LSPosed API 102">
  <img src="https://img.shields.io/badge/Stack-Jetpack%20Compose-4285F4?style=flat-square" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/License-AGPL--3.0-orange?style=flat-square" alt="AGPL-3.0">
</p>

<p align="center">
  <a href="#功能特性">功能特性</a> ·
  <a href="#安装与使用">安装与使用</a> ·
  <a href="#兼容性">兼容性</a> ·
  <a href="#常见问题">常见问题</a> ·
  <a href="#从源码构建">从源码构建</a> ·
  <a href="#上游与致谢">上游与致谢</a>
</p>
</div>

---

## 功能特性

设置页按「界面美化 / 按键手势 / 功能增强」三组组织。所有配置即时写入，重启微信输入法进程后生效；模块只在运行时修改，不替换、不改动输入法安装包。

| 分组 | 包含能力 |
| :--- | :--- |
| 界面美化 | 背景 / 按键 / 候选词的颜色、透明度、圆角、模糊与边缘高光 |
| 按键手势 | 全键盘与九宫格下滑手势、24 种动作绑定、触觉反馈与角标 |
| 功能增强 | Logo / 字体替换、剪贴板增强、热更新防护 |
| MIUI 附加 | 三方输入法全面屏优化解锁、小米短语校验解锁、剪贴板列表修复 |

### 界面美化

- 浅色与深色模式各自保存一套配色，编辑时可实时查看渲染效果
- 窗口背景颜色与不透明度自由调整
- 按键颜色按映射分组批量替换，再单独微调透明度与圆角
- 背景模糊强度、平滑圆角与边缘高光（支持关闭与强度调节）
- 全局品牌强调色
- 工具栏图标背景不透明度
- 候选词背景透明度与圆角
- 首个候选词左边距、候选栏拼音左边距

### 按键手势

- QWERTY 全键盘与九宫格 T9 两条手势通道，独立开关
- 内置 24 种下滑动作：全选、剪切、复制、粘贴、全复制、全剪切、撤销、重做、段首、段尾、选至段首、选至段尾、文首、文尾、选至文首、选至文尾、剪切板、常用语、手写找字、设置、滑移、滑选、禁用下滑、未绑定
- 预置 Z=全选、X=剪切、C=复制、V=粘贴，可一键恢复默认或清空全部
- 可视化键位表，点按任意按键即可指定动作
- 两种键盘布局分别提供触觉反馈与触发阈值
- 角标提示可调字号、不透明度、顶部 / 底部位置与四向边距

### 功能增强

- **键盘 Logo**：启用或关闭替换、单独控制显示隐藏；主体颜色支持跟随品牌色、跟随系统自适应黑白、自定义 `#RRGGBB`
- **字体替换**：可在微信官方字体、模块内置 WE-Regular 优化字体、系统默认字体之间切换
- **剪贴板**：多端同步条目可见化并持久保存；保留条数上限提升至 100,000 条、留存时长永久；单条文本上限提升至 1 亿字符；剪贴板页面内直接搜索，支持中文分词与拼音 / 首字母命中
- **剪贴板备份与恢复**：把剪贴板历史（全部文本 + 已落盘图片）导出为 zip 保存到设备文件夹，或备份到 WebDAV；支持合并导入还原，按内容去重并保留原始时间。备份包为明文，请妥善保存
- **系统防护**：阻止微信输入法热更新，降低依赖的 Hook 因云端热修复失效的概率

### MIUI / HyperOS 附加功能

面向支持小米全面屏键盘优化的系统：

- 解锁作用域内三方输入法的全面屏键盘优化限制
- 解锁小米短语的包名校验
- 修复三方输入法读不到系统剪贴板列表的问题

非小米系统不会加载这部分逻辑，也不影响微信输入法的增强功能。

## 安装与使用

### 下载

- 本仓库 Releases：https://github.com/Costben/WeType-Enhance/releases
- Xposed 模块仓库：https://github.com/Xposed-Modules-Repo/com.xposed.wetypehook

### 使用要求

- Android 12 及以上
- LSPosed 或兼容的 Xposed 框架，Xposed API 版本不低于 102
- 已安装微信输入法

### 安装步骤

1. 安装模块 APK。
2. 在 LSPosed 中启用模块，作用域勾选 **微信输入法**；需要 MIUI 附加功能时，再勾选对应输入法。
3. 重启微信输入法进程，让 Hook 加载。
4. 打开桌面入口进入设置；也可以点击微信输入法「关于」页里的 Logo 进入寄生设置页。
5. 保存配置后按提示重启微信输入法进程，改动即可生效。

## 兼容性

- 模块依赖微信输入法的内部实现与资源名称，官方更新或云端热修复可能导致个别功能失效；开启「禁用热更新」可降低风险。
- 本仓库基于上游继续演进，会定期合入上游改动并叠加自有增强，版本号与发布节奏和上游不完全一致。
- MIUI / HyperOS 附加功能只在小米系统生效，其他厂商的系统级输入法优化不在适配范围内。

## 常见问题

<details>
<summary>保存后为什么没有立即生效？</summary>

配置在输入法进程启动时读取，保存后需要重启微信输入法进程；在 LSPosed 里调整过作用域也一样，要重启作用域进程才会重新加载。

</details>

<details>
<summary>其他输入法能享受这些增强吗？</summary>

界面美化、按键手势、剪贴板增强与 Logo / 字体替换都只针对微信输入法；小米全面屏解锁部分对作用域内的三方输入法生效。

</details>

<details>
<summary>某个功能突然失效了？</summary>

多半是微信输入法通过热修复改了内部实现，导致类名或资源名变化。可以开启「禁用热更新」，同时等待模块跟进适配。

</details>

<details>
<summary>这个仓库和上游是什么关系？</summary>

本仓库 Fork 自 [NEORUAA/WeType_UI_Enhanced](https://github.com/NEORUAA/WeType_UI_Enhanced)，上游提供界面美化与 MIUI 解锁的基础实现；这里在其上增加了按键手势、剪贴板搜索与增强、Logo / 字体替换等能力。

</details>

<details>
<summary>剪贴板备份包安全吗？</summary>

备份包是明文 zip，包含剪贴板全部文本与图片。请勿放在公共目录或转发给他人；WebDAV 地址建议使用 https，并为账号开启二次验证或使用应用专用密码。导入为合并模式，不会删除或覆盖现有条目。

</details>

<details>
<summary>需要 Root 吗？</summary>

模块本身由 LSPosed 加载，不要求 Root。设置页里的「重启微信输入法」快捷操作需要 Root；没有 Root 时在系统设置里手动停止输入法即可。

</details>

## 从源码构建

准备 JDK 21 与 Android SDK（compileSdk 37），然后执行：

```bash
./gradlew :app:assembleRelease
```

产物为 `app/build/outputs/apk/release/WeType_Enhance-<versionName>_release.apk`，未包含签名，安装前请自行签名。

## 上游与致谢

- 上游项目：[NEORUAA/WeType_UI_Enhanced](https://github.com/NEORUAA/WeType_UI_Enhanced)，感谢原作者打下界面美化与 MIUI 解锁的基础。
- [MIUI_IME_Unlock (MIT)](https://github.com/RC1844/MIUI_IME_Unlock)：MIUI 全面屏优化限制的解锁实现。
- [miuix](https://github.com/compose-miuix-ui/miuix)：设置页使用的 Compose UI 组件库。

## 开源许可

项目自 2026.8.16 起采用 AGPL-3.0 协议：修改与分发需公开源码，并沿用同一协议。
