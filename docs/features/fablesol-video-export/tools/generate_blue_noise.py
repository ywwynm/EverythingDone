"""生成 64x64 蓝噪声阈值表（void-and-cluster），供 D157/D162 的码值域抖动使用。

为什么自己生成：libplacebo 是 LGPL 项目，只参考其"以 64x64 蓝噪声在目标位深码值域做阈值
舍入"的策略，不拷贝它的表数据或代码（2026-07-28 实现前审查第五节）。

算法即 Ulichney 1993 的 void-and-cluster：

1. 先用固定种子撒一批 1，再反复把"最紧的簇"里的 1 挪到"最大的空洞"，直到不再变化，
   得到 prototype binary pattern；
2. 阶段一：从 prototype 里逐个取走最紧的簇，秩自 ones-1 递减到 0；
3. 阶段二：逐个往最大的空洞填 1，秩自 ones 递增到 N/2-1；
4. 阶段三：在补集上继续填，秩递增到 N-1。

滤波器为环绕（toroidal）高斯，sigma = 1.5——这是该文献给出的标准取值，也是各家实现的
通用默认。环绕保证阈值图案按导出画布坐标周期采样时不出现接缝。

输出为 4096 个小端 uint16（秩 0..4095），阈值取 (rank + 0.5) / 4096。用秩而不是 8 位
归一化值：目标是 10-bit 码值域的无偏舍入，8 位阈值的量化台阶本身就接近一个目标码值。

运行（conda 环境 everythingdone）：

    python docs/features/fablesol-video-export/tools/generate_blue_noise.py
"""

from __future__ import annotations

import hashlib
import os
import struct

import numpy as np

SIZE = 64
COUNT = SIZE * SIZE
SIGMA = 1.5
SEED = 20260728
INITIAL_ONES = COUNT // 10

OUTPUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "..", "..", "..", "shared", "fablesol", "bluenoise64.bin",
)


def gaussian_kernel() -> np.ndarray:
    """环绕高斯核；半径取 3 sigma 后按 SIZE 折叠，等价于对整个环面卷积。"""
    axis = np.arange(SIZE)
    delta = np.minimum(axis, SIZE - axis)
    weights = np.exp(-(delta.astype(np.float64) ** 2) / (2.0 * SIGMA * SIGMA))
    kernel = np.outer(weights, weights)
    kernel[0, 0] = 0.0  # 样本不与自身相互作用
    return kernel


KERNEL = gaussian_kernel()
KERNEL_FFT = np.fft.fft2(KERNEL)


def filtered(pattern: np.ndarray) -> np.ndarray:
    """对二值图案做环绕卷积，得到"局部密度"。用 FFT，否则 4096 次全图卷积太慢。"""
    return np.real(np.fft.ifft2(np.fft.fft2(pattern.astype(np.float64)) * KERNEL_FFT))


def tightest_cluster(pattern: np.ndarray) -> tuple[int, int]:
    density = filtered(pattern)
    density[pattern == 0] = -np.inf
    return np.unravel_index(int(np.argmax(density)), density.shape)


def largest_void(pattern: np.ndarray) -> tuple[int, int]:
    density = filtered(pattern)
    density[pattern == 1] = np.inf
    return np.unravel_index(int(np.argmin(density)), density.shape)


def prototype() -> np.ndarray:
    rng = np.random.default_rng(SEED)
    pattern = np.zeros((SIZE, SIZE), dtype=np.uint8)
    flat = rng.choice(COUNT, size=INITIAL_ONES, replace=False)
    pattern.reshape(-1)[flat] = 1
    while True:
        cluster = tightest_cluster(pattern)
        pattern[cluster] = 0
        void = largest_void(pattern)
        if void == cluster:
            pattern[cluster] = 1
            return pattern
        pattern[void] = 1


def ranks() -> np.ndarray:
    pattern = prototype()
    result = np.zeros((SIZE, SIZE), dtype=np.int32)
    ones = int(pattern.sum())

    # 阶段一：逐个取走最紧的簇。
    working = pattern.copy()
    for rank in range(ones - 1, -1, -1):
        cluster = tightest_cluster(working)
        working[cluster] = 0
        result[cluster] = rank

    # 阶段二：从 prototype 出发逐个填最大的空洞，直到半数。
    working = pattern.copy()
    for rank in range(ones, COUNT // 2):
        void = largest_void(working)
        working[void] = 1
        result[void] = rank

    # 阶段三：在补集上继续——此时"空洞"要按 0 的分布找，所以对补集求最紧簇。
    for rank in range(COUNT // 2, COUNT):
        complement = 1 - working
        cluster = tightest_cluster(complement)
        working[cluster] = 1
        result[cluster] = rank

    return result


def main() -> None:
    table = ranks()
    assert sorted(table.reshape(-1).tolist()) == list(range(COUNT)), "秩必须是 0..4095 的置换"
    payload = b"".join(struct.pack("<H", int(v)) for v in table.reshape(-1))
    with open(os.path.normpath(OUTPUT), "wb") as handle:
        handle.write(payload)
    print("wrote", os.path.normpath(OUTPUT), len(payload), "bytes")
    print("sha256", hashlib.sha256(payload).hexdigest())


if __name__ == "__main__":
    main()
