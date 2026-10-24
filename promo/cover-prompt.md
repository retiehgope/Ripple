# Ripple 封面图 Prompt

给 ChatGPT（GPT-4o 图像 / DALL·E）用。三条要求：**简洁**、**一眼看出是音频处理**、**缩到 512×512 仍然认得出来**。

---

## 一、主 Prompt（直接粘贴）

```
Create a minimal, premium key-art image for an audio software plugin named "Ripple".

Concept: a single droplet striking dark still water, and the concentric ripples seamlessly
turn into an audio frequency-response curve — the left half reads as water ripples,
the right half continues as a measured EQ curve. One continuous line, no break.

Style: flat vector look with very subtle gradient, dark charcoal-navy background (#0E1418),
one accent color only — cold sea-teal (#3FE0D0), plus a single warm highlight hairline.
Thin crisp lines (about 2 px at 1024 px width), generous negative space, perfectly centered,
high contrast, no gradients that look like AI art, no gloss, no 3D.

Composition: 1:1 square canvas. The ripple/curve motif sits in the lower two thirds;
keep the top third almost empty for a title. The image must stay legible when shrunk to
512×512 and to a 200 px thumbnail.

Hard constraints: NO text, NO letters, NO logo, NO watermark, NO signature, NO UI controls,
NO headphones floating in space, NO purple-blue AI gradient, NO busy details.

Deliver four variations: (1) ripple-to-curve line art, (2) a calm water surface seen from
above with one drop hitting it, (3) a minimal headphone silhouette whose earcup emits
concentric rings, (4) pure geometry — concentric circles cut by a straight frequency axis.
```

## 二、16:9 横幅（做 Steam 条目头图时用）

```
Same art direction and color palette as above, but 16:9 landscape. Put the motif on the
right third, leave the left two thirds clean for a title and one line of subtitle.
Keep it flat, minimal, high contrast, no text.
```

## 三、负向词（模型支持时追加）

```
text, letters, words, logo, watermark, cluttered, photorealistic, 3D render, plastic gloss,
neon glow overload, purple blue gradient, lens flare, drop shadow, busy background
```

---

## 四、配色（保持一致，别让模型自己发挥）

| 用途 | 值 |
| --- | --- |
| 背景 | `#0E1418`（近黑的冷灰） |
| 主色 | `#3FE0D0`（海青） |
| 辅色 | `#F2B84B`（一点暖金，只用一根细线或一个点） |
| 线条 | 2 px / 1024 px 宽 |

## 五、出图之后

1. **文字自己加**：图像模型写英文几乎必错，标题用 Photopea / Figma / Canva 叠上去，字体建议无衬线（Inter / SF / 思源黑体）。
2. **导出规格**：创意工坊封面 **< 1 MB**，PNG；先出 1024×1024，再降到 512×512 检查辨识度。
3. **文案建议**：
   - 主标题：`Ripple`
   - 副标题：`Convolution filter plugin for Salt Player`
4. **自检**：把图缩到 200 px 看一眼——还认得出「一波涟漪 + 一条频响曲线」就算过关。
