"""迁移共同坐标变换与释放采样，保留现有材料和窗口退出路径。"""
from pathlib import Path
ROOT=next(p for p in Path(__file__).resolve().parents if (p/'gradlew.bat').is_file())
base=ROOT/'app/src/main/java/com/ywwynm/everythingdone/views/particledismiss'
p=base/'ParticleMicroflakeModel.kt';s=p.read_text('utf-8')
s=s.replace('val release = releaseField(nx, ny, directionDegrees, rules) { name, value -> metrics[name] = value }','val release = releaseField(nx, ny, directionDegrees, rules, width, height) { name, value -> metrics[name] = value }')
start=s.index('    /** 三个起点');end=s.index('    /** 与桌面逐位一致',start)
s=s[:start]+'''    /** 两个场共用矩形归一化方向，避免长弹窗的速度与释放范围错位。 */
    fun fieldRotation(directionDegrees: Float, width: Float, height: Float): Double {
        val angle = Math.toRadians(directionDegrees.toDouble())
        return atan2(sin(angle) / height, cos(angle) / width) - PI / 2
    }

    fun releaseField(
        nx: Int, ny: Int, directionDegrees: Float, rules: ParticleMicroflakeRules,
        width: Float = nx.toFloat(), height: Float = ny.toFloat(),
        stage: ((String, Double) -> Unit)? = null
    ): FloatArray {
        val started = System.nanoTime()
        val angle = fieldRotation(directionDegrees, width, height)
        val c = cos(angle); val s = sin(angle)
        val gridWidth = rules.number("release_width").toInt()
        val gridHeight = rules.number("release_height").toInt()
        val low = rules.decimal("field_min"); val size = rules.decimal("field_size")
        val result = FloatArray(nx * ny)
        for (y in 0 until ny) for (x in 0 until nx) {
            val qx = (x + .5) / nx - .5; val qy = (y + .5) / ny - .5
            val u = ((.5 + c * qx - s * qy - low) / size * gridWidth - .5).coerceIn(0.0, (gridWidth - 1).toDouble())
            val v = ((.5 + s * qx + c * qy - low) / size * gridHeight - .5).coerceIn(0.0, (gridHeight - 1).toDouble())
            val ix = u.toInt(); val iy = v.toInt()
            val ax = u - ix; val ay = v - iy
            val nextX = min(ix + 1, gridWidth - 1); val nextY = min(iy + 1, gridHeight - 1)
            val a = rules.release[iy * gridWidth + ix].toDouble() * (1 - ax) + rules.release[iy * gridWidth + nextX] * ax
            val b = rules.release[nextY * gridWidth + ix].toDouble() * (1 - ax) + rules.release[nextY * gridWidth + nextX] * ax
            result[y * nx + x] = (a * (1 - ay) + b * ay).toFloat()
        }
        stage?.invoke("fieldMs", (System.nanoTime() - started) / 1e6)
        return result
    }

'''+s[end:]
start=s.index('    private fun smoothMin(');end=s.index('    private fun smooth(value:',start)
s=s[:start]+s[end:];p.write_text(s,'utf-8',newline='\n')
files=[base/'ParticleDismissController.kt',base/'ParticleMicroflakeRenderer.kt',ROOT/'app/src/debug/java/com/ywwynm/everythingdone/views/particledismiss/ParticleMicroflakeProbeReceiver.kt']
for p in files:
    s=p.read_text('utf-8')
    import re
    s=re.sub(r'ParticleMicroflakeRules.read\(([^\n]*?)\.open\("particle-dismiss/rules.properties"\)\)',lambda m:f'ParticleMicroflakeRules.read({m[1]}.open("particle-dismiss/rules.properties"), {m[1]}.open("particle-dismiss/common-release.f32"))',s)
    if p.name=='ParticleMicroflakeRenderer.kt':
        s=s.replace('GLES30.GL_RG16F, 36, 36, 32, 0,','GLES30.GL_RG16F, input.rules.number("flow_width").toInt(), input.rules.number("flow_height").toInt(), input.rules.number("flow_time").toInt(), 0,')
        s=s.replace('Math.toRadians((input.direction - input.rules.number("guide_direction")).toDouble())','ParticleMicroflakeModel.fieldRotation(input.direction, input.cardWidth, input.cardHeight)')
        s=s.replace('one(material, "light_gain", input.rules.number("light_gain")); one(material, "body_weight", input.materials.bodyWeight)','one(material, "light_gain", input.rules.number("light_gain")); one(material, "body_weight", input.materials.bodyWeight)\n        one(material, "panel_weight", input.materials.statistics.getValue("panelWeight").toFloat())')
    p.write_text(s,'utf-8',newline='\n')
p=ROOT/'app/src/test/java/com/ywwynm/everythingdone/views/particledismiss/ParticleMicroflakeModelTest.kt';s=p.read_text('utf-8')
s=s.replace('ParticleMicroflakeRules.read(File(root, "shared/particle-dismiss/rules.properties").inputStream())','ParticleMicroflakeRules.read(File(root, "shared/particle-dismiss/rules.properties").inputStream(), File(root, "shared/particle-dismiss/common-release.f32").inputStream())')
p.write_text(s,'utf-8',newline='\n')
print('Android 共同表示和独立采样已接入。')
