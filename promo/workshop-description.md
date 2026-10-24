# Ripple

**用于 Salt Player 的卷积滤镜插件**
**Convolution filter plugin for Salt Player**

---

## 这是什么 / What is this

Ripple 把你自己准备的 `.wav` 卷积滤波器（IR）挂到 Salt Player 的混音器上，实时做分块 FFT 卷积。
Ripple loads your own `.wav` convolution filter (IR) onto the Salt Player mixer and runs block-FFT convolution in real time.

用来做耳机校正、房间校正，或任何频响塑形；全程本地处理，不联网、不上传数据。
Use it for headphone correction, room correction, or any frequency shaping — fully local, no network, no data uploaded.

## 功能 / Features

- 支持 PCM 8/16/24/32 bit 与 float32/64 的 WAV，单声道或立体声
  Supports PCM 8/16/24/32-bit and float32/64 WAV, mono or stereo
- 任意采样率的滤波器自动重采样到当前播放采样率
  Filters at any sample rate are resampled to the current playback rate
- 最长 131072 taps（48 kHz 下约 2.7 秒），足够房间校正
  Up to 131072 taps (about 2.7 s at 48 kHz), enough for room correction
- 响度补偿：自适应 rms / 1 kHz 锚点 / 不补偿
  Loudness compensation: adaptive RMS / 1 kHz anchor / none
- 额外增益 ±12 dB，带软限幅与过载自动回退
  Extra gain ±12 dB with soft limiting and automatic overload rollback
- 旁路开关，随时 A/B
  Bypass switch for instant A/B
- 切歌、换采样率后自动重挂，ASIO 下几十毫秒内恢复
  Re-attaches after track or sample-rate changes, within tens of milliseconds on ASIO
- 不申请任何 Workshop 权限
  Requests no Workshop permissions

## 与 EQ 的区别 / How it differs from EQ

自带 EQ 用 5–10 段参数滤波器拼频响；卷积直接应用整条测量得到的冲激响应，可表达任意幅频与相位。
The built-in EQ builds a response from 5–10 parametric filters; convolution applies a whole measured impulse response and can express any magnitude and phase.

同一副耳机（HD800，oratory1990）的实测对照：
Measured comparison on the same headphone (HD800, oratory1990):

| 频段 / Band | 平均差 / Mean | 最大差 / Max |
| --- | --- | --- |
| 20 Hz – 1 kHz | ≈ 0.00 dB | 0.39 dB |
| 5 – 10 kHz | +0.18 dB | 1.62 dB |
| 10 – 20 kHz | −0.21 dB | 5.42 dB |

整体 RMS 差 0.74 dB，最大差 5.50 dB（20 kHz）。
Overall RMS difference 0.74 dB, maximum 5.50 dB at 20 kHz.

结论：平滑的耳机校正在 10 kHz 以下两者几乎等价；卷积胜在高频窄特征、相位还原，以及任何现成 IR。
In short: below 10 kHz the two are nearly identical for smooth correction; convolution wins on narrow high-frequency features, phase, and ready-made IRs.

## 去哪里下载 .wav 滤波器 / Where to get .wav filters

### 耳机校正 / Headphone correction

AutoEq 每个型号目录下都有 `... minimum phase 48000Hz.wav` 与 `... minimum phase 44100Hz.wav`。
AutoEq provides `... minimum phase 48000Hz.wav` and `... minimum phase 44100Hz.wav` in each model folder.

- 结果目录 / Results：<https://github.com/jaakkopasanen/AutoEq/tree/master/results>
- 型号索引 / Index：<https://github.com/jaakkopasanen/AutoEq/blob/master/results/INDEX.md>

### 房间校正 / Room correction

用 REW 测量后导出冲激响应为 WAV 再导入。
Measure with REW and export the impulse response as WAV.

- REW：<https://www.roomeqwizard.com/>

### 其他 / Other

社区卷积 EQ 包、第三方测量 IR 均可，注意来源可信。
Community convolution-EQ packs and third-party IRs also work — verify the source.

## 使用 / Usage

1. 创意工坊 → 模组管理 → 启用 Ripple
   Workshop → Mod management → enable Ripple
2. 打开插件配置 → 点「选择 .wav 文件」
   Open plugin settings → click "Select .wav file"
3. 首次使用建议保持「响度补偿 = 自适应 (rms)」
   For first use keep "Loudness compensation = adaptive (RMS)"

## 注意事项 / Notes

- 卷积滤波器通常峰值归一化，整体会变轻，用响度补偿补回
  Convolution filters are usually peak-normalized, so loudness drops; use compensation
- 卷积固有延迟约 42 ms（48 kHz / 2048 帧），听音乐无感，看视频可能轻微不同步
  About 42 ms inherent latency (2048 frames at 48 kHz); inaudible for music, possibly slight desync for video
- 不要和自带 EQ 同时套同一条校正曲线，会叠加成双份
  Do not chain the built-in EQ with the same correction curve — it doubles

## 兼容性 / Compatibility

- Salt Player for Windows（Steam），实测 1.19.0
  Salt Player for Windows (Steam), tested on 1.19.0
- Workshop API `0.1.0-dev22`，JDK 25
- 版本 / Version：1.0.0

## 许可 / License

以 MIT 许可发布，源码仓库：`https://github.com/retiehgope/ripple`（发布前把 `retiehgope` 换成你的 GitHub 名）。
Released under the MIT License. Source: `https://github.com/retiehgope/ripple`.

本插件不分发任何第三方滤波器文件；AutoEq 等测量数据遵循其各自许可。
No third-party filter files are bundled; measurement data such as AutoEq follows its own license.
