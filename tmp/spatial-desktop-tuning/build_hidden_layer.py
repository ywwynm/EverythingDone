#!/usr/bin/env python
"""构建隐藏第二层：遮挡带的**颜色 + 深度**，作为点云第二层进入 3D 表示。

D149 的错误是只在屏幕空间补颜色、没有深度：补出来的内容不在 3D 里，无法随视角一致地
被遮挡／显露，视差一大就只能拉伸背景。文献口径（Shih et al. CVPR 2020；
IEEE TPAMI 的 disocclusion inpainting framework）一致：**在中心视角补一次颜色和深度**，
以 LDI 第二层参与渲染。深度那一半的规则是"用背景深度层级填充"，且**源区必须排除前景**
（与 D137 膜校正参照环、D149 背景优先取色是同一条规律）。

三步：
1. 遮挡带掩膜 M —— 中心视角里"会在最大基线下让出背景"的遮挡侧像素；
2. 颜色 —— 在 M 上做图像补全（默认 SDXL-inpainting，与 D132 同契约）；
3. 深度 —— 在 M 上做**最小值传播**（min = 更远），把背景深度延续进遮挡带。
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

import cv2
import numpy as np
from PIL import Image


def disocclusion_mask(inv: np.ndarray, fx: float, baseline: float,
                      max_radius: int = 64) -> np.ndarray:
    """会让出背景的遮挡侧像素。

    最大基线下，逆深度差 Δd 的两个面相对错开 fx·t·Δd 像素。因此像素 p 在半径 r 处
    只要存在比它远 r/(fx·t) 以上的面，p 就会在某个视角让出那一段背景。对 r 取并集
    即得整条遮挡带，宽度自动等于各处的实际视差，不需要全局常量。
    """
    scale = fx * baseline
    span = float(np.percentile(inv, 99.8) - np.percentile(inv, 0.2))
    radius = int(np.clip(np.ceil(scale * span), 1, max_radius))
    mask = np.zeros(inv.shape, dtype=bool)
    for r in range(1, radius + 1):
        kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
        farthest = cv2.erode(inv, kernel)          # 灰度腐蚀 = 邻域最小值 = 最远的面
        mask |= (inv - farthest) >= (r / max(scale, 1e-9))
    return mask


def propagate_background_color(inv: np.ndarray, color: np.ndarray, mask: np.ndarray,
                               iterations: int = 96) -> np.ndarray:
    """把**背景那一侧**的颜色按同一条最小逆深度规则传播进遮挡带。

    D158 的膜校正失败在参照环：遮挡带是沿边缘的条带，对称取环会同时吃到背景和遮挡物
    内部（暗），于是把补全推得更暗。而深度传播本来就是按"取邻域最小逆深度"选源的——
    它天然只吃背景那一侧。让它顺带把源像素的颜色带出来，就得到一张**保证来自背景侧**
    的参照色，用作膜校正的目标，不需要另取环。

    这张图本身很糊（只是延拓，没有纹理），所以只用它的**低频**去校正 LaMa 的色阶，
    LaMa 的高频纹理原样保留。
    """
    filled_inv = inv.copy()
    filled_inv[mask] = np.inf
    filled_rgb = color.copy()
    for _ in range(iterations):
        unknown = np.isinf(filled_inv)
        if not unknown.any():
            break
        pi = np.pad(filled_inv, 1, mode="edge")
        pc = np.pad(filled_rgb, ((1, 1), (1, 1), (0, 0)), mode="edge")
        offs = [(0, 1, 1, 2), (2, 3, 1, 2), (1, 2, 0, 1), (1, 2, 2, 3),
                (0, 1, 0, 1), (0, 1, 2, 3), (2, 3, 0, 1), (2, 3, 2, 3)]
        h, w = inv.shape
        best = np.full((h, w), np.inf, np.float32)
        best_rgb = np.zeros_like(color)
        for a, b, c, d in offs:
            nv = pi[a:a + h, c:c + w]
            nc = pc[a:a + h, c:c + w]
            take = nv < best
            best = np.where(take, nv, best)
            best_rgb = np.where(take[..., None], nc, best_rgb)
        use = unknown & np.isfinite(best)
        filled_inv = np.where(use, best, filled_inv)
        filled_rgb = np.where(use[..., None], best_rgb, filled_rgb)
    return filled_rgb


def propagate_background_depth(inv: np.ndarray, mask: np.ndarray,
                               iterations: int = 96, return_origin: bool = False):
    """把背景逆深度传播进遮挡带：逐环取邻域**最小值**（最小 = 最远）。

    取最小而非平均，是"源区排除前景"在深度上的实现——遮挡带的边界一侧是真实背景
    （逆深度小），另一侧是遮挡物内部（逆深度大）；最小值传播天然只吃背景那一侧。

    `return_origin=True` 时同时返回每个带内像素的**来源像素**（扁平索引）。
    这就是"这条带将要露出的那块真实背景"，**由构造给出**，不需要再用"同深度的最近
    像素"去反猜——后者会在遮挡物自己有一部分正好落在背景深度上时取错
    （05 的托盘立柱与玫瑰几乎同深，实测把盘沿对玫瑰的带判成了自遮挡）。
    """
    filled = inv.copy()
    filled[mask] = np.inf
    h, w = inv.shape
    origin = np.arange(h * w, dtype=np.int32).reshape(h, w)
    origin[mask] = -1
    off = [(-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (-1, 1), (1, -1), (1, 1)]
    for _ in range(iterations):
        unknown = np.isinf(filled)
        if not unknown.any():
            break
        pv = np.pad(filled, 1, mode="edge")
        po = np.pad(origin, 1, mode="edge")
        vs = np.stack([pv[1 + dy:1 + dy + h, 1 + dx:1 + dx + w] for dy, dx in off])
        take = np.argmin(vs, axis=0)
        neigh = np.take_along_axis(vs, take[None], 0)[0]
        if return_origin:
            os_ = np.stack([po[1 + dy:1 + dy + h, 1 + dx:1 + dx + w] for dy, dx in off])
            norg = np.take_along_axis(os_, take[None], 0)[0]
            origin = np.where(unknown & np.isfinite(neigh), norg, origin)
        filled = np.where(unknown & np.isfinite(neigh), neigh, filled)
    filled[np.isinf(filled)] = float(np.nanmin(inv))
    # 传播后是阶梯状，在带内做几次保边平滑，边界值由带外真实深度钉住
    smooth = filled.copy()
    for _ in range(24):
        blurred = cv2.GaussianBlur(smooth, (0, 0), 1.5)
        smooth = np.where(mask, blurred, filled)
    return (smooth, origin) if return_origin else smooth


def inpaint_onnx(image: np.ndarray, hole: np.ndarray, model: Path, side: int = 512) -> np.ndarray:
    """Big-LaMa / AOT-GAN 契约（generate_assets.run_inpaint_512 同源）：
    image [1,3,S,S] 0..1，mask [1,1,S,S] 1=洞；方形反射填充 → S 推理 → 还原尺寸。
    输入名从模型自身读，避免两个后端各写一份硬编码。"""
    import onnxruntime as ort

    session = ort.InferenceSession(str(model), providers=["CPUExecutionProvider"])
    names = [i.name for i in session.get_inputs()]
    h, w = image.shape[:2]
    full = max(w, h)
    pad_l, pad_t = (full - w) // 2, (full - h) // 2
    padded = np.pad(image, ((pad_t, full - h - pad_t), (pad_l, full - w - pad_l), (0, 0)), mode="reflect")
    padded_mask = np.pad(hole.astype(np.uint8), ((pad_t, full - h - pad_t), (pad_l, full - w - pad_l)))
    scaled = cv2.resize(padded, (side, side), interpolation=cv2.INTER_LINEAR)
    scaled_mask = cv2.resize(padded_mask, (side, side), interpolation=cv2.INTER_NEAREST)
    feed = {
        names[0]: (scaled.astype(np.float32) / 255.0).transpose(2, 0, 1)[None],
        names[1]: scaled_mask[None, None].astype(np.float32),
    }
    out = session.run(None, feed)[0][0].transpose(1, 2, 0)
    if out.max() <= 1.5:          # 有的导出输出 0..1，有的 0..255
        out = out * 255.0
    out = np.clip(out, 0, 255)
    restored = cv2.resize(out, (full, full), interpolation=cv2.INTER_LINEAR)
    return restored[pad_t:pad_t + h, pad_l:pad_l + w]


def inpaint_onnx_tiled(image: np.ndarray, hole: np.ndarray, model: Path,
                       tile: int = 512, overlap: int = 128) -> np.ndarray:
    """1:1 分块推理，避免把遮挡带缩掉。

    Big-LaMa 的 ONNX 空间维写死 512（只有 batch 动态），整幅送入要先把 720 缩到 512，
    压缩 1.41×——而实测遮挡带 67.5% 只有 4–8px 宽，一条 6px 的带在推理时只剩 4.3px。
    改成按原生像素切 512 的块逐块推理，块内不缩放，把这 1.41× 拿回来。
    无掩膜的块直接跳过（本例约七成块可跳），代价可控。
    """
    import onnxruntime as ort

    session = ort.InferenceSession(str(model), providers=["CPUExecutionProvider"])
    names = [i.name for i in session.get_inputs()]
    h, w = image.shape[:2]
    stride = tile - overlap
    pad_r = max(0, -(-max(w - tile, 0) // stride) * stride + tile - w) if w > tile else tile - w
    pad_b = max(0, -(-max(h - tile, 0) // stride) * stride + tile - h) if h > tile else tile - h
    padded = np.pad(image.astype(np.float32), ((0, pad_b), (0, pad_r), (0, 0)), mode="reflect")
    padded_mask = np.pad(hole.astype(np.uint8), ((0, pad_b), (0, pad_r)))
    ph, pw = padded.shape[:2]

    # 余弦窗，重叠区平滑过渡，避免块界留缝
    ramp = np.hanning(overlap * 2)[:overlap]
    win1d = np.ones(tile, dtype=np.float32)
    win1d[:overlap] = ramp
    win1d[-overlap:] = ramp[::-1]
    window = np.outer(win1d, win1d).astype(np.float32)

    accum = np.zeros((ph, pw, 3), np.float32)
    weight = np.zeros((ph, pw), np.float32)
    used = 0
    for y in range(0, max(ph - tile, 0) + 1, stride):
        for x in range(0, max(pw - tile, 0) + 1, stride):
            m = padded_mask[y:y + tile, x:x + tile]
            if m.sum() == 0:
                continue
            used += 1
            patch = padded[y:y + tile, x:x + tile]
            out = session.run(None, {
                names[0]: (patch / 255.0).transpose(2, 0, 1)[None],
                names[1]: m[None, None].astype(np.float32),
            })[0][0].transpose(1, 2, 0)
            if out.max() <= 1.5:
                out = out * 255.0
            accum[y:y + tile, x:x + tile] += np.clip(out, 0, 255) * window[..., None]
            weight[y:y + tile, x:x + tile] += window
    print(f"  分块补全：{used} 块（{tile}px，重叠 {overlap}）")
    filled = np.where(weight[..., None] > 1e-6, accum / np.maximum(weight, 1e-6)[..., None], padded)
    return filled[:h, :w]


def exclude_foreground(inv: np.ndarray, mask: np.ndarray, inv_layer: np.ndarray,
                       fx: float, baseline: float, radius: int,
                       margin_px: float = 3.0,
                       matte: np.ndarray | None = None,
                       matte_mode: str = "intersect",
                       region_complete: bool = False,
                       region_frac: float = 0.15,
                       bg_extrap: str = "blur") -> np.ndarray:
    """把带附近**比背景层级更近**的像素也加进补全掩膜——即"源区排除前景"。

    遮挡带长在**遮挡物那一侧**，所以带的一半邻域就是物体自己。直接把原图喂给补全模型，
    它会顺着物体的边缘和纹理往带里续接，于是第二层里留下一份物体轮廓的副本；第一层
    一让开，那份副本就露出来了——用户 2026-08-11 观察到的"物体轮廓复制了一份的条带"
    正是它，且"偏透明还是实心取决于前景与背景的像素差"也与此一致。
    Shih et al.（CVPR 2020）对这条的表述是 "excluding foreground information from
    both source regions and target patches"，我们此前只在深度上做到了（最小值传播），
    颜色上没有。

    做法是**有方向的外扩**：只吃"比带内背景层级更近"的像素，背景那一侧的真实上下文
    一格不动。后者很重要——D160 实测过无差别膨胀是有害的（拿走了模型需要的上下文）。

    radius < 0 表示不限半径，整个前景都排除（等价于经典的"物体移除"任务）。
    """
    m = mask.astype(np.uint8)
    if bg_extrap == "nearest":
        # **按最近的带像素**取背景层级，而不是加权平滑（2026-08-11）。
        # 平滑外推有两个毛病：一是远处会被自身混进来而退化（见下），二是它把不同剪影的
        # 背景层级搅在一起。改成"每个像素问离它最近的那条带：你背后是什么深度"，
        # 判据仍是同一条 inv > bg + jump，但**含义变严格了**：
        #   - 大物体（蛋糕、盘子）的中心离带远，最近的带仍是它自己的剪影、背景是墙，
        #     于是整块都判为前景被排除 —— 正是 D171 上分割掩膜想补的那个洞；
        #   - **自遮挡**（手压衣袖）处，最近的带就是手的剪影、它的背景层级就是衣袖本身，
        #     衣袖不比自己更近，因此绝不会被排除 —— 分割掩膜恰恰在这里犯错（D174）。
        dist, lbl = cv2.distanceTransformWithLabels(
            (1 - m), cv2.DIST_L2, 5, labelType=cv2.DIST_LABEL_PIXEL)
        ys, xs = np.nonzero(mask)
        lut = np.zeros((int(lbl.max()) + 1, 2), np.int32)
        lut[lbl[mask], 0] = ys
        lut[lbl[mask], 1] = xs
        bg = inv_layer[lut[lbl, 0], lut[lbl, 1]].astype(np.float32)
        # 单个带像素的层级可能是噪声，会独占一大片。取 9×9 局部均值压一下——
        # 半径远小于此前的 41–1001 档，不会把远处的自身值混回来。
        bg = cv2.blur(bg, (9, 9))
    else:
        # 带内已知背景层级，向外**多尺度**外推。原来只用 41×41 一档，离带 20px 之外
        # 加权和就归零、bg 退化成 inv 自身，判据永远不成立——这就是为什么"不限半径"和
        # "半径 12"结果逐位相同（2026-08-11 查出）。由细到粗逐档兜底，保证全图都有值。
        src = np.where(mask, inv_layer, 0.0).astype(np.float32)
        wgt = m.astype(np.float32)
        bg = np.full_like(inv, np.nan, dtype=np.float32)
        for k in (41, 121, 361, 1001):
            n2 = cv2.blur(src, (k, k)); d2 = cv2.blur(wgt, (k, k))
            ok = (d2 > 1e-6) & ~np.isfinite(bg)
            bg = np.where(ok, n2 / np.maximum(d2, 1e-6), bg)
            if np.isfinite(bg).all():
                break
        bg = np.where(np.isfinite(bg), bg, float(np.nanmin(inv_layer)))
    # 判据与断边同源：错开 margin_px 个像素所需的逆深度差
    jump = margin_px / max(fx * baseline, 1e-9)
    near = np.ones_like(mask) if radius < 0 else \
        cv2.dilate(m, np.ones((2 * radius + 1,) * 2, np.uint8)) > 0
    fg = near & (~mask) & (inv > bg + jump)

    if region_complete:
        # 区域补全：按**深度断崖**把画面切成连通区域，一个区域只要有足够比例被判为前景，
        # 整块都算前景。这治的是"阈值只够到大物体边缘"——蛋糕是连续爬升的曲面，
        # 离带远一点差值就掉到阈值以下，但它显然整块都是遮挡物（D171 实测只排除了 32.9%）。
        # 只用深度和与断边同源的判据，不需要任何分割模型。
        cut_r = np.abs(np.diff(inv, axis=1)) > jump
        cut_d = np.abs(np.diff(inv, axis=0)) > jump
        wall = np.zeros(inv.shape, np.uint8)
        wall[:, :-1] |= cut_r
        wall[:-1, :] |= cut_d
        n_lab, lab = cv2.connectedComponents((wall == 0).astype(np.uint8), connectivity=4)
        if n_lab > 1:
            flat = lab.ravel()
            area = np.bincount(flat, minlength=n_lab).astype(np.float32)
            hit = np.bincount(flat, weights=(fg | mask).ravel().astype(np.float32),
                              minlength=n_lab)
            frac = hit / np.maximum(area, 1.0)
            # **必须同时要求这块区域整体确实比背景层级更近**。只看命中率会把背景自己
            # 吞掉——背景也是一个连通大区域，只要碰到带就命中（第一版实测排除到 99.99%，
            # 整幅图都成了"前景"）。
            sum_inv = np.bincount(flat, weights=inv.ravel(), minlength=n_lab)
            sum_bg = np.bincount(flat, weights=bg.ravel(), minlength=n_lab)
            nearer = (sum_inv - sum_bg) / np.maximum(area, 1.0) > jump
            # 面积过小的碎片不参与（噪声）；命中率够高、且整体更近的整块吸收
            take = (frac >= region_frac) & (area >= 64) & nearer
            take[0] = False
            fg = fg | take[lab]

    if matte is not None:
        # 物体掩膜要**先膨胀**，免得留下一圈边又被抄进去
        # （Seeing Through Clutter 2602.04053 同款做法；注意膨胀的是物体不是细带，
        # 与 D160"膨胀细带有害"的结论目的相反、不冲突）。
        mm = cv2.dilate(matte.astype(np.uint8), np.ones((9, 9), np.uint8)) > 0
        # 半径同样约束分割掩膜（2026-08-11）。此前 `near` 只管深度那一支，`fg |= mm`
        # 是无条件的，于是 `--exclude-fg -1` 会把**整个物体**挖掉：05 的洞占到 48.8%，
        # 带附近 512 窗口里只剩 28.7% 的真实像素，LaMa 只能吐一片冷灰——
        # 用户圈出的"蛋糕右缘的灰条"就是它。我们只需要带那一圈的上下文干净，
        # 不需要把整个物体都变成洞。
        mm &= near
        if matte_mode == "intersect":
            # **取交，不是取并**（D174）。两者适用域不同：
            # - 深度判据在"大物体对背景"的遮挡上够不到远端 → 需要分割补全物体的**范围**；
            # - 分割在**自遮挡**处有害 → 手遮住的是自己的衣袖，把整个人排除掉，
            #   等于把"手背后应该是什么"的答案（深色衣袖）一起拿走，LaMa 只剩明亮背景
            #   可参考，于是补出发灰的条带（用户实测；四档消融确认那条带就是第二层本身）。
            # 正确写法：用分割定**范围**，用深度定这个范围里**哪些真的挡在带前面**。
            nearer = inv > bg + jump
            fg = fg | (mm & nearer)
        else:
            fg = fg | mm
    return mask | fg


def harmonic_extend(values: np.ndarray, known: np.ndarray,
                    levels: int = 6, iters: int = 80) -> np.ndarray:
    """把 `known` 上的值调和延拓（∇²c = 0）到其余像素。多尺度 Jacobi，无需稀疏求解器。

    调和场很光滑，直接在原分辨率迭代收敛极慢（低频要走遍整块区域）；
    逐级下采样先把低频解出来，再上采样当初值，几十次迭代就够。
    """
    v = values.astype(np.float32)
    k = known.astype(np.float32)
    if v.ndim == 2:
        v = v[..., None]
    pyr_v, pyr_k = [v], [k]
    for _ in range(levels - 1):
        if min(pyr_k[-1].shape[:2]) <= 8:
            break
        # 下采样要按"已知像素的加权平均"降，否则未知区的 0 会把边界值拖暗
        kk = cv2.pyrDown(pyr_k[-1])
        vv = cv2.pyrDown(pyr_v[-1] * pyr_k[-1][..., None])
        if vv.ndim == 2:
            vv = vv[..., None]
        pyr_v.append(np.where(kk[..., None] > 1e-3, vv / np.maximum(kk[..., None], 1e-6), 0.0))
        pyr_k.append(kk)
    c = np.zeros_like(pyr_v[-1])
    for lv in range(len(pyr_v) - 1, -1, -1):
        vv, kk = pyr_v[lv], pyr_k[lv]
        hard = kk > 0.5
        if c.shape[:2] != vv.shape[:2]:
            c = cv2.resize(c, (vv.shape[1], vv.shape[0]), interpolation=cv2.INTER_LINEAR)
            if c.ndim == 2:
                c = c[..., None]
        c = np.where(hard[..., None], vv, c)
        for _ in range(iters):
            p = np.pad(c, ((1, 1), (1, 1), (0, 0)), mode="edge")
            neigh = 0.25 * (p[:-2, 1:-1] + p[2:, 1:-1] + p[1:-1, :-2] + p[1:-1, 2:])
            c = np.where(hard[..., None], vv, neigh)
    return c if values.ndim == 3 else c[..., 0]


def seamless_blend(generated: np.ndarray, image: np.ndarray, omega: np.ndarray,
                   ring: int = 1) -> np.ndarray:
    """梯度域（Poisson）合成：把 `generated` 在 `omega` 内贴进 `image`，只保留它的**纹理**。

    等价形式：设边界失配 d = generated − image（只在 Ω 外圈一环上取），
    解 ∇²c = 0、c|∂Ω = d，再取 generated − c。这与"以 ∇generated 为引导场解 Poisson"
    同解，但只需调和延拓，数值上稳得多。

    **Ω 必须取送进模型的整块补全区域，不能只取遮挡带**：带的一侧邻域是遮挡物本身，
    拿它当边界条件等于把前景的颜色又搬回来（D137/D159 两次栽在同一类参照上）。
    而补全区域按构造已经把"比带内背景更近"的东西全挖掉了，它的外圈**只剩真实背景**。
    """
    known = ~omega
    if known.sum() < 256:
        return generated
    # 已知区取**整个 Ω 之外**，不是只取一圈。一圈在多尺度下采样时会直接消失
    # （1px 的环降两级就没了，粗层解不出低频，细层几十次 Jacobi 又传不远，
    #  实测跨缝落差纹丝不动 +19.8 → +19.8），这一版换成全外部，边界条件同样是那一圈，
    #  但低频在粗层就能解出来。
    d = (generated.astype(np.float32) - image.astype(np.float32)) * known[..., None]
    c = harmonic_extend(d, known)
    return np.clip(generated.astype(np.float32) - c, 0, 255)


def _nearest_at_level(image_shape: tuple[int, int], inv: np.ndarray, mask: np.ndarray,
                      level: np.ndarray, tol: float, bins: int = 24
                      ) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """带内每点 → 与 `level[p]` **同深度**的最近**带外真实像素**的坐标（yy, xx）与距离。

    分箱是必需的：源集合要随每个点自己的目标层级变化，一次全局距离变换做不到。
    箱内没有足够真实像素时放宽到 3·tol，仍不够就留 inf（该点无参照）。
    """
    h, w = image_shape
    yy = np.zeros((h, w), np.int32)
    xx = np.zeros((h, w), np.int32)
    dd = np.full((h, w), np.inf, np.float32)
    lv = level[mask]
    if lv.size == 0:
        return yy, xx, dd
    edges = np.linspace(float(np.percentile(lv, 0.5)), float(np.percentile(lv, 99.5)), bins + 1)
    for i in range(bins):
        c = 0.5 * (edges[i] + edges[i + 1])
        want = mask & (level >= edges[i]) & ((level < edges[i + 1]) if i < bins - 1
                                             else np.ones_like(mask))
        if not want.any():
            continue
        src = (~mask) & (np.abs(inv - c) <= tol)
        if src.sum() < 32:
            src = (~mask) & (np.abs(inv - c) <= 3 * tol)
        if src.sum() < 32:
            continue
        dist, lbl = cv2.distanceTransformWithLabels(
            (~src).astype(np.uint8), cv2.DIST_L2, 5, labelType=cv2.DIST_LABEL_PIXEL)
        sy, sx = np.nonzero(src)
        lut = np.zeros((int(lbl.max()) + 1, 2), np.int32)
        lut[lbl[src], 0] = sy
        lut[lbl[src], 1] = sx
        yy[want] = lut[lbl, 0][want]
        xx[want] = lut[lbl, 1][want]
        dd[want] = dist[want]
    return yy, xx, dd


def load_instances(path: Path) -> np.ndarray | None:
    """`segment_occluders.py --save` 产出的逐实例掩膜栈。"""
    if not path.is_file():
        return None
    d = np.load(path)
    n, h, w = d["shape"]
    return np.unpackbits(d["masks"])[: n * h * w].reshape(n, h, w).astype(bool)


def decide_selfocclusion(inv: np.ndarray, mask: np.ndarray, fx: float, baseline: float,
                         instances: np.ndarray, radius: int = 12, max_inst: int = 8,
                         min_band: float = 0.02
                         ) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """逐带像素判：**遮挡物与它让出的那片背景，是不是同一个物体**（D176/D177）。

    是（手压衣袖）→ 那个物体是"背后是什么"的唯一答案，必须留在补全上下文里 → 保守档；
    否（盘子挡玫瑰）→ 遮挡物只会被抄进带里 → 激进档。

    判法用**带自己的定义**，不再去反猜"背景侧那个像素在哪"：
    `disocclusion_mask` 说 p 在带里，是因为 p 半径 r 内存在比它远 r/(fx·t) 以上的面。
    那么把实例 I 的像素**从候选的"更远的面"里剔掉**再算一遍：

    - p 仍在带里 → 存在**不属于 I** 的面会被露出来 → 露的是别的物体 → 激进；
    - p 掉出带外 → 唯一能被露出来的就是 I 自己 → 自遮挡 → 保守。

    此前两版都栽在"背景侧取样"上：先用"同深度的最近带外像素"（05 的托盘立柱与玫瑰
    几乎同深，取到了立柱），再用 `propagate_background_depth` 的来源像素（那是**先到先得**
    的洪水填充，带的内侧先被遮挡物自己的深度填上，来源根本不是背景）。
    两次都是拿一个近似量去替代"这条带会露出什么"，而这件事**带的定义里本来就写着**。

    统一用**局部多数**（半径 radius，只在有意见的像素里数），不是连通块投票。
    连通块是错的粒度：一条带绕着物体是连通的，却同时跨好几种遮挡关系——05 里
    "蛋糕对墙""盘沿对玫瑰""托盘对桌布"全在同一个连通块里，按块投票会整条一起翻面。

    返回 (keep, decidable, same)。
    """
    h, w = inv.shape
    scale = fx * baseline
    span = float(np.percentile(inv, 99.8) - np.percentile(inv, 0.2))
    rmax = int(np.clip(np.ceil(scale * span), 1, 64))
    big = float(inv.max()) * 10.0                     # 哨兵：腐蚀取最小值，永远选不到它

    # 只看确实压在带上的实例，且按覆盖排序取前几个——57 个实例逐个重算代价太高，
    # 覆盖极小的那些也左右不了局部多数。
    cand = sorted(((float((m & mask).sum()) / max(mask.sum(), 1), i)
                   for i, m in enumerate(instances)), reverse=True)
    cand = [i for f, i in cand if f >= min_band][:max_inst]

    same = np.zeros((h, w), bool)
    decidable = np.zeros((h, w), bool)
    for i in cand:
        m = instances[i]
        masked_inv = np.where(m, big, inv).astype(np.float32)
        reveal_other = np.zeros((h, w), bool)
        for r in range(1, rmax + 1):
            ker = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
            reveal_other |= (inv - cv2.erode(masked_inv, ker)) >= (r / max(scale, 1e-9))
        here = m & mask
        decidable |= here
        same |= here & (~reveal_other)                # 能露出来的只有 I 自己
    same &= mask
    decidable &= mask

    k = 2 * radius + 1
    # 分母只数"有意见"的像素。没意见的若也计入分母，一段 80% 无实例的带会被稀释成
    # 激进，哪怕每一个能判的像素都说是自遮挡。
    num = cv2.blur(same.astype(np.float32), (k, k))
    den = cv2.blur(decidable.astype(np.float32), (k, k))
    frac = num / np.maximum(den, 1e-6)
    # 邻域里能判的像素太少就不下结论，退回激进（= 今天 matte 在场时的行为）
    enough = den * k * k >= 32
    keep = mask & enough & (frac >= 0.5)
    return keep, decidable, same


def inpaint_moebius(image: np.ndarray, hole: np.ndarray, mode: str,
                    steps: int, cfg: float, drift_sigma: float = 24.0) -> np.ndarray:
    """Moebius（ECCV 2026，0.226B，权重 MIT）。

    以子进程调用 `moebius_infer.py`：Moebius 仓库有顶层 `utils.py` / `utils_infer.py`，
    直接 import 会污染本管线的模块空间。模型只接受 512 方形输入（交叉注意力的位置
    编码是 n*n 的固定参数，硬锁在构建分辨率），驱动内部按 512 / 重叠 128 做 1:1 分块，
    与 `inpaint_onnx_tiled` 同规格，A/B 单变量成立。
    """
    import subprocess
    import sys
    import tempfile

    root = Path("E:/projects/EverythingDone")
    with tempfile.TemporaryDirectory(prefix="moebius_") as tmp:
        tmp = Path(tmp)
        Image.fromarray(image.astype(np.uint8)).save(tmp / "image.png")
        Image.fromarray((hole.astype(np.uint8) * 255)).save(tmp / "mask.png")
        env = dict(os.environ, PYTHONPATH=str(root / "tmp/Moebius"))
        cmd = [sys.executable, str(root / "tmp/spatial-desktop-tuning/moebius_infer.py"),
               "--image", str(tmp / "image.png"), "--mask", str(tmp / "mask.png"),
               "--out", str(tmp / "out.png"), "--mode", mode,
               "--steps", str(steps), "--cfg", str(cfg),
               "--drift-sigma", str(drift_sigma)]
        proc = subprocess.run(cmd, env=env, capture_output=True, text=True, encoding="utf-8",
                              errors="replace")
        if proc.returncode != 0:
            raise RuntimeError(f"Moebius 推理失败：\n{proc.stdout[-2000:]}\n{proc.stderr[-2000:]}")
        for line in proc.stdout.splitlines():
            if "块" in line or "补全完成" in line:
                print(f"  {line.strip()}")
        return np.asarray(Image.open(tmp / "out.png").convert("RGB")).astype(np.float32)


def inpaint_sdxl(image: np.ndarray, hole: np.ndarray, prompt: str, steps: int, seed: int) -> np.ndarray:
    """与 D132 同契约：方形反射填充 → 1024 推理 → 还原尺寸，固定种子保证可复现。"""
    import torch
    from diffusers import AutoPipelineForInpainting

    pipe = AutoPipelineForInpainting.from_pretrained(
        "diffusers/stable-diffusion-xl-1.0-inpainting-0.1",
        torch_dtype=torch.float16, variant="fp16",
    ).to("cuda")
    h, w = image.shape[:2]
    side = max(w, h)
    pad_l, pad_t = (side - w) // 2, (side - h) // 2
    padded = np.pad(image, ((pad_t, side - h - pad_t), (pad_l, side - w - pad_l), (0, 0)), mode="reflect")
    padded_mask = np.pad(hole.astype(np.uint8), ((pad_t, side - h - pad_t), (pad_l, side - w - pad_l)))
    scaled = cv2.resize(padded, (1024, 1024), interpolation=cv2.INTER_AREA)
    scaled_mask = cv2.resize(padded_mask, (1024, 1024), interpolation=cv2.INTER_NEAREST)
    generator = torch.Generator("cuda").manual_seed(seed)
    result = pipe(
        prompt=prompt, image=Image.fromarray(scaled),
        mask_image=Image.fromarray((scaled_mask * 255).astype(np.uint8)),
        num_inference_steps=steps, generator=generator,
    ).images[0]
    out = cv2.resize(np.asarray(result).astype(np.float32), (side, side), interpolation=cv2.INTER_LINEAR)
    return out[pad_t:pad_t + h, pad_l:pad_l + w]


def seam_step(color: np.ndarray, base: np.ndarray, mask: np.ndarray,
              exclude: np.ndarray | None = None) -> float:
    """跨缝色阶落差：带内紧邻 vs 带外**干净**参照环（退开 3px 避开被测对象自己，D137）。

    `exclude` 必须传补全区域（送进模型的掩膜）。参照环是"带外 3–8px"，
    在整幅背景板那一档里这一圈**大半落在被挖掉的前景上**——那里 `base` 是蛋糕本身，
    于是量出 +19.5 级的假落差，膜校正的守卫信了它、给整张背景板压了 −23 级，
    渲染上就是"一片偏暗的灰褐"。那不是补全的毛病，是指标取错了参照
    （2026-08-11；与 D137/D159 两次栽在参照环上属同一类）。
    """
    m = mask.astype(np.uint8)
    inner = (m - cv2.erode(m, np.ones((5, 5), np.uint8))) > 0
    outer = (cv2.dilate(m, np.ones((17, 17), np.uint8)) - cv2.dilate(m, np.ones((7, 7), np.uint8))) > 0
    if exclude is not None:
        outer &= ~exclude
    if inner.sum() < 64 or outer.sum() < 64:
        return float("nan")
    grey = lambda a: cv2.cvtColor(a.astype(np.float32), cv2.COLOR_RGB2GRAY)
    return float(np.median(grey(color)[inner]) - np.median(grey(base)[outer]))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", type=Path, default=Path("tmp/spatial-desktop-tuning/assets"))
    ap.add_argument("--geometry", type=Path, default=Path("tmp/spatial-desktop-tuning/qa/moge-geometry"))
    ap.add_argument("--scenes", nargs="*", default=["00_original_single"])
    ap.add_argument("--max-span-px720", type=float, default=60.0,
                    help="按查看器滑杆上限建带，保证任何强度下都够用")
    ap.add_argument("--max-span-cm", type=float, default=None,
                    help="改按**物理基线**给带宽预算（推荐）。像素预算随场景自动算出，"
                         "再按 --span-ceiling-px 截顶。给了这个就忽略 --max-span-px720。"
                         "统一用像素给预算是错的：160px 在人像上是 16.7cm，"
                         "在 06_statue 上是 171.6cm——按后者建带只会把掩膜从 11% 涨到 38%，"
                         "补全难度白白暴涨，而那段预算永远用不上。")
    ap.add_argument("--span-ceiling-px", type=float, default=160.0,
                    help="像素预算的上限（浅景深场景折算出来的像素数会很大）")
    ap.add_argument("--backend",
                    choices=("telea", "lama", "lama_tiled", "aotgan", "sdxl",
                             "moebius", "moebius_square", "migan"),
                    default="lama_tiled")
    ap.add_argument("--moebius-cfg", type=float, default=2.0)
    ap.add_argument("--moebius-steps", type=int, default=20)
    ap.add_argument("--fg-span-px720", type=float, default=None, metavar="PX",
                    help="前景排除判据 jump 用的视差尺度（px@720）。缺省=带宽预算本身。\n"
                         "**这两件事必须能分开**：带宽应当按渲染真正用的基线给（4.5cm），\n"
                         "而 jump = margin_px/(fx·baseline) 决定"
                         "「哪些像素算比带的背景更近」。共用一个 baseline 时，把带宽预算\n"
                         "从 60 缩到实需（06 场景只要 4px），jump 会大 15 倍，前景排除等于\n"
                         "被关掉——同一条带上实测抄袭度 0.209→0.673（D198 补充）。")
    ap.add_argument("--exclude-fg", type=int, default=12, metavar="RADIUS",
                    help="源区排除前景：把带附近比背景层级更近的像素也送进补全掩膜。"
                         "0=关闭，负数=不限半径（整个前景都排除）。"
                         "实测 12px 已饱和（24/48/不限 三者结果完全一致）。")
    ap.add_argument("--region-complete", action="store_true",
                    help="按深度断崖切连通区域，命中率够高的整块吸收为前景。"
                         "不需要分割模型，治'阈值只够到大物体边缘'")
    ap.add_argument("--region-frac", type=float, default=0.15,
                    help="区域被判为前景所需的命中比例")
    ap.add_argument("--bg-extrap", choices=("blur", "nearest"), default="blur",
                    help="带外背景层级怎么外推：blur=多尺度加权平滑（旧默认）；"
                         "nearest=按最近的带像素取（大物体能整块判到，自遮挡处天然不误判）")
    ap.add_argument("--matte-mode", choices=("intersect", "union"), default="intersect",
                    help="分割掩膜与深度判据的组合方式。intersect=只排除「分割实例 ∩ 比背景更近」"
                         "（默认，D174）；union=全排除（在自遮挡处有害，仅作对照）")
    ap.add_argument("--dual-pass", action="store_true",
                    help="两遍补全：激进档（∪ 分割掩膜）与保守档（只用深度）各补一次，"
                         "再按 D177 的实例归属判定逐条带挑。需要 occluder_instances.npz。")
    ap.add_argument("--feather", type=int, default=3,
                    help="两档合成时的羽化半径（px），只在带内起作用")
    ap.add_argument("--decision-radius", type=int, default=12,
                    help="逐条带判定的局部多数半径（px）")
    ap.add_argument("--plate", action="store_true",
                    help="整幅背景板：掩膜 = 遮挡物分割膨胀 ∪ 遮挡带，一次补完整张背景，"
                         "带内颜色从它取。洞很大（05 是 48.7%），**回归模型会吐条件均值**"
                         "（Big-LaMa 在 05 上给出一根冷灰柱，正是用户圈的灰条），"
                         "这一档应配生成式后端（--backend moebius）。")
    ap.add_argument("--seamless", action="store_true",
                    help="梯度域（Poisson）合成：以补全区域的外圈真实背景为边界条件，"
                         "把模型输出的低频换掉、只留纹理。生成式后端（Moebius/SDXL）必开——"
                         "它们会重写整幅图，跨缝落差空间不均匀，全局标量偏置修不掉。")
    ap.add_argument("--occluders-suffix", default="",
                    help="用哪份遮挡物分割（segment_occluders.py --out-suffix 的同名后缀）")
    ap.add_argument("--plate-backend", default="moebius",
                    help="整幅背景板那一档用哪个后端。默认 moebius——洞占一半时"
                         "回归模型只会吐条件均值（D179）。保守档仍用 --backend。")
    ap.add_argument("--plate-dilate", type=int, default=9,
                    help="整幅背景板模式下，遮挡物掩膜的膨胀半径")
    ap.add_argument("--use-matte", action="store_true",
                    help="把主体分割掩膜并入'排除前景'。按深度阈值排除在大物体上只够到"
                         "边上一圈（05 的蛋糕只排除了 32.9%%），分割掩膜覆盖整个物体")
    ap.add_argument("--fg-margin-px", type=float, default=3.0,
                    help="判定'比背景更近'的阈值，单位是最大基线下的像素错开量")
    ap.add_argument("--inpaint-dilate", type=int, default=0,
                    help="只膨胀**送进模型**的掩膜，合成仍用原掩膜。"
                         "中心视角的遮挡带是一张细网，而细散掩膜正是补全模型最弱的形态"
                         "（D151）；膨胀成连通块后模型面对的是它擅长的问题，"
                         "多补出来的部分落在带外，渲染时永远看不到，等于免费。")
    # 提示词纠正（D150）：旧值 "background scene, nobody, empty" 是为"抠掉整个人"设计的，
    # 用在细网状遮挡带上会让 SDXL 沿边缘刷灰白（实测跨缝 +45.3 级）。这里要的是
    # **续接周围纹理**，不是生成一个新场景。
    ap.add_argument("--prompt", default="seamless continuation of the surrounding background texture")
    ap.add_argument("--steps", type=int, default=30)
    ap.add_argument("--seed", type=int, default=20260808)
    ap.add_argument("--lama", type=Path,
                    default=Path("build/spatial-model-poc/artifacts/big_lama_places2_512_fp32.onnx"))
    ap.add_argument("--aotgan", type=Path,
                    default=Path("build/spatial-model-poc/artifacts/aotgan_places2_512.onnx"))
    ap.add_argument("--tag", default="", help="输出文件名后缀，用于并列多个后端做 A/B")
    ap.add_argument("--no-membrane", action="store_true", help="关闭膜校正作对照")
    args = ap.parse_args()

    for scene in args.scenes:
        geo = args.geometry / scene
        meta = json.loads((geo / "moge-meta.json").read_text(encoding="utf-8"))
        w, h = meta["width"], meta["height"]
        z = np.fromfile(geo / "depth_z.f32", dtype=np.float32).reshape(h, w)
        inv = 1.0 / np.maximum(z, 1e-6)
        image = np.asarray(Image.open(args.assets / meta["scene"] / "center.jpg").convert("RGB"))

        bpp720 = meta["baselinePerPixel"] * max(w, h) / 720.0   # 每 px@720 对应的基线（米）
        if args.max_span_cm is not None:
            span_px = min(args.span_ceiling_px, (args.max_span_cm / 100.0) / max(bpp720, 1e-12))
        else:
            span_px = args.max_span_px720
        baseline = span_px * bpp720
        # 前景排除的判据尺度与带宽预算解耦（见 --fg-span-px720）
        fg_baseline = (args.fg_span_px720 if args.fg_span_px720 else span_px) * bpp720
        mask = disocclusion_mask(inv, meta["fx"], baseline)
        mask = cv2.dilate(mask.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
        print(f"{scene}: 带宽预算 {span_px:.0f} px@720 / 最大基线 {baseline*100:.2f} cm  "
              f"遮挡带 {mask.sum()} px ({100*mask.mean():.2f}%)")

        inv_layer, bg_origin = propagate_background_depth(inv, mask, return_origin=True)

        # 优先用 SAM 3 分割出来的**全部遮挡物**（segment_occluders.py 产出，
        # 已与 matte 取并集）；没有就退回只覆盖显著主体的 matte
        op = args.assets / meta["scene"] / f"occluders{args.occluders_suffix}.png"
        mp = op if op.is_file() else args.assets / meta["scene"] / "matte.png"
        occl = None
        if mp.is_file():
            mi = Image.open(mp).convert("L").resize((w, h), Image.BILINEAR)
            occl = np.asarray(mi) > 127
            if occl.mean() < 0.005:               # 空 matte（如街景无显著主体）视作没有
                occl = None

        def build_paint(matte, matte_mode=None):
            # 深度传播必须用**原掩膜**（它就是要填的那条带）；只有送进补全模型的掩膜才膨胀。
            if args.exclude_fg == 0:
                return mask
            p = exclude_foreground(inv, mask, inv_layer, meta["fx"], fg_baseline,
                                   args.exclude_fg, args.fg_margin_px, matte,
                                   matte_mode or args.matte_mode,
                                   args.region_complete, args.region_frac,
                                   args.bg_extrap)
            if args.inpaint_dilate > 0:
                k = 2 * args.inpaint_dilate + 1
                p = cv2.dilate(p.astype(np.uint8), np.ones((k, k), np.uint8)) > 0
            return p

        raw_out: dict[str, np.ndarray] = {}

        def run_inpaint(p, name="raw", backend=None, seamless=None):
            args_backend, args.backend = args.backend, backend or args.backend
            args_seamless, args.seamless = args.seamless, args.seamless if seamless is None else seamless
            try:
                return _run_inpaint(p, name)
            finally:
                args.backend, args.seamless = args_backend, args_seamless

        def _run_inpaint(p, name="raw"):
            if args.backend == "sdxl":
                c = inpaint_sdxl(image, p, args.prompt, args.steps, args.seed)
            elif args.backend in ("moebius", "moebius_square"):
                c = inpaint_moebius(image, p,
                                    "square" if args.backend == "moebius_square" else "tiled",
                                    args.moebius_steps, args.moebius_cfg)
            elif args.backend == "lama":
                c = inpaint_onnx(image, p, args.lama)
            elif args.backend == "lama_tiled":
                c = inpaint_onnx_tiled(image, p, args.lama)
            elif args.backend == "migan":
                # MI-GAN（5.95M，MIT，官方 ONNX）——**Android 上线的就是它**，而桌面这条线
                # 一直用 Big-LaMa，两边从未对齐过。带真值台九场景实测 MI-GAN 略胜
                # （L1 3.22 vs 3.79、梯度相关 0.797 vs 0.774），所以它不是移植的瓶颈。
                # 契约与 Android 的 runUint8Pipeline 逐位一致（uint8 RGB / mask 0=洞），
                # 分块规格与 inpaint_onnx_tiled 相同，A/B 单变量成立。
                from cz_migan import inpaint_tiled as _migan_tiled
                c = _migan_tiled(image, p)
            elif args.backend == "aotgan":
                c = inpaint_onnx(image, p, args.aotgan)
            else:
                c = cv2.inpaint(image, p.astype(np.uint8), 5, cv2.INPAINT_TELEA).astype(np.float32)
            # **模型吐出来的整张，一个像素都不合成回去**。落盘的 hidden_color 只保留带内、
            # 带外逐像素等于原图（D133 纪律），于是诊断视图里除了那条细带全是原图——
            # "模型到底补出了什么"完全看不到（2026-08-11 用户点名）。
            # 第一版修错了：写成 `np.where(p, c, image)`，掩膜外又贴回了原图，
            # 前景照样在，等于没修。这一档就是要看**未经任何合成**的输出。
            raw_out[name] = c.copy()
            if args.seamless:
                # 梯度域合成：把模型输出的**低频**换成真实背景的，只保留它的纹理。
                # 潜空间扩散会把整幅图重写一遍（Moebius 实测未遮挡区 26.66 dB / 7.39 级，
                # D160），落到跨缝上是 +19.5 级、且**空间不均匀**，单一标量偏置修不掉。
                before = c
                c = seamless_blend(c, image.astype(np.float32), p)
                shift = (before - c)[p]
                print(f"  梯度域合成：Ω {100*p.mean():.1f}%，"
                      f"低频修正中位 {np.median(shift):+.1f} 级、"
                      f"p5..p95 {np.percentile(shift, 5):+.1f}..{np.percentile(shift, 95):+.1f}")
                raw_out[name + "_poisson"] = c.copy()
            # 补全只允许改写洞内；洞外必须逐像素等于原图（D133 同纪律）
            return np.where(mask[..., None], c, image.astype(np.float32))

        def plate_mask():
            k = 2 * args.plate_dilate + 1
            p = mask.copy()
            if occl is not None:
                p |= cv2.dilate(occl.astype(np.uint8), np.ones((k, k), np.uint8)) > 0
            return p

        decision = None
        if args.plate and not args.dual_pass:
            paint = plate_mask()
            print(f"  整幅背景板：掩膜 {100*paint.mean():.2f}%（带 {100*mask.mean():.2f}%）")
            color = run_inpaint(paint)
        elif args.dual_pass:
            # 两遍补全（D177）。`--use-matte` 这个开关**两边各有各的问题**，而且问题
            # 在同一张照片里同时存在：00 的手部带要保守（手遮的是自己的衣袖，把人从
            # 上下文里删掉就没人能回答"背后是什么"），人物外剪影那条带要激进。
            # 所以不选边，两档都补出来，再按"遮挡物与它让出的背景是不是同一个物体"
            # 逐条带挑。渲染时仍然只有一份烘焙好的第二层，端上开销不变。
            inst = load_instances(args.assets / meta["scene"]
                                  / f"occluder_instances{args.occluders_suffix}.npz")
            if inst is None:
                raise SystemExit(f"{scene}: 缺 occluder_instances.npz，先跑 "
                                 f"segment_occluders.py")
            keep, decidable, _ = decide_selfocclusion(inv, mask, meta["fx"], baseline,
                                                      inst, args.decision_radius)
            # 激进档必须显式取并集：`--matte-mode` 的默认值是 intersect，而 D175 已证
            # 取交是**空操作**（分割掩膜的贡献恒为零）。不写死这一行，两档掩膜会一模一样。
            # 激进档：`--plate` 时换成**整幅背景板 + 生成式后端 + 梯度域合成**（D179/D181）。
            # 洞占到一半，回归模型只会吐条件均值（那根冷灰柱）；这一档要的是"凭空生成
            # 一片可信的背景"，正是生成式模型的主场。保守档仍用回归后端——
            # 那里洞很细、答案由上下文唯一确定，D160 的带真值评测说了算。
            paint_a = plate_mask() if args.plate else build_paint(occl, "union")
            paint_c = build_paint(None)            # 保守：只用深度判据
            print(f"  两遍补全：激进档掩膜 {100*paint_a.mean():.2f}%"
                  f"（{'整幅背景板/' + args.plate_backend if args.plate else args.backend}）、"
                  f"保守档 {100*paint_c.mean():.2f}%（{args.backend}）")
            col_a = run_inpaint(paint_a, "raw_aggr",
                                backend=args.plate_backend if args.plate else None,
                                seamless=True if args.plate else None)
            col_c = run_inpaint(paint_c, "raw_cons", seamless=False)
            # 羽化只在带内有意义：两档在带外逐像素相同（都被钳回原图），所以直接模糊即可
            wgt = cv2.blur(keep.astype(np.float32), (2 * args.feather + 1,) * 2)
            color = col_c * wgt[..., None] + col_a * (1.0 - wgt[..., None])
            paint = np.where(keep, paint_c, paint_a)
            # 两档各自送进模型的掩膜也单独落盘：查看器要靠它复原"模型到底看到了什么"
            # （实测 ONNX 图内部做了 img*(1−mask)，洞内像素改成随机噪声输出逐位不变，
            #  所以模型的实际输入就是"洞被置零的图"）
            for nm2, pm2 in (("aggr", paint_a), ("cons", paint_c)):
                Image.fromarray((pm2 * 255).astype(np.uint8), mode="L").save(
                    geo / f"hidden_paint_{nm2}{args.tag}.png")
            decision = np.zeros((h, w), np.uint8)
            decision[mask & keep] = 85             # 同一物体 -> 保守
            decision[mask & ~keep] = 170           # 不同物体 -> 激进
            decision[mask & ~decidable] = 255      # 遮挡侧无实例，规则无意见（并入激进）
            n = max(int(mask.sum()), 1)
            print(f"  逐条带判定：保守 {100*(mask & keep).sum()/n:.1f}%、"
                  f"激进 {100*(mask & ~keep).sum()/n:.1f}%"
                  f"（其中无实例可判 {100*(mask & ~decidable).sum()/n:.1f}%）")
        else:
            matte = occl if args.use_matte else None
            paint = build_paint(matte)
            if args.exclude_fg != 0:
                print(f"  源区排除前景（半径 {args.exclude_fg}px"
                      f"{'，区域补全' if args.region_complete else ''}"
                      f"{'，含分割掩膜' if matte is not None else ''}）："
                      f"{100*mask.mean():.2f}% -> {100*paint.mean():.2f}%")
            color = run_inpaint(paint)
        step = seam_step(color, image.astype(np.float32), mask, paint)
        print(f"  跨缝色阶落差（校正前）{step:+.1f} 级")
        # 膜校正：补全内容整体比紧邻真实背景暗一截，位移把它露出来就是"黑暗的背景"
        # （用户 2026-08-10 实测第二层比紧邻第一层暗 5.7 级）。
        # 参照环必须退开剪影软边——D137 的教训：贴边取会把 发/衣×背景 的暗混合像素
        # 当成"真实背景亮度"，反而把补全压得更暗。
        m8 = mask.astype(np.uint8)
        outer = (cv2.dilate(m8, np.ones((17, 17), np.uint8))
                 - cv2.dilate(m8, np.ones((7, 7), np.uint8))) > 0
        inner = (m8 - cv2.erode(m8, np.ones((5, 5), np.uint8))) > 0
        # 色阶校正（D159，第三版）：**全局标量偏置**，参照退开剪影 6px。
        # 前两版都栽在同一处——对称取环（D158）和最小逆深度传播（本轮）取的参照都紧邻
        # 剪影，而那一圈是 发/衣×背景 的暗混合像素，于是把补全越校越暗
        # （渲染 −5.7 → −7.1 / −6.2）。局部参照在这条细带上不可靠：带的一侧永远是遮挡物。
        # 改为全局：参照 = 掩膜外、且距掩膜 >6px（避开污染带）、且逆深度落在遮挡带自身
        # 背景层级范围内（只取背景侧）的像素，取整体均值差作单一偏置。
        # 单一标量不携带空间结构，因此不可能被某一处局部污染带偏。
        if not args.no_membrane:
            dist_out = cv2.distanceTransform((~mask).astype(np.uint8), cv2.DIST_L2, 5)
            bg_level = inv_layer[mask]
            lo, hi = np.percentile(bg_level, 2), np.percentile(bg_level, 98)
            ref_sel = (~mask) & (dist_out > 6) & (inv >= lo) & (inv <= hi)
            if ref_sel.sum() > 512:
                bias = image.astype(np.float32)[ref_sel].mean(0) - color[mask].mean(0)
                cand = np.where(mask[..., None], np.clip(color + bias, 0, 255), color)
                # 守卫：只在确实改善跨缝落差时才采用（2026-08-11）。
                # "源区排除前景"开启后，补出来的内容本就已经是真背景，跨缝落差从 −6.2
                # 降到 −0.2；此时再按"全局均值应当匹配背景参照"去搬，反而把它推到 +13.2。
                # 这个全局匹配的前提是"补全内容整体偏暗"，前提不成立时它就是净损害。
                cand_step = seam_step(cand, image.astype(np.float32), mask, paint)
                if abs(cand_step) < abs(step):
                    color = cand
                    print(f"  色阶校正：参照 {int(ref_sel.sum())} px（退开 6px、按深度筛背景侧），"
                          f"偏置 R{bias[0]:+.1f} G{bias[1]:+.1f} B{bias[2]:+.1f}")
                else:
                    print(f"  色阶校正：跳过（会把跨缝落差从 {step:+.1f} 推到 "
                          f"{cand_step:+.1f}，偏置 R{bias[0]:+.1f} G{bias[1]:+.1f} B{bias[2]:+.1f}）")
            else:
                print("  色阶校正：干净参照不足，跳过")
        step2 = seam_step(color, image.astype(np.float32), mask, paint)
        print(f"  跨缝色阶落差（校正后）{step2:+.1f} 级")

        z_layer = (1.0 / np.maximum(inv_layer, 1e-9)).astype(np.float32)
        # 第二层必须严格在第一层之后，否则会在 z 竞争里抢到前面
        z_layer = np.maximum(z_layer, z * 1.001).astype(np.float32)

        out = geo
        tag = args.tag
        z_layer.tofile(out / f"hidden_z{tag}.f32")
        Image.fromarray(np.clip(color, 0, 255).astype(np.uint8)).save(out / f"hidden_color{tag}.png")
        Image.fromarray((mask * 255).astype(np.uint8), mode="L").save(out / f"hidden_mask{tag}.png")
        # 送进补全模型的掩膜（带 ∪ 被排除的前景）单独落盘，供查看器的诊断视图显示——
        # 它和最终合成用的 mask 不是一回事，看不到它就没法判断"排除前景"到底排到了哪
        Image.fromarray((paint * 255).astype(np.uint8), mode="L").save(out / f"hidden_paint{tag}.png")
        for name, raw in raw_out.items():
            Image.fromarray(np.clip(raw, 0, 255).astype(np.uint8)).save(
                out / f"hidden_{name}{tag}.png")
        if decision is not None:
            # 逐条带用了哪一档，供查看器的「自遮挡决策」诊断视图显示。
            # 与 selfocc_stat.py --save-map 同一套码值，但这份是**这次构建实际用的**。
            Image.fromarray(decision, mode="L").save(out / f"selfocc_code{tag}.png")
        meta["hiddenLayer"] = {
            "backend": args.backend,
            "maxSpanPx720": span_px,
            "maxBaseline": baseline,
            "maskRatio": float(mask.mean()),
            "seamStep": None if np.isnan(step) else step,
            "dualPass": bool(args.dual_pass),
        }
        (out / "moge-meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"  -> hidden_color.png / hidden_z.f32 / hidden_mask.png")


if __name__ == "__main__":
    main()
