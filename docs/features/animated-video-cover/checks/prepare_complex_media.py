"""生成可重复的 720p 测试视频；只写入被忽略的 tmp，不加入正式 APK。"""
from pathlib import Path
import subprocess

root = next(p for p in Path(__file__).resolve().parents if (p / "gradlew.bat").exists())
target = root / "tmp/selection-transition-20261013/complex-assets/complex-drag/source.mp4"
target.parent.mkdir(parents=True, exist_ok=True)
subprocess.run([
    "C:/ffmpeg/bin/ffmpeg.exe", "-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=24", "-t", "3",
    "-c:v", "libx264", "-preset", "ultrafast", "-crf", "28", "-pix_fmt", "yuv420p", str(target),
], check=True)
print(target)
