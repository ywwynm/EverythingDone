from pathlib import Path
import json,hashlib
root=Path(__file__).resolve().parents[1]
repo=next(p for p in root.parents if (p/'gradlew.bat').is_file())
version='r33'
model=json.loads((root/'analysis/model-qa.json').read_text('utf-8'))
video=json.loads((root/'analysis/video-qa.json').read_text('utf-8'))
trajectory=json.loads((root/'analysis/trajectory-qa.json').read_text('utf-8'))
assert model['version']==video['version']==trajectory['version']==version
assert model['code_hash']==video['code_hash']==trajectory['code_hash']
assets=repo/'shared/particle-dismiss';meta=json.loads((assets/'model.json').read_text('utf-8'))
for name,digest in meta['files'].items():assert hashlib.sha256((assets/name).read_bytes()).hexdigest()==digest,name
size=sum(p.stat().st_size for p in (root/'videos').glob('*.mp4'))/2**20
qa={
    'version':version,'checked_local_date':'2026-09-10','code_hash':model['code_hash'],
    'surface':'Codex 浏览器，CUA 可访问性状态及实际截图',
    'header_version':'R33','video_count':54,
    'selection':'切换筛选后点击相应视频行，再核对标题、文件名和时长；不以 URL hash 单独推断选中状态。',
    'playback':[
        {'file':'thanos-compare-phase-0.5x.mp4','observed_time_seconds':3.006,'duration_seconds':3.7},
        {'file':'attachment-eight-directions-1x.mp4','observed_time_seconds':1.070,'duration_seconds':1.85},
        {'file':'attachment-eight-directions-0.5x.mp4','observed_time_seconds':3.004,'duration_seconds':3.7}],
    'frame_step':{'file':'thanos-compare-phase-0.5x.mp4','before_seconds':1.756,'after_seconds':1.773,'nominal_step':1/60},
    'paused_review':[
        {'file':'thanos-compare-phase-0.5x.mp4','time_seconds':1.756,'observation':'整体释放范围与主向运动相近，参考下半部细束更集中，候选仍较分散。'},
        {'file':'attachment-eight-directions-0.5x.mp4','time_seconds':1.437,'observation':'八个方向均有多处局部释放；白色面板通过细粒错时交接，未出现整条统一切断线。'}],
    'desktop_preview':'viewer.py --capture 通过',
    'limitations':'单个截图不证明动态审美或显示帧率；结合七场景阶段图、视频和连续轨迹检查做本轮判断。'
}
(root/'analysis/ui-qa-r33.json').write_text(json.dumps(qa,ensure_ascii=False,indent=2),encoding='utf-8')
path=root/'README.md';s=path.read_text('utf-8')
first=s.index('当前视频与预览使用');last=s.index('\n\n## 先看结果',first)
s=s[:first]+'当前桌面视频与预览使用 **r33**，已移植 Android 的 dialog 消失路径并完成双设备测试。r33 保留钢铁侠的运动改善，适度细化灭霸、科比的卷束以及白色微片明暗；自有弹窗使用同一连续速度积分和综合流场。沿用 107 来源研究和九段参考分析，局部细束仍有差异。最终过程见 `docs/features/dialog-particle-dismiss/tuning-2026-09-10-desktop-android.md`。'+s[last:]
s=s.replace('共约 423.74 MiB',f'共约 {size:.2f} MiB')
s=s.replace('`analysis/r31-ironman.jpg`、`r31-thanos.jpg`、`r31-kobe.jpg`：本轮模型的形态对照图集。','`analysis/all-scenes-final.jpg`：当前七场景的分阶段检查；历史版本图像在归档中保留。')
s=s.replace('`analysis/r31-ui-attachment.jpg`、`r31-ui-attachment-image.jpg`、`r31-ui-color.jpg`、`r31-ui-language.jpg`：自有弹窗与 r30 的阶段对照。','`analysis/r33-ui-attachment.jpg`、`r33-ui-attachment-image.jpg`、`r33-ui-color.jpg`、`r33-ui-language.jpg`：自有弹窗与 r31 的阶段对照。')
s=s.replace('华为／r30／r31 三栏，自有弹窗为 r30／r31 两栏','华为／r31／r33 三栏，自有弹窗为 r31／r33 两栏')
s=s.replace('`ui-qa-r31.json`','`ui-qa-r33.json`').replace('`analysis/r31-reference-metrics.json`','`analysis/r33-reference-metrics.json`')
s=s.replace('`archive/r30/`：上一版','`archive/r31/`：本轮比较基准版')
s=s.replace('长期中文文档在 `docs/features/dialog-particle-dismiss/`。本轮未修改 Android、未使用 ADB，也未创建 Git 提交。','长期中文文档在 `docs/features/dialog-particle-dismiss/`。Android 运行资源在仓库 `shared/particle-dismiss/`，真实 GPU 输入及输出在 `android-fixtures/`、`device-r33/`。三星有系统录屏；OPD2515 的系统录屏输出受设备限制，保留真实阶段截图。测试只操作用户指定的两台设备，未创建 Git 提交。')
path.write_text(s,encoding='utf-8')
print(json.dumps({'videoMiB':size,'videoCount':video['count'],'assetHashesVerified':True}))
