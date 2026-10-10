"""历史诊断脚本：在迁移阶段使用同一性能探针构建当时的 HEAD（35aabcf68）。

仅归档原测量方法。当前分阶段预览代码已增加依赖，且提交后的 HEAD 不再是旧版，
不能直接用本脚本重建原基线；复测应在隔离的旧版检出中适配对应探针。
原执行通过 finally 按原字节恢复候选源码。
"""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[4]
OUTPUT = ROOT / "tmp/detail-checklist-diagnosis/migration"
MAIN = "app/src/main/"
tracked = [MAIN + path for path in [
    "java/com/ywwynm/everythingdone/activities/DetailActivity.kt",
    "java/com/ywwynm/everythingdone/activities/DoingActivity.kt",
    "java/com/ywwynm/everythingdone/fragments/AddAttachmentDialogFragment.kt",
    *[f"java/com/ywwynm/everythingdone/adapters/{name}.kt" for name in
      ("CheckListAdapter", "ImageAttachmentAdapter", "AudioAttachmentAdapter")],
    "java/com/ywwynm/everythingdone/helpers/DetailAttachmentPlaybackController.kt",
    "java/com/ywwynm/everythingdone/helpers/ScreenshotHelper.kt",
    "java/com/ywwynm/everythingdone/utils/UriPathConverter.kt",
    "java/com/ywwynm/everythingdone/model/DetailAutoplayMode.kt", "res/layout/activity_detail.xml",
    *[f"res/{locale}/strings.xml" for locale in ("values", "values-zh-rCN", "values-zh-rHK", "values-zh-rTW")]
]]
temporary = [ROOT / MAIN / f"java/com/ywwynm/everythingdone/helpers/{name}.kt" for name in
             ("ChecklistEditorItems", "DetailContentLayout", "DetailGridGeometry", "DetailScreenshotRenderer")]
temporary += [path for path in (ROOT / "app/src/debug/java/com/ywwynm/everythingdone/detail").glob("*.kt")
              if path.name not in ("DetailPerformanceProbe.kt", "DetailRecreationProbe.kt", "DetailProcessProbeActivity.kt")]
files = [ROOT / path for path in tracked] + temporary
assert all(path.resolve().is_relative_to(ROOT.resolve()) and path.is_file() for path in files)
backup = {path: path.read_bytes() for path in files}
for path, data in backup.items():
    copy = OUTPUT / "perf-source-candidate" / path.relative_to(ROOT)
    copy.parent.mkdir(parents=True, exist_ok=True)
    copy.write_bytes(data)
try:
    for path in tracked:
        (ROOT / path).write_bytes(subprocess.check_output(["git", "show", f"HEAD:{path}"], cwd=ROOT))
    for path in temporary:
        path.unlink()
    with (OUTPUT / "baseline-perf-build.log").open("wb") as log:
        subprocess.run(["cmd", "/c", "gradlew.bat", ":app:assembleDebug", "--console=plain"], cwd=ROOT,
                       stdout=log, stderr=subprocess.STDOUT, check=True)
    (OUTPUT / "baseline-perf.apk").write_bytes((ROOT / "app/build/outputs/apk/debug/app-debug.apk").read_bytes())
finally:
    for path, data in backup.items():
        path.write_bytes(data)
    assert all(path.read_bytes() == data for path, data in backup.items())
print("性能基线 APK 已保存；候选源码已逐字恢复。")
