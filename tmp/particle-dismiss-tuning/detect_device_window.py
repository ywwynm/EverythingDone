# -*- coding: utf-8 -*-
"""从真机录屏里自动定位消散动画的时间窗，并写回 device-recordings/latest.json。

判据：在对话框裁剪区内的逐帧变化幅度。静止期接近 0，动画期显著抬升。
取「首次越过阈值」到「最后一次越过阈值」为窗口，两端各留一帧余量。

时间必须来自**每帧的真实时间戳**，不能用帧号除以帧率：`adb shell screenrecord`
输出的是变帧率流，容器里的 `avg_frame_rate` 与 `nb_frames` 都对不上实际解码出的
帧序列（2026-08-31 实测：7.2 秒的录像被算成 17-24 秒的窗口）。
"""
import json
import subprocess
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
RAW = HERE / "device-recordings" / "r5-dismiss-raw.mp4"
META = HERE / "device-recordings" / "latest.json"
CROP = tuple(__import__('json').load(__import__('io').open(str(HERE / 'device-recordings' / 'latest.json'), encoding='utf-8'))['crop']) if (HERE / 'device-recordings' / 'latest.json').exists() else (1440, 1760, 0, 760)
WIDTH = 160


def timestamps():
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "frame=best_effort_timestamp_time",
         "-of", "csv=p=0", str(RAW)],
        capture_output=True, check=True, text=True).stdout
    return np.array([float(line) for line in out.split() if line and line[0].isdigit()])


def frames(count):
    height = int(round(WIDTH * CROP[1] / CROP[0]))
    height += height % 2
    out = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(RAW),
         "-vf", "crop=%d:%d:%d:%d,scale=%d:%d,format=gray" % (CROP + (WIDTH, height)),
         "-vsync", "0", "-f", "rawvideo", "-pix_fmt", "gray", "-"],
        capture_output=True, check=True).stdout
    n = len(out) // (WIDTH * height)
    if n != count:
        raise SystemExit("解码帧数 %d 与时间戳数 %d 不一致" % (n, count))
    return np.frombuffer(out, np.uint8).reshape(n, height, WIDTH).astype(np.float32)


def main():
    ts = timestamps()
    f = frames(len(ts))
    diff = np.abs(np.diff(f, axis=0)).mean(axis=(1, 2))
    base = float(np.percentile(diff, 20))
    thr = max(base + 0.02 * (float(diff.max()) - base), 0.35)
    hot = np.flatnonzero(diff > thr)
    if hot.size == 0:
        raise SystemExit("没有检测到动画段")
    i0, i1 = int(hot[0]), int(hot[-1])
    start = max(0.0, float(ts[max(i0 - 1, 0)]))
    end = float(ts[min(i1 + 2, len(ts) - 1)])
    print("解码 %d 帧，跨度 %.3f 秒（平均 %.1f fps，变帧率）"
          % (len(ts), ts[-1] - ts[0], (len(ts) - 1) / max(ts[-1] - ts[0], 1e-6)))
    print("逐帧变化：基线 %.3f 阈值 %.3f 峰值 %.3f" % (base, thr, float(diff.max())))
    print("动画窗口：%.3f - %.3f 秒（%d 帧，时长 %.3f 秒）"
          % (start, end, i1 - i0 + 1, end - start))
    meta = json.loads(META.read_text(encoding="utf-8")) if META.exists() else {}
    meta.update({
        "device": "SM-S9180 (R5CW20BLNKL)",
        "source": "device-recordings/r5-dismiss-raw.mp4",
        # 录制日期取录屏文件的修改时间——手写的话每轮都会忘记更新，
        # latest.json 里就会留着一个早就过期的日期（2026-09-01 实际发生过）。
        "recorded": __import__("datetime").date.fromtimestamp(
            RAW.stat().st_mtime).isoformat(),
        "start": round(start, 3),
        "end": round(end, 3),
        "crop": list(CROP),
        "note": "真实『添加附件』Dialog，左上外部触点 (60,700) 关闭。"
                "窗口由逐帧变化检测得到，时间取自每帧真实时间戳（录屏为变帧率）。",
    })
    META.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    print("已写回", META)


if __name__ == "__main__":
    sys.exit(main())
