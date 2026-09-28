# ColorOS 光感预设目录

本目录是模块光感预设的可追溯入口。完整的 36 项预设及其 16 槽参数保存在同目录的 [`preset-catalog.js`](./preset-catalog.js)；网页预览和模块设置使用相同的稳定 ID。

## 使用规则

- 默认关闭“允许全部 36 个预设”时，背景、图标、按键只显示对应对象和相近对象的预设。
- 打开该开关后，三个对象都可以选择完整 36 项。
- 设置保存的是预设 ID，不保存显示名称；名称变更不会破坏已有配置。
- 模块绘制器会把每个 ID 解析到对应的 ColorOS / COUI 材质家族；网页文件中的原始槽位数组是参数真源。

## 设置页里的三层选择

「光感设置」页自上而下为 **总控 → 分类 → 高级参数 → 恢复默认**。

- **分类**是一张卡里的三行下拉：背景、按钮、图标。三行各自独立，可以选同一项，也可以不同。
- 每行下拉的**第一项固定是「无预设」**（`MaterialPresetCatalog.NONE`），表示这一类不修改。
  绘制侧对它是显式跳过，不会因为查不到目录项就退回某套默认阴影栈。
- 边缘 / 内发光及其滑杆不在分类里，只从**「高级参数 → 高级参数调节」**进。
- 三类的默认与「恢复默认」的结果都是「无预设」。

## 自定义预设

「高级参数调节」里可以：

- 只读查看 36 项内置预设（稳定 ID、名称、来源组、所属阴影栈、自带角度）。内置项不可修改。
- 从任意内置项复制一份**自定义预设**，可编辑项为：光照方向、边缘开关 / 强度 / 宽度、
  内发光开关 / 强度 / 宽度 —— 即绘制侧真正消费的那几个参数，全部落在既有滑杆的合法区间内。
- 自定义预设保存在模块偏好里（`advanced_light_presets`，一个 JSON 数组），重启与旋转后不丢；
  创建后出现在分类三行下拉的末尾，三个对象都可以选它。

自定义预设的 ID 形如 `custom:classic:1` / `custom:coloros:1`，阴影栈家族写在 ID 里，所以渲染进程
不需要为了取家族再读一遍偏好文件。

目录里的 32 个原生槽位（每项的 `edgeArray` / `shadowArray`）**不开放编辑**：渲染侧不消费它们，
开放出来只会得到一个「改了没反应」的编辑项。

## 36 项清单

| ID | 名称 | 来源组 | 默认可用于 |
| --- | --- | --- | --- |
| `coui:edge-0` | COUI 边缘光 0 | coui | 背景 / 图标 / 按键 |
| `coui:edge-1` | COUI 边缘光 1 | coui | 背景 / 图标 / 按键 |
| `coui:edge-2` | COUI 边缘光 2 | coui | 背景 / 图标 / 按键 |
| `coui:spec-default` | COUI 默认 Spec | coui | 背景 / 图标 / 按键 |
| `launcher:PAGE_INDICATOR` | 桌面 · 页面指示器 | launcher | 背景 / 图标 |
| `launcher:DOCK` | 桌面 · Dock | launcher | 背景 / 图标 |
| `launcher:CARD` | 桌面 · 卡片 | launcher | 背景 / 图标 |
| `launcher:GROUP_CARD` | 桌面 · 文件夹卡片 | launcher | 背景 / 图标 |
| `launcher:WIDGET` | 桌面 · 小组件 | launcher | 背景 / 图标 |
| `launcher:MIDDLE_FOLDER` | 桌面 · 中型文件夹 | launcher | 背景 / 图标 |
| `launcher:SMALL_FOLDER` | 桌面 · 小型文件夹 | launcher | 背景 / 图标 |
| `launcher:BIG_FOLDER` | 桌面 · 大型文件夹 | launcher | 背景 / 图标 |
| `launcher:MIDDLE_1_2_FOLDER` | 桌面 · 横向文件夹 | launcher | 背景 / 图标 |
| `launcher:MIDDLE_2_1_FOLDER` | 桌面 · 纵向文件夹 | launcher | 背景 / 图标 |
| `launcher:PRESS_FEEDBACK` | 桌面 · 按压反馈 | launcher | 图标 / 按键 |
| `launcher:TOGGLE_TOP_BUTTON` | 桌面 · 顶部切换按钮 | launcher | 图标 / 按键 |
| `launcher:TOGGLE_BOTTOM_BUTTON` | 桌面 · 底部切换按钮 | launcher | 图标 / 按键 |
| `launcher:ALL_APPS_CATEGORY` | 桌面 · 应用分类 | launcher | 背景 / 图标 |
| `launcher:ALL_APPS_SUGGESTION` | 桌面 · 应用推荐 | launcher | 背景 / 图标 |
| `launcher:PREVIEW_PAGE_EFFECT` | 桌面 · 页面预览 | launcher | 背景 / 图标 |
| `settings:back-circle-light` | 设置 · 返回键（浅色） | settings | 图标 |
| `settings:back-circle-dark` | 设置 · 返回键（深色） | settings | 图标 |
| `systemui:notif-light` | 控制中心 · 通知卡（浅色） | systemui | 背景 |
| `systemui:notif-dark` | 控制中心 · 通知卡（深色） | systemui | 背景 |
| `systemui:seekbar-bg-light` | 控制中心 · 亮度条未填充（浅色） | systemui | 背景 / 按键 |
| `systemui:seekbar-bg-dark` | 控制中心 · 亮度条未填充（深色） | systemui | 背景 / 按键 |
| `systemui:seekbar-pg-light` | 控制中心 · 亮度条已填充（浅色） | systemui | 背景 / 按键 |
| `systemui:seekbar-pg-dark` | 控制中心 · 亮度条已填充（深色） | systemui | 背景 / 按键 |
| `systemui:statusbar-capsule` | 状态栏 · 锁屏胶囊（单卡） | statusbar | 背景 |
| `systemui:statusbar-capsule-multi` | 状态栏 · 锁屏胶囊（多卡） | statusbar | 背景 |
| `systemui:qs-tile-inactive-light` | 控制中心 · 快捷开关未激活（浅色） | qs | 按键 / 图标 |
| `systemui:qs-tile-active-light` | 控制中心 · 快捷开关激活（浅色） | qs | 按键 / 图标 |
| `systemui:qs-tile-inactive-dark` | 控制中心 · 快捷开关未激活（深色） | qs | 按键 / 图标 |
| `systemui:qs-tile-active-dark` | 控制中心 · 快捷开关激活（深色） | qs | 按键 / 图标 |
| `systemui:volume-slider-light` | 控制中心 · 音量条（浅色） | volume | 按键 / 背景 |
| `systemui:volume-slider-dark` | 控制中心 · 音量条（深色） | volume | 按键 / 背景 |

网页调参页会直接链接到本文；模块设置页的“预设参数文档”也指向同一份文件。

模块侧目录实现位于 `app/src/main/java/com/xposed/wetypehook/wetype/settings/MaterialPresetCatalog.kt`，
选择值由 `EdgeLightGroup.presetId` 保存，绘制入口由
`app/src/main/java/com/xposed/wetypehook/wetype/graphics/WeTypeSelfDrawnEdgeLight.kt` 解析。
「高级参数调节」里的自定义预设由
`app/src/main/java/com/xposed/wetypehook/wetype/settings/AdvancedLightPreset.kt` 建模与编解码。
