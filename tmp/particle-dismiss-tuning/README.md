# Dialog 粒子消散桌面验证

当前有效入口是 `render_curtain_model.py`。它直接从
`ParticleDismissRenderer.kt` 提取消失动画的 canonical GLSL，将版本头从 GLSL ES 3.00
转换为桌面 GLSL 3.30 后编译和渲染，不维护第二份 Shader。

运行：

```powershell
& 'C:\Users\ywwynm\miniconda3\envs\everythingdone\python.exe' `
  tmp\particle-dismiss-tuning\render_curtain_model.py
```

内容色强调回归检查：

```powershell
& 'C:\Users\ywwynm\miniconda3\envs\everythingdone\python.exe' `
  tmp\particle-dismiss-tuning\check_color_emphasis.py
```

该检查直接渲染 canonical 粒子层，以 4 个随机种子 × 4 个时刻统计高饱和 alpha 占比；
粒子相对原始快照的内容色强调倍率必须不低于 2.0×。

输出位于 `frames-curtain/`：

- `primary-up.mp4`：单次向上消散，60fps；
- `seed-matrix.mp4`：12 个随机种子同时播放，用于检查受控随机是否退化；
- `direction-matrix.mp4`：上、下、左、右上四种触点方向；
- `contact-up.png`：关键时间点接触表；
- `reference-vs-model.mp4`：由调研过程另行生成的参考视频并排对照。

`render_frames.py` 和 `index.html` 是第 1–41 轮历史蓝本，内含人工复制的旧 Shader，只用于
复查历史，不得再据此调整当前 Android 效果。
