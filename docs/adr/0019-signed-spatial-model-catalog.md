# 为远端空间计算组件 catalog 固定 Ed25519 信任根

空间深度模型及按设备 ABI 分发的 ONNX Runtime 由 EverythingDone 的阿里云服务器独立于 APK
分发；仅靠 HTTPS 和同源 catalog 内的 SHA-256 无法抵御分发源被整体篡改。我们决定由离线私钥
签署 catalog，App 固定 Ed25519 公钥并在采用模型或 Runtime 的 URL、大小、哈希、ABI 与兼容性
声明前先验证签名；stable 与 staging 使用不同密钥，release 只信任 stable，显式 staging 构建只
信任 staging。

## 影响

- 私钥不进入仓库、APK、模型服务器或常驻发布环境，只在受控的本地发布步骤中使用。
- catalog 验签失败时禁止新的模型或 Runtime 安装、更新，但不破坏已安装组件及已有
  Spatial Photo Derivative。
- 下载后的深度与补图模型仍须按已签名 catalog 校验字节数、SHA-256 并通过初始化自检；Runtime 还须校验
  解包后的双库名称、字节数与 SHA-256。两者都只在完整验证后原子启用。
- schema 1 的原 `inpaintingModels` 只放旧版 App 已认识的 MI-GAN；新增补图 ABI 放入可选的
  `additionalInpaintingModels`。旧 Gson 会忽略扩展字段，当前版合并两组后统一验签和逐项 ABI
  校验，从而避免新增模型让旧客户端拒绝整个 catalog。
- 仅扩大裁剪算子集合而不改变 Java/JNI ABI 时，Runtime 可保持 `runtimeApiVersion` 并提升
  `packageVersion`；当前 App 可以要求精确包版本触发升级，而旧 App 仍可安全使用算子超集。真正的
  ABI 或加载语义变化必须提升 `runtimeApiVersion`。
- catalog 不能自行增加信任公钥；正式密钥轮换需要 App 更新先提供受控的双公钥过渡期。
- 该方案增加密钥保管、发布和轮换成本，换取模型来源认证以及 stable/staging 的明确隔离。
