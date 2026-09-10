from pathlib import Path
import re,hashlib,json
ROOT=next(p for p in Path(__file__).resolve().parents if (p/'gradlew.bat').is_file())
p=ROOT/'app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleDismissRenderer.kt'
s=p.read_text('utf-8')
names=['VERTEX_SHADER','FRAGMENT_SHADER','STILL_VERTEX_SHADER','STILL_FRAGMENT_SHADER']
def shaders(source):return {n:re.search(r'private val '+n+r' = """(.*?)"""\.trimIndent\(\)',source,re.S).group(1) for n in names}
before=shaders(s)
a=s.index('    /** 返回 true 表示动画完整播完');b=s.index('    private fun renderAnimation()',a);s=s[:a]+s[b:]
a=s.index('        /**\n         * 粒子层与静止层共享同一张运行时释放场。');s=s[:a]+'    }\n}\n'
a=s.index('        /**\n         * 消散每 cell 只画一个粒子。');b=s.index('        /** 凝聚路径的波前扩散时长',a);s=s[:a]+s[b:]
a=s.index('        val isCondense = spec.condenseFromT != null');s=s[:a]+s[a:].replace('        val isCondense = spec.condenseFromT != null\n','',1)
a=s.index('        // 桌面 A 固定使用 144 列');b=s.index('        val cols =',a)
s=s[:a]+'''        // 出现路径保留原有点粒子与完整静止层。
        val cellPx = spec.cellPx
        val program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        val stillProgram = createProgram(STILL_VERTEX_SHADER, STILL_FRAGMENT_SHADER)
'''+s[b:]
a=s.index('        // 出现动画的静止层仍是一张平的四边形');b=s.index('        val texture =',a)
s=s[:a]+'''        val stillVertices = 4
        val stillPrimitive = GLES30.GL_TRIANGLE_STRIP
        val particleCount = cols * rows
        val replicas = REPLICAS

'''+s[b:]
a=s.index('        if (!isCondense) {');b=s.index('        val scale = spec.durationScale',a);s=s[:a]+s[b:]
s=s.replace('val condenseFrom = spec.condenseFromT','val condenseFrom = checkNotNull(spec.condenseFromT)')
a=s.index('            val clampedT: Float');b=s.index('            GLES30.glClear(',a)
s=s[:a]+'''            val progress = min(elapsed / spec.condenseDurationS, 1f)
            val clampedT = condenseFrom * (1f - progress)
            val lastFrame = progress >= 1f
'''+s[b:]
s=s.replace('        // 揭开的宏观时序已由 DISMISS_FIELD_GLSL 的释放核决定，消散不再需要\n        // 单向扫描项；凝聚路径本来就传 0。uSweepDir 仍提供飞行方向。','        // 保留出现路径的既有参数。')
s=s.replace('            // 消散的第 0 区间是基础材料采样，后三个区间按同一轨迹补充\n            // 彩色内容、当前释放锋线和真实折叠脊；凝聚仍保留既有三副本。','            // 凝聚保留既有三副本。')
assert shaders(s)==before,'不得改变出现着色器'
assert 'SHEET_' not in s and 'DISMISS_' not in s
p.write_text(s,encoding='utf-8')
hashes={n:hashlib.sha256('\n'.join(line.strip() for line in v.strip().splitlines()).encode()).hexdigest() for n,v in before.items()}
(Path(__file__).parent/'condense-preserved.json').write_text(json.dumps(hashes,indent=2),encoding='utf-8')
print('删除旧消失路径，保留四段出现着色器',hashes)
