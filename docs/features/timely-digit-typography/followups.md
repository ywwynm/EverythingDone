# Followups - Timely 数字排版

## 2026-07-05 - 追加字体候选

- 用户确认第一轮可保留候选：Fraunces、Bodoni Moda、Libre Bodoni、Cinzel、Libre Baskerville、Josefin Sans、Exo 2。
- 用户确认第二轮看中的候选：Space Grotesk、Limelight、Righteous、Poiret One、Major Mono Display、Genos、Italiana、Nixie One、Big Shoulders Stencil、Sirin Stencil、Allerta Stencil、Saira Stencil、Stardos Stencil、Outfit、Monoton。
- 仍可优先尝试接入的优雅 / 适配性较好的候选：Prata、Spectral、Quattrocento、Tenor Sans。
- 可作为技术 / 数字感补充的候选：Rajdhani、Oxanium、Chakra Petch、Quicksand。
- 第二轮可直接用当前管线继续试的其它风格：Righteous、Poiret One、Limelight、Antonio、Teko、Saira Condensed、Oswald、Space Grotesk、Sora、Urbanist、Outfit、Nixie One、Courier Prime、Gilda Display、Forum、Gemunu Libre、Kanit、Major Mono Display、Jersey 10。
- 第二轮好看但需要先扩展离线管线支持多外轮廓 / 多填充分片的候选：Handjet、Silkscreen、Tiny5、Allerta Stencil、Saira Stencil、Saira Stencil One、Stardos Stencil、Sirin Stencil、Big Shoulders Stencil、Black Ops One、Graduate、Monoton、Bungee Shade、Special Elite。
- 接入顺序建议：先用现有管线做 Space Grotesk、Limelight、Righteous、Poiret One、Major Mono Display、Genos、Italiana、Nixie One、Outfit 的预览与真机包；再扩展多外轮廓支持后做 Big Shoulders Stencil、Sirin Stencil、Allerta Stencil、Saira Stencil、Stardos Stencil、Monoton。
- 暂不优先：Unbounded 过宽，容易压缩录音 dialog；Marcellus 与 Belleza 默认数字高度 / 落点差异偏大；Cormorant Garamond 仍需解决 lining figures 后再接入。
- 接入时建议先扩展 `docs/features/timely-digit-typography/tools/generate_glyph_data.py` 的 `STYLES` / `ORDER`，生成 JSON 与 chooser 预览后再改 `DoingDigitStyleDialogFragment.STYLES`。
