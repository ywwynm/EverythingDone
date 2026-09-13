"""导出实机 GPU 的逆向验证帧；要求已通过对应正向帧的 RGBA 散列比较。"""
import argparse
import io
import json
from pathlib import Path
import subprocess
import tarfile

from PIL import Image

p = argparse.ArgumentParser()
p.add_argument("serial", choices=["9018f404", "R5CW20BLNKL"])
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
out = root / "analysis/reverse-appearance"
frames = out / "frames"
frames.mkdir(parents=True, exist_ok=True)
videos = root / "videos"
scenes = dict(ironman="钢铁侠", attachment="添加附件", color="调整颜色")
adb = ["E:/AndroidSDK/platform-tools/adb.exe", "-s", a.serial]
remote = "/sdcard/Android/data/com.ywwynm.everythingdone/files/particle-unified/generated"
reports = {scene: json.loads(subprocess.check_output(adb + ["shell", "cat", f"{remote}/{scene}.json"], timeout=15))
           for scene in scenes}
for report in reports.values():
    assert report["reverseVerified"] and report["reverseFramesSaved"]
    assert len(report["reverseFrameParity"]) == report["reverseSamples"] + 1
files = [f"{scene}-reverse-{i}.png" for scene in scenes for i in range(reports[scene]["reverseSamples"] + 1)] + [f"{scene}.json" for scene in scenes]
data = subprocess.check_output(adb + ["exec-out", "tar", "-C", remote, "-cf", "-", *files], timeout=60)
with tarfile.open(fileobj=io.BytesIO(data)) as archive:
    assert {m.name for m in archive.getmembers()} == set(files)
    for member in archive.getmembers():
        assert member.isfile() and member.name in files
        target = out / member.name if member.name.endswith(".json") else frames / member.name
        target.write_bytes(archive.extractfile(member).read())

items = []
for scene, title in scenes.items():
    report = json.loads((out / f"{scene}.json").read_text("utf-8"))
    samples = report["reverseSamples"]
    assert report["reverseVerified"] and report["reverseFramesSaved"] and len(report["reverseFrameParity"]) == samples + 1
    assert samples == round(report["appearanceSeconds"] * 60)
    angle = report["direction"] % 360
    assert 225 <= angle <= 315
    direction_title = "左下方" if angle < 255 else "右下方" if angle > 285 else "下方"
    asset = root / "assets" / scene
    config = json.loads((asset / "scene.json").read_text("utf-8"))
    size = (report["width"], report["height"])
    background = Image.open(asset / "background.png").convert("RGBA").resize(size)
    dim = config.get("dim_alpha", 0)
    if dim:
        background = Image.alpha_composite(background, Image.new("RGBA", size, (0, 0, 0, round(255 * dim))))
    for speed, repeats in [("1x", 1), ("0.5x", 2)]:
        name = f"{scene}-reverse-appearance-{speed}.mp4"
        proc = subprocess.Popen([
            "C:/ffmpeg/bin/ffmpeg.exe", "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
            "-s", f"{size[0]}x{size[1]}", "-r", "60", "-i", "-", "-an", "-c:v", "libx264",
            "-preset", "fast", "-crf", "17", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(videos / name)
        ], stdin=subprocess.PIPE)
        try:
            # 起止停留仍为 0.35 / 0.5 秒，中段按真机 0.6 秒播放完整逆向轨迹。
            for i in [samples] * 21 + list(range(samples - 1, -1, -1)) + [0] * 30:
                layer = Image.open(frames / f"{scene}-reverse-{i}.png").convert("RGBA")
                raw = Image.alpha_composite(background, layer).convert("RGB").tobytes()
                for _ in range(repeats):
                    proc.stdin.write(raw)
        finally:
            proc.stdin.close()
        assert proc.wait() == 0
        items.append(dict(name=name, label=f"{title} · 从{direction_title}出现 · {'1' if speed == '1x' else '0.5'} 倍速"))
        print(name, flush=True)

html = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>从下方进入的粒子出现动画</title>
<style>body{background:#0e151e;color:#edf1f7;font:16px/1.6 system-ui;margin:24px}h1{font-size:25px}
video{display:block;height:75vh;max-width:100%;margin:16px auto;background:#080c11}
button,select{padding:8px 12px;background:#223247;border:1px solid #59718e;border-radius:6px;color:inherit;margin-right:8px}a{color:#80dce4}
</style><h1>从下方进入的粒子出现动画</h1>
<p>画面来自 Android GPU：钢铁侠从左下、添加附件从下方、调整颜色从右下进入。真机每次在下方 90° 扇区内随机选择位置，用 0.6 秒完整倒播共同消散模型。视频不包含准备等待。</p>
<select id="choice"></select><button id="toggle">播放 / 暂停</button><button id="restart">从头播放</button>
<a id="save" download>保存当前视频</a><video id="video" controls loop muted playsinline></video>
<p>三组素材的 37 个逆向采样均与同一模型的对应正向帧逐像素相等。颜色弹窗被遮挡的背景沿用此前的重建素材。</p>
<script>const items=ITEMS;const c=document.getElementById('choice'),v=document.getElementById('video');
for(const x of items)c.add(new Option(x.label,x.name));
function load(){v.src=c.value+'?v=faster-stable-layout';document.getElementById('save').href=c.value;location.hash=c.value;v.play().catch(()=>{});}
c.onchange=load;document.getElementById('toggle').onclick=()=>v.paused?v.play():v.pause();
document.getElementById('restart').onclick=()=>{v.currentTime=0;v.play();};
const selected=decodeURIComponent(location.hash.slice(1));if(items.some(x=>x.name===selected))c.value=selected;load();
</script></html>'''.replace("ITEMS", json.dumps(items, ensure_ascii=False))
(videos / "reverse-appearance.html").write_text(html, "utf-8")
