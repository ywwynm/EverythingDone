from pathlib import Path
p=Path(__file__).resolve().parents[1]/'build_gallery.py'
s=p.read_text(encoding='utf-8')
s=s.replace('三栏按相同进度比较。中栏使用上一版归档的原始渲染帧，右栏为本轮。华为参考区间映射为 1 秒。','按相同进度比较：上一版 r30、本轮 r31。照片序列另有华为参考列（映射为 1 秒）；自有弹窗为新旧两栏。')
s=s.replace('video.ontimeupdate=()=>{','const updateClock=()=>{')
s=s.replace("document.addEventListener('keydown'","video.ontimeupdate=updateClock;video.onloadedmetadata=updateClock;video.ondurationchange=updateClock;\ndocument.addEventListener('keydown'")
s=s.replace("v.file==='ironman-compare-versions-0.5x.mp4'","v.file==='attachment-compare-versions-0.5x.mp4'")
p.write_text(s,encoding='utf-8')
