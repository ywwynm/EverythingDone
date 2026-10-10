"""临时隔离一种首屏后台预热；APK 留作诊断，App.kt 在构建后逐字恢复。"""
import argparse
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[4]
parser = argparse.ArgumentParser()
parser.add_argument("kind", choices=("timely", "particles"))
args = parser.parse_args()
path = root / "app/src/main/java/com/ywwynm/everythingdone/App.kt"
original = path.read_bytes()
source = original.decode("utf-8")
snippet = ('''TimelyClockView.prewarmStyle(this@App,
                            getSharedPreferences(Def.Meta.PREFERENCES_NAME, 0)
                                .getString(Def.Meta.KEY_DOING_DIGIT_STYLE, "poppins"))'''
           if args.kind == "timely" else "ParticleDismissController.warmDismissModel(this@App)")
snippet = snippet.replace("\n", "\r\n") if "\r\n" in source else snippet
assert source.count(snippet) == 1
out = root / "tmp/detail-checklist-diagnosis/migration"
try:
    path.write_bytes(source.replace(snippet, "Unit // 临时隔离首屏预热，仅用于归因").encode("utf-8"))
    with (out / f"no-{args.kind}-build.log").open("wb") as log:
        subprocess.run(["cmd", "/c", "gradlew.bat", ":app:assembleDebug", "--console=plain"], cwd=root,
                       stdout=log, stderr=subprocess.STDOUT, check=True)
    (out / f"candidate-no-{args.kind}.apk").write_bytes((root / "app/build/outputs/apk/debug/app-debug.apk").read_bytes())
finally:
    path.write_bytes(original)
    assert path.read_bytes() == original
print(f"隔离 {args.kind} 的 APK 已保存；App.kt 已逐字恢复。")
