# 分享产物体积基准工具

用于 [animated-share-evaluation.md](../animated-share-evaluation.md) 里那组体积
数字的复现。纯离线合成 + ffmpeg 编码，不依赖真机。

## 依赖

- ffmpeg（含 libx264 / libwebp）
- Python 3 + numpy + Pillow（本机用 conda 环境 `everythingdone`）

## 用法

`gen_longshot.py` 合成一张「EverythingDone 详情页长截图」的帧序列，写 rgb24 裸流
到 stdout：渐变背景 + 标题正文 + 2 列附件网格，前 N 格填带噪声的视频式动态内容，
其余格是静止照片瓦片。

```bash
python gen_longshot.py <宽> <高> <帧数> <动态格数> > /dev/null
```

`bench.sh` 把同一序列编码成全部候选产物（静态 JPEG / 整图 GIF / 半宽 GIF /
H.264 MP4 / 动态 WebP）并打印体积：

```bash
bash bench.sh 1080 4400 75 2 long
```

参数依次是宽、高、帧数、动态格数、输出目录后缀。评估里用的两档是
`1080 2400 75 1 short`（短记事）与 `1080 4400 75 2 long`（长记事），
75 帧 = 3 秒 @ 25fps，与 `VideoCoverPreviewManager` 的派生 GIF 规格一致。

## 注意

`bench.sh` 第一条命令带 `-frames:v 1`，ffmpeg 取到首帧就关管道，Python 侧会打一条
`OSError: [Errno 22]`（Windows 上的 broken pipe），无害，不影响后续测量。
