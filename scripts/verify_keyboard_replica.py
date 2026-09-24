#!/usr/bin/env python3
"""键帽复刻件对照：把真机键盘截图与设置页复刻件截图量同一批像素指标，逐项打印差值。

为什么要这么比：复刻件的每一项都要「拖动滑块就能看到真机的样子」，但视觉模型分不清
5dp / 10dp 的差别（见项目 AGENTS.md 的视觉配额铁律），所以对照必须走数值。这个脚本
就是那把尺子 —— 它曾经量出复刻件键帽圆角是 30px 而真机是 18.9px。

用法：

    python3 scripts/verify_keyboard_replica.py \
        --real /tmp/rk_10.png --preview /tmp/pv_10_light.png --setting 10

两张图必须是同一台机器、同一档设置、同一深浅色下拍的，否则差值没有意义。
真机侧用 Google Keep 编辑态拉键盘；复刻件侧进「界面美化 → 颜色」并打开真机键盘预览。

真机侧是基准，复刻件侧跟着它量：

  真机的键行用中位剖面的双峰中点切出来 —— 键帽与面板是两种纯色，切得很干净。
  复刻件侧不能用同一个阈值：它的面板颜色随 y 连续变化（用户壁纸透出来），
  第四行键帽还跟它下面的底板同色，任何单一阈值都切不开。所以复刻件侧改成在真机
  量出的每条边界附近做局部搜索，找最近的那条沿。这本来就是这个脚本的语义 ——
  复刻件的任务就是复现真机。

量到的指标：

  板顶       IME 窗口顶边（两张图必须一致，否则后面所有纵向数字都会错位）
  键行       四行键帽的顶/底
  键列       第一行的键宽、键距与左沿
  键帽圆角   圆角半径的模型拟合值（同一套算法量两侧，差值才有意义）
  键帽色     键帽填充的 RGB（半透明叠在不同背景上，只记录不判定）

退出码：全部在容差内 0，否则 1。
"""
from __future__ import annotations

import argparse
import sys

import numpy as np
from PIL import Image

# 真机键帽是宿主 drawRoundRect 画的正圆弧角矩形，复刻件不描那条边，而真机键帽有 1px 描边
# 居中压在路径上 —— 填充边界会比几何半径内缩约 1.1px。所以半径容差给 1.5px。
RADIUS_TOL_PX = 1.5
# 位置类指标只受布局常量与 density 影响，卡到亚像素。
POSITION_TOL_PX = 1.0
# 复刻件侧找边界时在真机边界附近搜多宽；真机边界本身就是 ±1px 的测量值。
SNAP_WINDOW_PX = 45
# 一条键行边界处中位剖面的最小台阶。小于它就说明那一侧根本没画出键行。
MIN_STEP_LEVEL = 3.0
# 键行的合理高度区间，用来滤掉工具栏残影和底板。
ROW_HEIGHT_RANGE = (100, 200)


def gray_of(path: str) -> np.ndarray:
    return np.asarray(Image.open(path).convert("RGB")).astype(np.float64).mean(axis=2)


def rgb_of(path: str) -> np.ndarray:
    return np.asarray(Image.open(path).convert("RGB")).astype(np.float64)


def runs(mask) -> list[tuple[int, int]]:
    out, i, n = [], 0, len(mask)
    while i < n:
        if mask[i]:
            j = i
            while j < n and mask[j]:
                j += 1
            out.append((i, j - 1))
            i = j
        else:
            i += 1
    return out


def crossing(vals, threshold, rising: bool):
    idx = np.where(vals >= threshold)[0] if rising else np.where(vals <= threshold)[0]
    if idx.size == 0:
        return None
    i = int(idx[0])
    if i == 0:
        return 0.0
    a, b = vals[i - 1], vals[i]
    if b == a:
        return float(i)
    t = (threshold - a) / (b - a)
    return float(i - 1 + t) if rising else float(i - 1 + (a - threshold) / (a - b))


def two_mode_threshold(vals, min_gap: float = 6.0) -> float:
    """双峰剖面的分界：两个最高直方峰的中点。

    不能用百分位 —— 键帽上的字和图标把低百分位拖到 195 那种深度，
    中点就落到面板色 222 以下，面板自己也会越过阈值。
    """
    lo, hi = float(np.min(vals)), float(np.max(vals))
    if hi - lo < min_gap:
        return float(np.median(vals))
    hist, edges = np.histogram(vals, bins=np.arange(np.floor(lo), np.ceil(hi) + 2.0))
    centers = (edges[:-1] + edges[1:]) / 2.0
    order = np.argsort(hist)[::-1]
    first = int(order[0])
    for i in order[1:]:
        if abs(centers[int(i)] - centers[first]) >= min_gap:
            a, b = sorted((centers[first], centers[int(i)]))
            return float((a + b) / 2.0)
    return float(np.median(vals))


def detect_ime_jump(gray: np.ndarray) -> int:
    """辅助判据：屏幕下半部分行均值跳变最大的一处。只用来交叉检查，不参与测量。"""
    h = gray.shape[0]
    rows = gray.mean(axis=1)
    lo, hi = int(h * 0.45), int(h * 0.72)
    best, best_y = 0.0, lo
    for y in range(lo, hi):
        jump = abs(rows[y] - rows[y - 4]) + abs(rows[y] - rows[y + 4])
        if jump > best:
            best, best_y = jump, y
    return best_y


def detect_key_rows(prof: np.ndarray, top: int) -> tuple[list[tuple[int, int]], float]:
    """真机侧：中位剖面的双峰中点切键行，返回 (绝对 y 的键行列表, 阈值)。"""
    off = int(prof.shape[0] * 0.15)  # 跳过工具栏：它比键区暗得多，会把剖面拉成三峰
    thr = two_mode_threshold(prof[off:])
    mask = prof >= thr
    mask[:off] = False
    bands = [(a + top, b + top) for a, b in runs(mask)
             if ROW_HEIGHT_RANGE[0] <= b - a + 1 <= ROW_HEIGHT_RANGE[1]]
    return bands, thr


def snap_boundary(prof: np.ndarray, top: int, y_target: int, rising: bool) -> tuple[int, float]:
    """在 y_target 附近找剖面最陡的上升/下降沿，返回 (绝对 y, 台阶高度)。"""
    y0 = max(top + 1, y_target - SNAP_WINDOW_PX)
    y1 = min(top + prof.shape[0] - 1, y_target + SNAP_WINDOW_PX)
    if y1 < y0:
        return y_target, 0.0
    ys = np.arange(y0, y1 + 1)
    steps = prof[ys - top] - prof[ys - 1 - top]
    score = steps if rising else -steps
    k = int(np.argmax(score))
    return int(ys[k]), float(score[k])


def fit_circular_corner(points) -> tuple[float, float]:
    """(xs, ys, r) 三参数拟合；返回 (r, rmse)。锚点自由浮动，抵消亚像素边缘偏差。"""
    xs = np.asarray([p[0] for p in points], dtype=float)
    ys = np.asarray([p[1] for p in points], dtype=float)

    def residual(p):
        x0, y0, r = p
        dy = ys - y0
        pred = np.where(
            dy >= r, 0.0,
            r - np.sqrt(np.clip(r * r - (r - np.clip(dy, 0, None)) ** 2, 0, None)))
        return x0 + pred - xs

    best = None
    for x0 in np.arange(xs.min() - 3, xs.min() + 3.01, 0.5):
        for y0 in np.arange(ys.min() - 6, ys.min() + 6.01, 0.5):
            for r0 in np.arange(2.0, 70.01, 0.5):
                v = float(np.mean(residual((x0, y0, r0)) ** 2))
                if best is None or v < best[0]:
                    best = (v, x0, y0, r0)
    for step in (0.1, 0.02):
        v, x0, y0, r0 = best
        for a in np.arange(x0 - step * 6, x0 + step * 6 + 1e-9, step):
            for b in np.arange(y0 - step * 6, y0 + step * 6 + 1e-9, step):
                for c in np.arange(max(2.0, r0 - step * 8), r0 + step * 8 + 1e-9, step):
                    vv = float(np.mean(residual((a, b, c)) ** 2))
                    if vv < best[0]:
                        best = (vv, a, b, c)
    v, x0, y0, r = best
    return r, float(np.sqrt(v))


def measure(path: str, ime_height: int, reference_rows=None) -> dict:
    gray = gray_of(path)
    rgb = rgb_of(path)
    # IME 窗口底边贴屏幕底，高度是宿主实测值，两张图都一样 —— 直接算比找跳变稳。
    top = gray.shape[0] - ime_height
    sub = gray[top: min(top + ime_height, gray.shape[0])]
    prof = np.median(sub, axis=1)
    out: dict = {"ime_top": top, "path": path, "ime_jump": detect_ime_jump(gray)}

    if reference_rows is None:
        rows, thr = detect_key_rows(prof, top)
        out["row_threshold"] = thr
        out["row_steps"] = [None] * len(rows)
    else:
        rows, steps = [], []
        for t, b in reference_rows:
            st, up = snap_boundary(prof, top, t, rising=True)
            sb, dn = snap_boundary(prof, top, b, rising=False)
            rows.append((st, sb))
            steps.append((up, dn))
        out["row_steps"] = steps
    out["key_rows"] = rows
    if not rows:
        return out

    # 键帽比面板亮还是暗：拿第一行键帽中部与它上方那一行比。
    ry0, ry1 = rows[0][0] - top, rows[0][1] - top
    cols = np.median(sub[ry0:ry1 + 1], axis=0)
    c_lo, c_hi = np.percentile(cols, 5), np.percentile(cols, 95)
    raw_cols = [(a, b) for a, b in runs(cols >= (c_lo + c_hi) / 2) if b - a > 40]
    # 屏幕边缘/相邻行残影会切出半截键，按众数宽度筛掉，只留完整键。
    if raw_cols:
        widths = np.array([b - a + 1 for a, b in raw_cols])
        modal = float(np.median(widths))
        col_bands = [(a, b) for a, b in raw_cols if abs((b - a + 1) - modal) <= modal * 0.15]
    else:
        col_bands = []
    out["key_cols"] = col_bands
    out["key_cols_all"] = raw_cols
    if len(col_bands) < 2:
        return out

    cap_like = np.median(sub[ry0 + 40:ry1 - 40, col_bands[1][0] + 20:col_bands[1][1] - 20])
    panel_like = np.median(sub[ry0 - 8:ry0 - 2, col_bands[1][0] + 10:col_bands[1][1] - 10]) \
        if ry0 > 8 else c_lo
    out["contrast"] = float(abs(cap_like - panel_like))

    # 极性：键帽比面板亮就向下穿过阈值，反之向上穿过。
    rising = cap_like > panel_like
    thr = (float(cap_like) + float(panel_like)) / 2

    out["col_widths"] = [b - a + 1 for a, b in col_bands]
    out["col_lefts"] = [a for a, _ in col_bands]
    out["col_rights"] = [b for _, b in col_bands]
    if len(col_bands) >= 3:
        pitches = [col_bands[i + 1][0] - col_bands[i][0] for i in range(len(col_bands) - 1)]
        out["pitch"] = float(np.median(pitches))

    radii, seeds = [], []
    for (a, b) in col_bands[1:4]:
        lo_x = max(0, a - 9)
        y_off = crossing(gray[top + ry0 - 8: top + ry1 + 60, (a + b) // 2], thr, rising)
        if y_off is None:
            continue
        y_top = top + ry0 - 8 + y_off
        pts = []
        for k in range(0, 80):
            y = int(round(y_top)) + k
            if y >= gray.shape[0]:
                break
            x = crossing(gray[y, lo_x:a + 80], thr, rising)
            if x is not None:
                pts.append((lo_x + x, float(y)))
        if len(pts) < 8:
            continue
        r, rmse = fit_circular_corner(pts)
        if rmse < 0.5:
            radii.append(r)
            seeds.append((a, b, r))
    out["key_radii"] = radii
    out["key_radius"] = float(np.median(radii)) if radii else None
    out["key_radius_detail"] = seeds
    sub_rgb = rgb[top: min(top + ime_height, rgb.shape[0])]
    a, b = col_bands[1]
    out["key_color"] = np.median(
        sub_rgb[ry0 + 50:ry1 - 50, a + 25:b - 25].reshape(-1, rgb.shape[2]), axis=0)
    out["panel_color"] = np.median(
        sub_rgb[max(0, ry0 - 10):max(1, ry0 - 3), a + 10:b - 10].reshape(-1, rgb.shape[2]),
        axis=0)
    return out


def fmt(v, nd=2):
    return "—" if v is None else (f"{v:.{nd}f}" if isinstance(v, float) else str(v))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--real", required=True, help="真机键盘截图")
    ap.add_argument("--preview", required=True, help="设置页复刻件截图")
    ap.add_argument("--ime-height", type=int, default=1010, help="IME 窗口像素高（默认 1010）")
    ap.add_argument("--setting", type=int, default=None, help="当时的键盘按键圆角设置值")
    args = ap.parse_args()

    ok = True
    real = measure(args.real, args.ime_height)
    print(f"真机  {args.real}   (行均值跳变点 {real['ime_jump']})")
    print(f"IME 窗口顶边  真机 {real['ime_top']}  (按 --ime-height {args.ime_height} 反推)")
    if not real["key_rows"]:
        print(f"  !! 真机图上没切出键行（阈值 {fmt(real.get('row_threshold'), 1)}）—— "
              f"确认截图是键盘拉起的浅色态，且 --ime-height 等于宿主实测键盘高度")
        return 1
    if len(real["key_rows"]) != 4:
        print(f"  !! 真机图上切出 {len(real['key_rows'])} 条键行，期望 4 条：{real['key_rows']}")
        ok = False

    prev = measure(args.preview, args.ime_height, reference_rows=real["key_rows"])
    print(f"预览  {args.preview}   (行均值跳变点 {prev['ime_jump']})")
    print(f"IME 窗口顶边  真机 {real['ime_top']}  预览 {prev['ime_top']}  "
          f"差 {prev['ime_top'] - real['ime_top']:+d}")
    # 顶边是按 屏高 - 键盘高 反推的，两张图必然一致；跳变点只做交叉检查。
    # 不能要求它俩相等 —— 真机键盘板顶之上还有一层宿主自己画的背景，
    # 行均值最大跳变点落在键帽起始处而不是板顶，这是正常的。
    for name, m in (("真机", real), ("预览", prev)):
        j, t = m["ime_jump"], m["ime_top"]
        if not (t - 12 <= j <= t + args.ime_height):
            print(f"  !! {name}的行均值跳变点 {j} 落在 IME 窗口 {t}..{t + args.ime_height} 之外，"
                  f"先确认 --ime-height 是否等于宿主实测键盘高度")
            ok = False

    print(f"\n键行（真机阈值 {fmt(real.get('row_threshold'), 1)}，预览在真机边界附近找沿）")
    for i, (rb, pb) in enumerate(zip(real["key_rows"], prev["key_rows"])):
        dt, db = pb[0] - rb[0], pb[1] - rb[1]
        flag = "ok" if abs(dt) <= POSITION_TOL_PX and abs(db) <= POSITION_TOL_PX else "!!"
        steps = prev["row_steps"][i] or (0.0, 0.0)
        weak = "  !! 这一侧没有台阶，说明没画出键行" if min(steps) < MIN_STEP_LEVEL else ""
        print(f"  row{i}  顶 {rb[0]:5d}/{pb[0]:5d} ({dt:+3d})   底 {rb[1]:5d}/{pb[1]:5d} ({db:+3d})   "
              f"台阶 {steps[0]:+.1f}/{steps[1]:+.1f}   {flag}{weak}")
        if flag == "!!" or weak:
            ok = False

    print(f"\n键列（第一行）")
    rw, pw = real.get("col_widths", []), prev.get("col_widths", [])
    print(f"  键宽  真机 {rw}  预览 {pw}")
    print(f"  键距  真机 {fmt(real.get('pitch'), 1)}  预览 {fmt(prev.get('pitch'), 1)}")
    if real.get("pitch") is not None and prev.get("pitch") is not None:
        d = prev["pitch"] - real["pitch"]
        flag = "ok" if abs(d) <= POSITION_TOL_PX else "!!"
        print(f"  键距差 {d:+.1f}px   {flag}")
        if flag == "!!":
            ok = False
    rl, pl = real.get("col_lefts", []), prev.get("col_lefts", [])
    rr, pr = real.get("col_rights", []), prev.get("col_rights", [])
    print(f"  键左沿  真机 {rl}")
    print(f"          预览 {pl}")
    if rl and pl and len(rl) == len(pl):
        # 首尾键的外侧边贴着面板边缘，真机在那一带画了 20 级左右的暗色边缘阴影，
        # 阈值交点被推进键帽里约 2px（真机首键因此量成 88px 而不是 90px）。
        # 所以外侧边不参与判定，改比首尾键朝向内部的那条边。
        inner = list(zip(rl[1:-1], pl[1:-1]))
        if inner:
            worst = max(abs(a - b) for a, b in inner)
            flag = "ok" if worst <= POSITION_TOL_PX else "!!"
            print(f"  内部键左沿最大偏差 {worst}px   {flag}")
            if flag == "!!":
                ok = False
        for label, a, b in (("首键内沿（右）", rr[0], pr[0]), ("末键内沿（左）", rl[-1], pl[-1])):
            d = b - a
            flag = "ok" if abs(d) <= POSITION_TOL_PX else "!!"
            print(f"  {label}  真机 {a}  预览 {b}  差 {d:+d}px   {flag}")
            if flag == "!!":
                ok = False
        print(f"  外侧边  真机 {rl[0]}/{rr[-1]}  预览 {pl[0]}/{pr[-1]}  "
              f"（真机那一侧有面板边缘阴影，本方法量不准，仅记录）")
    elif rl and pl:
        print(f"  !! 两侧检出的完整键数不同（{len(rl)} vs {len(pl)}），左沿没法逐一对齐；"
              f"真机全部列 {real.get('key_cols_all')}，预览全部列 {prev.get('key_cols_all')}")

    print(f"\n键帽圆角（模型拟合，同一套算法两侧）")
    r_r, p_r = real.get("key_radius"), prev.get("key_radius")
    print(f"  对比度  真机 {fmt(real.get('contrast'), 1)}  预览 {fmt(prev.get('contrast'), 1)}")
    if r_r is None or p_r is None:
        print("  !! 有一侧量不到圆角（对比度太低或边缘被截）—— 把按键颜色调成高对比再拍")
        ok = False
    else:
        d = p_r - r_r
        flag = "ok" if abs(d) <= RADIUS_TOL_PX else "!!"
        print(f"  半径    真机 {r_r:.2f}px  预览 {p_r:.2f}px  差 {d:+.2f}px   {flag}")
        if args.setting is not None:
            print(f"  期望    设置 {args.setting} + 10 = {args.setting + 10}px 几何半径")
        if flag == "!!":
            ok = False

    print(f"\n键帽填充色（仅记录）")
    rc, pc = real.get("key_color"), prev.get("key_color")
    if rc is None or pc is None:
        print("  !! 有一侧取不到键帽色（键列没检出完整键）")
        ok = False
    else:
        # 键帽是半透明的，叠在真机壁纸与设置页背景上出来的色本来就不一样，
        # 这里只记下两侧的实际值，不参与 pass/fail；要判色请直接看复刻件源码常量。
        print(f"  真机 {rc.round(1).tolist()}  预览 {pc.round(1).tolist()}  最大分量差 "
              f"{np.abs(rc - pc).max():.1f}（背景不同，不判定）")

    print("\n结论：" + ("全部在容差内" if ok else "有指标超出容差，见上面的 !!"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
