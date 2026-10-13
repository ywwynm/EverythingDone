from pathlib import Path
import shutil

root = next(p for p in Path(__file__).resolve().parents if (p / 'gradlew.bat').is_file())
dest = root / 'tmp/selection-transition-20261013'
dest.mkdir(parents=True, exist_ok=True)
for name in ('main', 'debug'):
    source = root / f'app/src/{name}/AndroidManifest.xml'
    data = source.read_text(encoding='utf-8')
    data = data.replace('android:name=".', 'android:name="com.ywwynm.everythingdone.')
    data = data.replace('android:authorities="com.ywwynm.everythingdone"', 'android:authorities="${applicationId}"')
    data = data.replace('android:targetPackage="com.ywwynm.everythingdone"', 'android:targetPackage="${applicationId}"')
    (dest / f'{name}-manifest.xml').write_text(data, encoding='utf-8')
files = ['activities/ThingsActivity.kt', 'managers/ModeManager.kt', 'adapters/ThingsAdapter.kt',
         'adapters/BaseThingsAdapter.kt', 'views/ActivityHeader.kt',
         'views/particledismiss/ThingListRemovalAnimator.kt', 'views/particledismiss/ParticleDismissOverlay.kt']
for file in files:
    target = dest / 'before-source' / file
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(root / 'app/src/main/java/com/ywwynm/everythingdone' / file, target)
