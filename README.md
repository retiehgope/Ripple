# Ripple

用于 Salt Player 的卷积滤镜插件
Convolution filter plugin for Salt Player

---

## 这是什么 / What is this

Ripple 把你自己准备的 `.wav` 卷积滤波器（IR）挂到 Salt Player 的混音器上，实时做分块 FFT 卷积。
Ripple loads your own `.wav` convolution filter (IR) onto the Salt Player mixer and runs block-FFT convolution in real time.

可以用来做耳机校正、房间校正，或者任何你想要的频响塑形。
Use it for headphone correction, room correction, or any frequency-shaping you like.

全程本地处理，不联网、不上传任何数据。
Everything runs locally: no network access, no data leaves your machine.

## 功能 / Features

- 支持 PCM 8/16/24/32 bit 与 float32/64 的 WAV，单声道或立体声
  Supports PCM 8/16/24/32-bit and float32/64 WAV, mono or stereo
- 任意采样率的滤波器都会自动重采样到当前播放采样率
  Filters at any sample rate are resampled to the current playback rate automatically
- 最长 131072 taps（48 kHz 下约 2.7 秒），足够放房间校正
  Up to 131072 taps (about 2.7 s at 48 kHz), enough for room correction
- 响度补偿：自适应 rms / 1 kHz 锚点 / 不补偿
  Loudness compensation: adaptive RMS / 1 kHz anchor / none
- 额外增益 ±12 dB，软限幅保护，过载自动回退
  Extra gain ±12 dB with soft limiting and automatic overload rollback
- 旁路开关，方便 A/B 对比
  Bypass switch for quick A/B comparison
- 切片切歌后自动重新挂载，ASIO 下也保持在几十毫秒内
  Re-attaches automatically after track changes, within tens of milliseconds even on ASIO
- 不申请任何 Workshop 权限
  Requests no Workshop permissions

## 与 EQ 的区别 / How it differs from EQ

播放器自带的 EQ 用若干段参数滤波器（biquad）拼出频响，一条曲线通常只有 5–10 个控制点。
The player's built-in EQ builds a response from a handful of parametric (biquad) filters, usually 5–10 control points per curve.

卷积直接拿一整条测量得到的冲激响应（IR）做运算，理论上可以表达任意幅频与相位。
Convolution instead applies a measured impulse response (IR) as a whole, so it can express any magnitude and phase response.

下面的实测来自同一副耳机（HD800，oratory1990 测量）：把它的 EQ 曲线与同来源的卷积 IR 在频域逐点相比。
The numbers below compare the EQ curve and the convolution IR of the same measurement (HD800, oratory1990) point by point in the frequency domain.

| 频段 / Band | 平均差 / Mean diff | 最大差 / Max diff |
| --- | --- | --- |
| 20 Hz – 1 kHz | ≈ 0.00 dB | 0.39 dB |
| 5 – 10 kHz | +0.18 dB | 1.62 dB |
| 10 – 20 kHz | −0.21 dB | 5.42 dB |

整体 RMS 差 0.74 dB，最大差 5.50 dB 出现在 20 kHz。
The overall RMS difference is 0.74 dB, with the 5.50 dB maximum at 20 kHz.

也就是说：**做平滑的耳机校正时，10 kHz 以下两者几乎等价**；差别集中在高频的窄峰窄谷。
In short: for smooth headphone correction the two are nearly identical below 10 kHz; differences concentrate in narrow high-frequency features.

卷积更擅长的地方：
Where convolution wins:

- 高频的窄峰窄谷，EQ 的段数不够时补不出来
  Narrow peaks and dips at high frequencies that a few EQ bands cannot reproduce
- 相位与群延迟一并还原（房间校正、分频、串扰消除都依赖这个）
  Phase and group delay are reproduced too, which room correction, crossovers and crosstalk cancellation rely on
- 任何测量结果都能直接用：房间校正、HRTF、混响、软限幅的母带处理链
  Any measurement can be used directly: room correction, HRTF, reverb, or a mastering-style chain
- 想要的效果已经被人做成 IR 时，不用自己调段
  When someone already published the effect as an IR, you do not have to dial in bands

EQ 更擅长的地方：
Where EQ wins:

- 零延迟（卷积有约 42 ms 的固有延迟）
  Zero latency, while convolution adds about 42 ms
- 可以逐段拖动、边听边改，卷积要改就得重新做一份 IR
  Bands can be dragged and tweaked live, whereas a convolution change means producing a new IR
- 不依赖外部文件，也不挑采样率
  No external file, no sample-rate constraints

建议：同一副耳机只启用一种校正，两者串联会叠加成双份。
Recommendation: enable only one correction per headphone — chaining both doubles the correction.



本地导入：创意工坊 → 模组管理 → 导入模组 → 选择 `Ripple.jar`
Local import: Workshop → Mod management → Import mod → choose `Ripple.jar`

创意工坊：订阅后在模组管理中启用
Steam Workshop: subscribe, then enable it in mod management

启用后进入插件配置，点「选择 .wav 文件」挑选滤波器。
After enabling, open the plugin settings and click "Select .wav file".

## 去哪里下载 .wav 卷积滤波器 / Where to get .wav filters

### 耳机校正 / Headphone correction

AutoEq 收录了大量耳机的测量与校正结果，每个型号目录下都有
`... minimum phase 48000Hz.wav` 和 `... minimum phase 44100Hz.wav`，直接下载即可。
AutoEq hosts measurements and corrections for many headphones; each model folder contains
`... minimum phase 48000Hz.wav` and `... minimum phase 44100Hz.wav` — download either one.

- 结果目录 / Results: <https://github.com/jaakkopasanen/AutoEq/tree/master/results>
- 型号索引 / Model index: <https://github.com/jaakkopasanen/AutoEq/blob/master/results/INDEX.md>
- 用法：在索引里搜你的耳机型号 → 进入对应目录 → 下载 `minimum phase 48000Hz.wav`
  How: search your model in the index → open its folder → download `minimum phase 48000Hz.wav`

### 房间校正 / Room correction

用 REW（Room EQ Wizard）测量后导出冲激响应为 WAV，再导入本插件。
Measure with REW (Room EQ Wizard), export the impulse response as WAV, then load it here.

- REW 官网 / REW website: <https://www.roomeqwizard.com/>

### 其他来源 / Other sources

社区分享的卷积 EQ 包、厂商或第三方测量的 IR 也可以直接用，但请注意来源可信。
Community convolution-EQ packs and vendor or third-party IRs also work — just make sure the source is trustworthy.

## 注意事项 / Notes

卷积滤波器通常做峰值归一化，整体响度会变低，用「响度补偿」补回来即可。
Convolution filters are usually peak-normalized, so overall loudness drops; use "Loudness compensation" to bring it back.

卷积有约 42 ms 的固有延迟（48 kHz 下 2048 帧），听音乐无感，看视频可能出现轻微口型不同步。
Convolution adds about 42 ms of latency (2048 frames at 48 kHz). Inaudible for music, possibly slightly out of sync for video.

插件自带的 EQ 与卷积会串联叠加；如果播放器本身已经开了同一条校正曲线，请只留一个。
The player's built-in EQ and this plugin are chained; if the player already applies the same correction, keep only one.

## 兼容性 / Compatibility

- Salt Player for Windows（Steam 渠道），实测版本 1.19.0
  Salt Player for Windows (Steam), tested on 1.19.0
- Workshop API `0.1.0-dev22`，JDK 25
  Workshop API `0.1.0-dev22`, JDK 25
- 版本 / Version: 1.0.0

## 许可 / License

以 MIT 许可发布，源码仓库：`https://github.com/retiehgope/Ripple`。
Released under the MIT License. Source: `https://github.com/retiehgope/Ripple`.

本插件不分发任何第三方滤波器文件；AutoEq 等测量数据遵循其各自许可。
No third-party filter files are bundled; measurement data such as AutoEq follows its own license.
