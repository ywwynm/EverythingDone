# 语句里的完全限定名清理

## 缘起与统计

用户注意到代码里大量 `com.ywwynm.everythingdone.views.particledismiss.XXX` 这样的写法，问为什么
不加 import 直接用类名，并要求先统计其它包是否也有同样情况。

扫 `app/src/main/java` 与 `app/src/test/java` 共 657 个 `.kt` / `.java`（排除 `package` /
`import` 行、注释与字符串字面量），语句里直接写完全限定名的有 **791 处、分布在 108 个文件**：

| 分类 | 处数 |
|---|---:|
| 同文件没有任何同名类，可直接加 import | 669 |
| 同文件已经 import 了它，纯多余 | 59 |
| 同文件 import 了同名的别的类，必须限定 | 27 |
| 与 Kotlin 默认导入同名，必须限定 | 36 |

按来源：`android.*` 384、`com.ywwynm.everythingdone.*` 186、`java.*` 153、`androidx.*` 51、
其余 17。项目自己的包只占四分之一，`particledismiss` 一族 32 处。最集中的六个文件
（`BackgroundUtil`、`SpatialPhotoSettingsActivity`、`DetailActivity`、`ThingsActivity`、
`BitmapUtil`、`ImageViewerActivity`）占了三分之一。

## 做法

改写脚本只做两件事：去掉包名前缀、需要时在已有 import 块里补一行。判定与跳过规则：

- 注释、字符串字面量、`import` / `package` 行一律不动。去噪时把它们换成等长空白，偏移量与
  原文一一对应，替换直接落在原始文本上。
- 同一文件里同一个简单名指向两个不同的类 → 整组跳过。
- 同文件已 import 同名的别的类 → 跳过；已 import 它本身 → 只去前缀，不加 import。
- 与 Kotlin 默认导入（含 `java.lang.*`）同名 → 跳过。**唯一的例外是 `android.os.Process`**：
  项目别处已经是「import 之后裸用」，且没有任何文件用到 `java.lang.Process`。
- `java.util.ArrayList`、`LinkedHashMap` 等在 Kotlin 里本来就有同名 typealias → 只去前缀，
  不加 import。
- 包通配 import（`import foo.bar.*`）会引入未知类名 → 整文件跳过；静态成员通配
  （`import org.junit.Assert.*`）不影响类名解析 → 照常处理。
- 补 import 的位置按「与已有 import 共享最长包前缀的那一组，组内保持字典序」插入，因此落在
  原有分组里而不是文件末尾。三个原本没有 import 块的文件在 `package` 行后新起一块。

分七批改写，每批 `:app:assembleDebug`；最后一批加跑全量单测。

## 结果

**791 → 44 处**，改了 100 个文件（全是 `.kt`），新增 296 行 import，净 +1005 / −706。

剩下的 44 处全部是必须的：`android.R` 26 处与 `androidx.appcompat.R` 1 处（与项目自己的 `R`
撞名）、`java.lang.Double` 8 处、`java.lang.Float` 5 处、`java.lang.Long` 4 处（Kotlin 的
`Double` / `Float` / `Long` 是另一回事）。

## 验证

- 七批全部 `:app:assembleDebug` 通过；单测 1028 项，唯一失败是既有的
  `FableSolCurveFairnessTest`，与本轮无关（清理前后同一项、同一数量）。
- 另写了一个与改写代码相互独立的核对脚本：把改前改后两个版本都「抹掉所有包名前缀 + 去掉
  import 行 + 去掉空行」再逐字节比较，必须完全相同；新增的行必须全是 import 或空行；不得出现
  重复或丢失的 import。100 个文件全部通过——也就是说正文除了包名前缀没有任何别的差异。
- 未上真机：这是纯粹的名称解析改动，编译器与单测已经覆盖，真机跑不出额外信息。

## 发布复核

阿里云 debug 更新 `202609150855` 已发布。本地发布产物、远端完整 APK 与远端 `latest.json`
声明的 SHA-256 均为 `b5d83d287483661e21ac519567e14c27e1789365f18ff4d242c09718be62c4e8`，
26,758,586 字节，含完整 releaseNotes（1249 字）。发这一版只是让实际安装包也跑一遍——本轮
没有任何功能与外观改动，真出问题会是某个界面崩溃而不是显示异常。未创建 Git 提交。
