"""生成独立测试包的 Manifest 与 Gradle init 脚本，不修改正式包配置。"""
import re
from pathlib import Path

root = Path(__file__).resolve().parents[4]
out = root / 'tmp/default-directions-20261019'
out.mkdir(parents=True, exist_ok=True)
namespace = 'com.ywwynm.everythingdone'
package = namespace + '.directionvalidation'
for source in ('main', 'debug'):
    text = (root / f'app/src/{source}/AndroidManifest.xml').read_text(encoding='utf-8')
    text = text.replace('android:name=".', f'android:name="{namespace}.')
    for attribute in ('targetPackage', 'authorities', 'taskAffinity'):
        text = re.sub(rf'android:{attribute}="{re.escape(namespace)}([^\"]*)"',
                      lambda m: f'android:{attribute}="{package}{m[1]}"', text)
    (out / f'{source}-manifest.xml').write_text(text, encoding='utf-8')
(out / 'clone.init.gradle').write_text('''gradle.beforeProject { project ->
    if (project.path == ':app') {
        project.plugins.withId('com.android.application') {
            project.extensions.getByName('androidComponents').finalizeDsl { android ->
                android.defaultConfig.applicationId = 'com.ywwynm.everythingdone.directionvalidation'
                android.sourceSets.main.manifest.srcFile(project.rootProject.file('tmp/default-directions-20261019/main-manifest.xml'))
                android.sourceSets.debug.manifest.srcFile(project.rootProject.file('tmp/default-directions-20261019/debug-manifest.xml'))
            }
        }
    }
}
''', encoding='utf-8')
