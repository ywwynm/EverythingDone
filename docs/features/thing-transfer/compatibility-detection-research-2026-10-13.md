# 卓易通运行环境识别调研

## 范围与结论

用户要求调查更可靠的卓易通识别方式，避免把普通华为 Android 设备误判进去；本轮只研究，不修改应用代码、不构建或发布、不连接设备。

当前 `TapGroupRolePolicy` 使用 `HUAWEI` 厂商和 `Build.DISPLAY` 的 `System ` 前缀。这是已观察设备的临时特征，不是公开的环境识别契约。找到比该规则更有依据的候选：检查鸿蒙 Android 兼容环境的特定 `anco` 系统属性。开源库已有实现，公开属性样本也提供了正反对照，但仍需用户鸿蒙 7 设备上的普通应用权限验证，不能宣称完全可靠。

## 已核对的实现

[DeviceCompat 的 DeviceOs.java](https://github.com/getActivity/DeviceCompat/blob/90e526c5aef07de05056cc18093da78844c7e93b/library/src/main/java/com/hjq/device/compat/DeviceOs.java#L256-L259) 定义了四个识别属性：

- `ro.product.anco.devicetype`
- `ro.sys.anco.product.software.version`
- `ro.product.os.dist.anco.apiversion`
- `ro.product.os.dist.anco.releasetype`

该库在普通 HarmonyOS 判断之前检查这些属性，只要任意一个能读到非空值，即识别为 `HarmonyOS NEXT AndroidCompatible`。公开方法为 `isHarmonyOsNextAndroidCompatible()`，说明涵盖卓易通和出境易；不能把它描述成仅识别卓易通产品的官方接口。

[属性读取实现](https://github.com/getActivity/DeviceCompat/blob/90e526c5aef07de05056cc18093da78844c7e93b/library/src/main/java/com/hjq/device/compat/SystemPropertyCompat.java) 依次尝试反射 `android.os.SystemProperties`、执行 `getprop`、读取 `build.prop`。有现成实现不等于这些路径在所有系统和应用权限下都可用，不建议未经验证照搬全部回退路径或引入整个库。

## 公开样本对照

样本来自 [AndroidSystemPropertyCollect](https://github.com/getActivity/AndroidSystemPropertyCollect/tree/245d4c2a6666dfd3079084b77088ae721f04ed1c)，只读取属性文本，没有执行仓库代码。

| 样本 | 四个候选属性 |
|---|---|
| [nova 12 Ultra，HarmonyOS NEXT 5.1.0](https://github.com/getActivity/AndroidSystemPropertyCollect/blob/245d4c2a6666dfd3079084b77088ae721f04ed1c/HarmonyOS%20NEXT/HarmonyOS%20NEXT%205.1.0%20Android%2012%20HUAWEI%20nova%2012%20Ultra.txt) | 全部存在；设备类型 `phone`，API 值 `50005`，发布类型 `Release`，软件版本包含 `5.1.0.150` |
| [Mate 70 Pro+，HarmonyOS NEXT 6.0.0](https://github.com/getActivity/AndroidSystemPropertyCollect/blob/245d4c2a6666dfd3079084b77088ae721f04ed1c/HarmonyOS%20NEXT/HarmonyOS%20NEXT%206.0.0%20Android%2012%20HUAWEI%20Mate%2070%20Pro%2B.txt) | 全部存在；设备类型 `phone`，API 值 `60000`，发布类型 `Beta5`，软件版本包含 `6.0.0.100` |
| [Mate 70，HarmonyOS 4.3.0](https://github.com/getActivity/AndroidSystemPropertyCollect/blob/245d4c2a6666dfd3079084b77088ae721f04ed1c/HarmonyOS/HarmonyOS%204.3.0%20Android%2012%20HUAWEI%20Mate%2070.txt) | 未出现；型号 `CLS-AL00`，显示版本为正常设备版本 |
| [Mate 30 Pro，HarmonyOS 4.2.0](https://github.com/getActivity/AndroidSystemPropertyCollect/blob/245d4c2a6666dfd3079084b77088ae721f04ed1c/HarmonyOS/HarmonyOS%204.2.0%20Android%2012%20HUAWEI%20Mate%2030%20Pro.txt) | 未出现；型号 `LIO-AL00`，显示版本为正常设备版本 |

另外成功读取的多个 EMUI 和 HarmonyOS 2／3／4 样本也没有这四个字段；部分下载失败，不计入验证。发现名为 `HarmonyOS 4.3.0 Android 13 HUAWEI Mate 70 Pro+.txt` 的文件实际含 Xiaomi／mars 属性，排除该文件，不能只凭文件名把它作为华为反例。以上是社区提交的属性快照，不是认证设备覆盖测试，也没有覆盖用户的鸿蒙 7／Android 16 兼容环境。

## 平台与可靠性边界

- 本轮没有找到华为或卓易通公开承诺稳定的 Android 环境识别 API。[卓易通官网](https://www.droitong.com/AboutUs.html) 提供产品介绍和联系渠道，未提供本次需要的 API 文档；不能把“未找到”写成“确认不存在”。
- [Android 官方非 SDK 接口说明](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces) 明确反射等访问方式也受非 SDK 限制。[AOSP 系统属性说明](https://source.android.com/docs/core/architecture/configuration/add-system-properties) 明确属性读取受进程访问规则控制，属性可访问性和稳定性是不同问题。因此 shell 快照有值不能证明普通 APK 必然可读，读取失败应视为未知。
- [第三方 DCloud 插件](https://ext.dcloud.net.cn/plugin?id=28917) 声称使用 cgroup 关键词、安装来源及 Build 字段组合识别。这只能证明作者采用这些方法，不能采信其“最可靠”的评级；通用容器词并不专属于卓易通，安装渠道也不是运行环境契约，不作为首选。

## 建议，尚未实施

先在后续经授权的诊断版里只读上述四个指定属性，区分空值和读取错误，验证用户设备可读性。若可读，以明确的兼容环境属性组合替代 `HUAWEI + System `；为了优先避免普通华为误判，可考虑至少两个属性有效且格式合理，比上游“任一非空”更保守，但会增加漏识别的可能，这个阈值仍需样本验证。

未命中或读取失败时保持普通 Android 组网路径，并保留手动覆盖；不再用宽泛的品牌／显示版本条件把未知环境判为卓易通。环境识别与“该版本需要接收方建组”的传输策略分开：识别到兼容环境不等于证明所有版本或出境易都有相同 P2P 问题。当前不修改已发布版本。

## 追加核对：按华为品牌优先建组的影响

用户随后提出，只按 `Build.MANUFACTURER` 的 HUAWEI 前缀选择华为建组，普通华为被纳入是否只改变组主。本轮仍为讨论，未授权代码改动。

当前识别结果同时影响两处：接收时是否预建组，以及发送时是否声明 `supportsReceiverGroup`。`WifiDirectSenderTransport` 对被识别的环境关闭加入接收组的能力。因此直接扩大公共判据会使普通华为发送端也不声明这项能力；当它向卓易通接收端发送时，接收端会走原路径，尝试加入普通华为发送组，重新遇到已经观察到的卓易通入组失败或需要系统确认的兼容路径。普通华为互传并不因此必然失败，但不能概括成仅无害地换一个组主。

“华为优先建组”可以作为较宽的角色偏好，与“已知环境不能可靠主动入组”的限制分开考虑。只要双方都能加入另一方的组，选择哪台建组不改变记事方向；普通华为与卓易通同时存在时，仅品牌无法确定哪台存在入组限制。这是当前代码推导及既有日志的结论，不是新增实机验证，也没有采用新的识别规则。
