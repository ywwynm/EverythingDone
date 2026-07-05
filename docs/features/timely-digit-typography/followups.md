# Followups - Timely 数字排版

## 2026-07-05 - 追加字体候选

- 用户确认的第一轮字体已接入：Fraunces、Bodoni Moda、Libre Bodoni、Cinzel、Libre Baskerville、Josefin Sans、Exo 2。
- 用户确认的第二轮字体已接入：Space Grotesk、Limelight、Righteous、Poiret One、Major Mono Display、Genos、Italiana、Nixie One、Big Shoulders Stencil、Sirin Stencil、Allerta Stencil、Saira Stencil、Stardos Stencil、Outfit、Monoton。
- 仍可优先尝试接入的优雅 / 适配性较好的候选：Prata、Spectral、Quattrocento、Tenor Sans。
- 可作为技术 / 数字感补充的候选：Rajdhani、Oxanium、Chakra Petch、Quicksand。
- 第二轮可直接用当前管线继续试的其它风格：Righteous、Poiret One、Limelight、Antonio、Teko、Saira Condensed、Oswald、Space Grotesk、Sora、Urbanist、Outfit、Nixie One、Courier Prime、Gilda Display、Forum、Gemunu Libre、Kanit、Major Mono Display、Jersey 10。
- 多外轮廓 / 多填充分片管线已扩展，已接入：Big Shoulders Stencil、Sirin Stencil、Allerta Stencil、Saira Stencil、Stardos Stencil、Monoton。
- 仍可基于新管线继续尝试的其它多外轮廓候选：Handjet、Silkscreen、Tiny5、Saira Stencil One、Black Ops One、Graduate、Bungee Shade、Special Elite。
- 暂不优先：Unbounded 过宽，容易压缩录音 dialog；Marcellus 与 Belleza 默认数字高度 / 落点差异偏大；Cormorant Garamond 仍需解决 lining figures 后再接入。
- 接入时建议先扩展 `docs/features/timely-digit-typography/tools/generate_glyph_data.py` 的 `STYLES` / `ORDER`，生成 JSON 与 chooser 预览后再改 `DoingDigitStyleDialogFragment.STYLES`。
