"""离线比较返回曲线；输入为上一轮已保存的三机轨迹，不连接设备。"""

import hashlib
import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from matplotlib.font_manager import FontProperties

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
REPORTS = {
    "samsung": "tmp/media-dismiss-inertia-samsung-final-fixtures/result.json",
    "oneplus": "tmp/media-dismiss-inertia-oneplus-confirmed-fixtures/result.json",
    "meizu": "tmp/media-dismiss-inertia-meizu-final-fixtures/result.json",
}
T = np.arange(0, 0.90001, 0.0001)


def hermite(t, start, target, velocity, duration):
    s = np.clip(t / duration, 0, 1)
    value = start + (target - start) * (3 * s**2 - 2 * s**3)
    value += velocity * duration * s * (1 - s)**2
    speed = (target - start) * (6 * s - 6 * s**2) / duration
    speed += velocity * (1 - 4 * s + 3 * s**2)
    return value, speed


def critical_spring(t, start, target, velocity, omega):
    delta = start - target
    b = velocity + omega * delta
    decay = np.exp(-omega * t)
    return target + (delta + b * t) * decay, (velocity - omega * b * t) * decay


def summarize(values, speeds, start, target):
    # 本轮输入都是向下快速甩动；正方向是继续向外。
    assert start > target
    peak = int(np.argmax(values))
    distance = abs(start - target)

    def stable_time(limit):
        outside = np.flatnonzero(abs(values - target) > limit)
        index = int(outside[-1] + 1) if len(outside) else 0
        return None if index >= len(T) else round(float(T[index] * 1000), 1)

    return {
        "outwardPx": round(float(values[peak] - start), 3),
        "turnMs": round(float(T[peak] * 1000), 1),
        "within5PercentMs": stable_time(distance * .05),
        "within1PercentMs": stable_time(distance * .01),
        "within2PxMs": stable_time(2),
        "initialVelocityPxPerSec": round(float(speeds[0]), 3),
    }


results = []
plot_data = None
for device, relative in REPORTS.items():
    path = ROOT / relative
    raw = path.read_bytes()
    report = json.loads(raw.decode("utf-8-sig"))
    frames = [f for f in report["interactiveFrames"] if f["name"] == "dismiss-fast-down-return"]
    start, target = frames[0]["y"], frames[-1]["y"]
    p = np.array([f["progress"] for f in frames])
    measured = np.array([f["y"] for f in frames])
    baseline = start + (target - start) * (3 * p**2 - 2 * p**3)
    coefficient = .432 * p * (1 - p)**2
    # 从整条已知 Hermite 曲线最小二乘反推速度；避免单帧浮点舍入误差。
    velocity = float(np.dot(coefficient, measured - baseline) / np.dot(coefficient, coefficient))
    max_error = float(np.max(abs(baseline + velocity * coefficient - measured)))
    assert max_error < .01, (device, max_error)
    curves = {
        "hermite432": hermite(T, start, target, velocity, .432),
        "hermite300": hermite(T, start, target, velocity, .300),
        "springOmega20": critical_spring(T, start, target, velocity, 20),
        "springOmega24": critical_spring(T, start, target, velocity, 24),
    }
    results.append({
        "device": device,
        "input": relative,
        "inputSha256": hashlib.sha256(raw).hexdigest(),
        "startY": start,
        "targetY": target,
        "inferredVelocityPxPerSec": velocity,
        "maxReconstructionErrorPx": max_error,
        "recordedOutwardPx": max(measured) - start,
        "models": {name: summarize(*curve, start, target) for name, curve in curves.items()},
    })
    if device == "oneplus":
        plot_data = (start, target, curves, p * .432 * 1000, measured)

output = {
    "date": "2026-10-22",
    "kind": "离线单轴比较，候选曲线尚未装机验证",
    "sampleIntervalMs": .1,
    "scope": "使用同一起点、终点及反推的松手速度；不包含缩放、裁剪、窗口交接或刷新率",
    "metrics": "误差占松手到目标的初始距离；仅位置阈值，不能视作速度已归零或窗口完成",
    "springParameters": "质量归一为 1，阻尼比为 1；omega=20/24 对应刚度 400/576",
    "devices": results,
}
(OUT / "return-curve-comparison-2026-10-22.json").write_text(
    json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
)

font = FontProperties(fname="C:/Windows/Fonts/msyh.ttc")
plt.rcParams.update({"font.family": font.get_name(), "axes.unicode_minus": False, "font.size": 10})
fig, axes = plt.subplots(1, 2, figsize=(12.8, 4.6), layout="constrained")
start, target, curves, measured_time, measured = plot_data
for key, label, color in [
    ("hermite432", "当前曲线 · 432 ms", "#c4573c"),
    ("hermite300", "只缩短时长 · 300 ms", "#3575b9"),
    ("springOmega24", "临界阻尼候选 · ω=24", "#168570"),
]:
    values, speeds = curves[key]
    axes[0].plot(T * 1000, values - target, label=label, color=color, linewidth=2.3)
    axes[1].plot(T * 1000, speeds / 1000, color=color, linewidth=2.3)
axes[0].scatter(measured_time, measured - target, s=12, color="#c4573c", alpha=.55, label="上一轮一加实测帧")
axes[0].axhline(start - target, color="#8c9299", linestyle="--", linewidth=.9)
axes[0].text(465, start - target + 40, "松手位置", ha="right", color="#646b73")
axes[0].set_ylabel("距详情目标中心的纵向距离（px）")
axes[1].set_ylabel("纵向速度（千 px/秒；正值向外）")
axes[0].set_title("候选曲线更早折返、减少外移")
axes[1].set_title("三条曲线均接续相同的松手速度")
axes[0].legend(loc="upper right", fontsize=9)
for ax in axes:
    ax.axhline(0, color="#8c9299", linewidth=.9)
    ax.set_xlim(0, 480)
    ax.set_xlabel("松手后的时间（毫秒）")
    ax.grid(alpha=.15)
    ax.spines[["top", "right"]].set_visible(False)
fig.suptitle("返回动画曲线比较：单轴离线计算，候选效果尚未实机验证", fontsize=14)
fig.savefig(OUT / "return-curve-comparison-2026-10-22.png", dpi=160)
plt.close(fig)
print(json.dumps(results, ensure_ascii=False, indent=2))
