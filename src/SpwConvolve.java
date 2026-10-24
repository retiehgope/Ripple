package com.local.spwconv;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.lang.instrument.Instrumentation;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Salt Player 卷积原型（PoC #2 + 增益/旁路）
 *
 * 在宿主的 BASS 混音器通道上挂 DSP 回调，用「分块 FFT + overlap-add」做卷积。
 * 只用于验证可行性，不是产品代码。
 *
 * 可调参数（-D 系统属性）：
 *   spwconv.model      AutoEq 型号，如 "Sennheiser HD 650"
 *   spwconv.ir         直接指定 IR 文件（model 为空时生效）
 *   spwconv.makeup     响度补偿：rms（默认，自适应等响）| anchor（1 kHz 锚点）| none
 *   spwconv.gainDb     额外增益 dB，叠在补偿之后
 *   spwconv.ctl        控制文件路径，默认 tmp\spwconv.ctl（旁路/增益/makeup 热切换）
 *   spwconv.attachAll  true 时所有活跃通道都挂 DSP（诊断用）
 *   spwconv.skipIfEqActive  true 时宿主 EQ 非直通就不挂载
 *   spwconv.refresh    true 时忽略缓存重新下载 IR
 */
public final class SpwConvolve {

    static final String LOG_PATH = System.getProperty("spwconv.log",
            "C:\\Users\\Lawrence Yao\\dsh\\tmp\\convolve.log");
    static final String IR_PATH = System.getProperty("spwconv.ir", "C:\\Users\\Lawrence Yao\\dsh\\tmp\\music\\room-48k.wav");

    static PrintWriter LOG;

    static synchronized void log(String s) {
        try {
            if (LOG == null) LOG = new PrintWriter(new FileWriter(LOG_PATH, true), true);
            LOG.println(LocalDateTime.now() + "  " + s);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ JNA

    public interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);
        Pointer GetModuleHandleW(WString name);
    }

    public static class ChannelInfo extends Structure {
        public int freq, chans, flags, ctype, origres;
        public Pointer plugin, sample, filename;
        @Override protected List<String> getFieldOrder() {
            return Arrays.asList("freq", "chans", "flags", "ctype", "origres", "plugin", "sample", "filename");
        }
    }

    public interface DspProc extends StdCallLibrary.StdCallCallback {
        void invoke(int handle, int channel, Pointer buffer, int length, Pointer user);
    }

    public interface Bass extends Library {
        int BASS_ChannelIsActive(int handle);
        boolean BASS_ChannelGetInfo(int handle, ChannelInfo info);
        boolean BASS_ChannelSetDSP(int handle, DspProc proc, Pointer user, int priority);
        boolean BASS_ChannelRemoveDSP(int handle, int dspHandle);
        int BASS_GetConfig(int option);
        int BASS_GetVersion();
        int BASS_GetDevice();
        int BASS_ErrorGetCode();
    }

    static volatile Bass BASS;

    // ------------------------------------------------------------- 参数

    static final int B = 2048;          // 内部分块
    static final int N = 2 * B;         // FFT 长度
    static final int MAXP = 64;         // 最多分区数 → IR 最长 131072 taps（约 2.7 s @48k）
    static final int MAX_FRAMES = 65536;
    static final int MIXER_CTYPE = Integer.getInteger("spwconv.ctype", 0x10800);
    static final int FLOAT_FLAG = 0x100;

    static final AtomicLong BLOCKS = new AtomicLong();
    static final AtomicLong TOTAL_NS = new AtomicLong();
    static final Set<Integer> ATTACHED = Collections.synchronizedSet(new HashSet<Integer>());

    static float[] IR_L, IR_R;
    /** 上次成功加载的文件，配置丢失时兜底 */
    static volatile String LAST_GOOD_FILE = "";
    static volatile String LAST_IR_ERROR = "";
    static int IR_CHANS = 1;
    static volatile boolean SKIP_ATTACH;
    /** 最近一次成功挂载的时刻，用于决定扫描频率 */
    static volatile long LAST_ATTACH_MS;
    /** 已观察到的最大 BASS 句柄（无符号），快扫锚点 */
    static volatile long MAX_HANDLE_U;
    static double IR_RATE;

    // ------------------------------------------------------ 响度 / 旁路控制

    /** 运行期控制文件；agent 每 250 ms 轮询，改了立即生效 */
    static final String CTL_PATH = System.getProperty("spwconv.ctl",
            "C:\\Users\\Lawrence Yao\\dsh\\tmp\\spwconv.ctl");
    /** 默认只挂混音器；置 true 恢复旧行为（所有活跃通道都挂，用于诊断） */
    static final boolean ATTACH_ALL = Boolean.getBoolean("spwconv.attachAll");
    /** 自适应补偿的上下限（dB），防止把 IR 的原意改坏 */
    static final double MAKEUP_MIN_DB = Double.parseDouble(System.getProperty("spwconv.makeupMinDb", "-12"));
    static final double MAKEUP_MAX_DB = Double.parseDouble(System.getProperty("spwconv.makeupMaxDb", "12"));
    /** rms 自适应的平滑系数：每块约 90 ms，0.02 → 时间常数约 4.5 s */
    static final double MAKEUP_ALPHA = Double.parseDouble(System.getProperty("spwconv.makeupAlpha", "0.02"));

    /** 软限幅：峰值上限（<1 留余量），超过后按 tanh 软压缩到 1.0 以内 */
    static final float LIMIT_CEIL = (float) Double.parseDouble(System.getProperty("spwconv.limitCeil", "0.98"));
    static final boolean LIMIT_ON = Boolean.parseBoolean(System.getProperty("spwconv.limit", "true"));
    /** 一旦限幅样本占比超过该阈值，就自动回退补偿（防持续过载） */
    static final double LIMIT_FALLBACK_RATIO =
            Double.parseDouble(System.getProperty("spwconv.limitRatio", "0.005"));

    static volatile boolean BYPASS;
    static volatile double USER_GAIN_DB = Double.parseDouble(System.getProperty("spwconv.gainDb", "0"));
    /** rms | anchor | none */
    static volatile String MAKEUP_MODE = System.getProperty("spwconv.makeup", "rms");

    static final AtomicLong CLIP_SAMPLES = new AtomicLong();
    static final java.util.concurrent.atomic.AtomicBoolean OVERSIZE_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();
    static volatile float PEAK;
    /** 当前 rms 模式算出来的补偿量（dB） */
    static volatile double MAKEUP_DB;
    /** 软限幅触发后的自动回退量（dB，≤0），会缓慢恢复 */
    static volatile double AUTO_TRIM_DB;
    /** 无过载块计数，用于缓慢恢复 */
    static int RELEASE_TICKS;
    /** 被软限幅处理过的样本数 */
    static final AtomicLong LIMITED_SAMPLES = new AtomicLong();
    static double inPow, outPow;   // 仅 DSP 线程访问

    // ---------------------------------------------------------------- FFT

    static void fft(float[] re, float[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            float wRe = (float) Math.cos(ang);
            float wIm = (float) Math.sin(ang);
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                float curRe = 1f, curIm = 0f;
                for (int j = 0; j < half; j++) {
                    int a = i + j, b = a + half;
                    float uRe = re[a], uIm = im[a];
                    float vRe = re[b] * curRe - im[b] * curIm;
                    float vIm = re[b] * curIm + im[b] * curRe;
                    re[a] = uRe + vRe;
                    im[a] = uIm + vIm;
                    re[b] = uRe - vRe;
                    im[b] = uIm - vIm;
                    float nRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nRe;
                }
            }
        }
        if (inverse) {
            float s = 1f / n;
            for (int i = 0; i < n; i++) {
                re[i] *= s;
                im[i] *= s;
            }
        }
    }

    // ---------------------------------------------------------------- WAV

    /** 读取 WAV（PCM 8/16/24/32、IEEE float 32/64），返回 {L,R}；单声道自动复制 */
    static float[][] readWav(String path) throws Exception {
        byte[] all = new byte[(int) new File(path).length()];
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            raf.readFully(all);
        }
        int p = 12;
        int fmt = -1, chans = 1, bits = 16, dataOff = -1, dataLen = 0;
        double rate = 48000;
        while (p + 8 <= all.length) {
            String id = new String(all, p, 4, "ISO-8859-1");
            int len = (all[p + 4] & 0xff) | ((all[p + 5] & 0xff) << 8)
                    | ((all[p + 6] & 0xff) << 16) | ((all[p + 7] & 0xff) << 24);
            int body = p + 8;
            if ("fmt ".equals(id)) {
                fmt = (all[body] & 0xff) | ((all[body + 1] & 0xff) << 8);
                chans = (all[body + 2] & 0xff) | ((all[body + 3] & 0xff) << 8);
                rate = ((all[body + 4] & 0xff) | ((all[body + 5] & 0xff) << 8)
                        | ((all[body + 6] & 0xff) << 16) | ((all[body + 7] & 0xff) << 24)) & 0xffffffffL;
                bits = (all[body + 14] & 0xff) | ((all[body + 15] & 0xff) << 8);
            } else if ("data".equals(id)) {
                dataOff = body;
                dataLen = len;
            }
            p = body + len + (len & 1);
        }
        if (dataOff < 0) throw new IllegalStateException("no data chunk");
        int bytesPer = Math.max(1, bits / 8);
        int frameBytes = bytesPer * Math.max(1, chans);
        int frames = Math.min(dataLen / frameBytes, (all.length - dataOff) / frameBytes);
        float[] l = new float[frames];
        float[] r = new float[frames];
        for (int i = 0; i < frames; i++) {
            int o = dataOff + i * frameBytes;
            l[i] = wavSample(all, o, fmt, bits);
            r[i] = chans > 1 ? wavSample(all, o + bytesPer, fmt, bits) : l[i];
        }
        IR_RATE = rate;
        IR_CHANS = chans;
        return new float[][]{l, r};
    }

    static float wavSample(byte[] a, int o, int fmt, int bits) {
        if (fmt == 3) {
            if (bits == 32) {
                int v = (a[o] & 0xff) | ((a[o + 1] & 0xff) << 8)
                        | ((a[o + 2] & 0xff) << 16) | ((a[o + 3] & 0xff) << 24);
                return Float.intBitsToFloat(v);
            }
            if (bits == 64) {
                long v = 0;
                for (int k = 0; k < 8; k++) v |= ((long) (a[o + k] & 0xff)) << (8 * k);
                return (float) Double.longBitsToDouble(v);
            }
            throw new IllegalStateException("unsupported float bits: " + bits);
        }
        switch (bits) {
            case 8:
                return ((a[o] & 0xff) - 128) / 128f;
            case 16:
                return (short) ((a[o] & 0xff) | ((a[o + 1] & 0xff) << 8)) / 32768f;
            case 24: {
                int v = (a[o] & 0xff) | ((a[o + 1] & 0xff) << 8) | ((a[o + 2] & 0xff) << 16);
                if ((v & 0x800000) != 0) v |= 0xFF000000;
                return v / 8388608f;
            }
            case 32: {
                int v = (a[o] & 0xff) | ((a[o + 1] & 0xff) << 8)
                        | ((a[o + 2] & 0xff) << 16) | ((a[o + 3] & 0xff) << 24);
                return v / 2147483648f;
            }
            default:
                throw new IllegalStateException("unsupported bits: " + bits);
        }
    }

    static float[] resample(float[] in, double srcRate, double dstRate) {
        if (Math.abs(srcRate - dstRate) < 1e-6) return in;
        int n = (int) Math.round(in.length * dstRate / srcRate);
        float[] out = new float[n];
        double step = srcRate / dstRate;
        for (int i = 0; i < n; i++) {
            double x = i * step;
            int i0 = (int) x;
            int i1 = Math.min(in.length - 1, i0 + 1);
            double f = x - i0;
            out[i] = (float) (in[Math.min(i0, in.length - 1)] * (1 - f) + in[i1] * f);
        }
        return out;
    }

    // ------------------------------------------------------------ 卷积单元

    static final class Mono {
        final float[][] hRe, hIm, ringRe, ringIm;
        final float[] xRe = new float[N], xIm = new float[N], yRe = new float[N], yIm = new float[N];
        final float[] tail = new float[B];
        final float[] inBuf = new float[MAX_FRAMES + 4 * B];
        final float[] outBuf = new float[MAX_FRAMES + 4 * B];
        int inSize, outSize, ringPos;
        final int P;
        final double peakGain;
        /** 把 1 kHz 拉回 0 dB 所需的线性增益（AutoEq 的测量惯例） */
        final float anchorGain;

        Mono(float[] ir, double rate) {
            int p = Math.max(1, (ir.length + B - 1) / B);
            P = Math.min(MAXP, p);
            hRe = new float[P][N];
            hIm = new float[P][N];
            ringRe = new float[P][N];
            ringIm = new float[P][N];
            for (int k = 0; k < P; k++) {
                Arrays.fill(xRe, 0f);
                Arrays.fill(xIm, 0f);
                for (int i = 0; i < B; i++) {
                    int idx = k * B + i;
                    if (idx < ir.length) xRe[i] = ir[idx];
                }
                fft(xRe, xIm, false);
                System.arraycopy(xRe, 0, hRe[k], 0, N);
                System.arraycopy(xIm, 0, hIm[k], 0, N);
            }
            int bin1k = (int) Math.round(1000.0 * N / rate);
            bin1k = Math.max(1, Math.min(N / 2 - 1, bin1k));
            float maxMag = 1e-9f;
            double b1re = 0, b1im = 0;
            for (int f = 0; f < N; f++) {
                double sre = 0, sim = 0;
                for (int k = 0; k < P; k++) {
                    sre += hRe[k][f];
                    sim += hIm[k][f];
                }
                if (f == bin1k) {
                    b1re = sre;
                    b1im = sim;
                }
                float mag = (float) Math.hypot(sre, sim);
                if (mag > maxMag) maxMag = mag;
            }
            peakGain = 1.0 / maxMag;
            for (int k = 0; k < P; k++) {
                for (int f = 0; f < N; f++) {
                    hRe[k][f] *= peakGain;
                    hIm[k][f] *= peakGain;
                }
            }
            double mag1k = Math.hypot(b1re, b1im) / maxMag;
            anchorGain = (float) Math.min(8.0, Math.max(0.125, mag1k > 1e-9 ? 1.0 / mag1k : 1.0));
            outSize = B; // 预热：B 个静音，作为处理延迟
        }

        /** 旁路后恢复：清空内部状态，避免 tail 与输入错位（会重新预热 B 帧） */
        void flush() {
            inSize = 0;
            outSize = B;
            ringPos = 0;
            Arrays.fill(outBuf, 0f);   // 预热区里还留着上一段的数据，必须一起清
            Arrays.fill(tail, 0f);
            for (int k = 0; k < P; k++) {
                Arrays.fill(ringRe[k], 0f);
                Arrays.fill(ringIm[k], 0f);
            }
        }

        void process(float[] in, int off, int n, float[] out, int outOff) {
            System.arraycopy(in, off, inBuf, inSize, n);
            inSize += n;
            while (inSize >= B) {
                System.arraycopy(inBuf, 0, xRe, 0, B);
                Arrays.fill(xRe, B, N, 0f);
                Arrays.fill(xIm, 0f);
                fft(xRe, xIm, false);
                System.arraycopy(xRe, 0, ringRe[ringPos], 0, N);
                System.arraycopy(xIm, 0, ringIm[ringPos], 0, N);
                ringPos = (ringPos + 1) % P;

                Arrays.fill(yRe, 0f);
                Arrays.fill(yIm, 0f);
                for (int k = 0; k < P; k++) {
                    int idx = ((ringPos - 1 - k) % P + P) % P;
                    float[] aRe = ringRe[idx], aIm = ringIm[idx], bRe = hRe[k], bIm = hIm[k];
                    for (int i = 0; i < N; i++) {
                        yRe[i] += aRe[i] * bRe[i] - aIm[i] * bIm[i];
                        yIm[i] += aRe[i] * bIm[i] + aIm[i] * bRe[i];
                    }
                }
                fft(yRe, yIm, true);
                for (int i = 0; i < B; i++) outBuf[outSize + i] = yRe[i] + tail[i];
                outSize += B;
                for (int i = 0; i < B; i++) tail[i] = yRe[i + B];

                System.arraycopy(inBuf, B, inBuf, 0, inSize - B);
                inSize -= B;
            }
            if (outSize >= n) {
                System.arraycopy(outBuf, 0, out, outOff, n);
                System.arraycopy(outBuf, n, outBuf, 0, outSize - n);
                outSize -= n;
            } else {
                for (int i = 0; i < n; i++) out[outOff + i] = i < outSize ? outBuf[i] : 0f;
                outSize = 0;
            }
        }
    }

    static final class Chan {
        final Mono l, r;
        final int ctype;
        volatile boolean logged;
        /** 旁路期间不下发数据，恢复时先 flush 再淡入 */
        boolean bypassed;
        int fadeRemaining;

        final double rate;

        Chan(float[] irL, float[] irR, int ctype, double rate) {
            l = new Mono(irL, rate);
            r = new Mono(irR == null ? irL : irR, rate);
            this.ctype = ctype;
            this.rate = rate;
        }

        /** 首次回调时记录，用于停用插件时摘除 DSP */
        volatile int dspHandle = Integer.MIN_VALUE;
    }

    static final Map<Integer, Chan> STATE = new ConcurrentHashMap<>();
    static final float[] chL = new float[MAX_FRAMES];
    static final float[] chR = new float[MAX_FRAMES];
    static final AtomicLong BLOCKS_SEEN = new AtomicLong();

    /** 引擎生命周期（agent 与插件共用） */
    static volatile boolean ENGINE_ON;
    static volatile Thread SCANNER;
    static volatile Thread CTL_THREAD;

    // ------------------------------------------------------------ DSP 回调

    static final DspProc PROC = SpwConvolve::onDsp;

    /** 当前生效的线性增益 = 补偿（rms 自适应 / 1 kHz 锚点 / 无） + 用户额外增益 */
    static float currentGain(Chan chan) {
        double db;
        String m = MAKEUP_MODE;
        if ("anchor".equals(m)) db = 20 * Math.log10(chan.l.anchorGain);
        else if ("none".equals(m)) db = 0;
        else db = MAKEUP_DB;
        db += USER_GAIN_DB;
        return (float) Math.pow(10, db / 20);
    }

    /** 软限幅：|x|>ceil 时按 tanh 平滑压到 1.0 以内，几乎不产生硬削波的谐波 */
    static float softLimit(float x) {
        float a = Math.abs(x);
        if (a <= LIMIT_CEIL) return x;
        float y = LIMIT_CEIL + (1f - LIMIT_CEIL)
                * (float) Math.tanh((a - LIMIT_CEIL) / (1f - LIMIT_CEIL));
        return x < 0 ? -y : y;
    }

    static void onDsp(int dspHandle, int channel, Pointer buffer, int length, Pointer user) {
        try {
            Chan chan = STATE.get(channel);
            if (chan == null || buffer == null || length <= 0) return;
            if (chan.dspHandle == Integer.MIN_VALUE) chan.dspHandle = dspHandle;
            int samples = length / 4;
            int frames = samples / 2;
            if (frames <= 0) return;
            if (frames > MAX_FRAMES) {
                if (chan.ctype == MIXER_CTYPE && OVERSIZE_LOGGED.compareAndSet(false, true)) {
                    log("WARN 单块 frames=" + frames + " 超过 MAX_FRAMES(" + MAX_FRAMES + ")，该块直通");
                }
                return;
            }
            if (chan.ctype != MIXER_CTYPE) {
                if (!chan.logged) {
                    float[] probe = buffer.getFloatArray(0, Math.min(samples, 4096));
                    double r0 = rms(probe, probe.length);
                    if (r0 > 1e-6) {
                        chan.logged = true;
                        log("SIGNAL channel=" + channel + " ctype=0x" + Integer.toHexString(chan.ctype)
                                + " frames=" + frames + " rms=" + String.format("%.5f", r0));
                    }
                }
                return;
            }

            // 旁路：一个字节都不碰，真直通
            if (BYPASS) {
                if (!chan.bypassed) {
                    chan.bypassed = true;
                    chan.l.flush();
                    chan.r.flush();
                    log("BYPASS on ch=" + channel);
                }
                return;
            }
            if (chan.bypassed) {
                chan.bypassed = false;
                chan.fadeRemaining = B;
                log("BYPASS off ch=" + channel + " → 淡入 " + B + " 帧");
            }

            long t0 = System.nanoTime();

            float[] inter = buffer.getFloatArray(0, samples);
            double rmsIn = rms(inter, samples);
            for (int i = 0; i < frames; i++) {
                chL[i] = inter[2 * i];
                chR[i] = inter[2 * i + 1];
            }
            chan.l.process(chL, 0, frames, chL, 0);
            chan.r.process(chR, 0, frames, chR, 0);

            double rmsOut = 0;
            if ("rms".equals(MAKEUP_MODE) && rmsIn > 1e-4) {
                double s = 0;
                for (int i = 0; i < frames; i++) {
                    float a = chL[i], b = chR[i];
                    s += (double) a * a + (double) b * b;
                }
                rmsOut = Math.sqrt(s / (2.0 * frames));
                // 只在有信号时更新，避免静音段把补偿拉到极值
                double bi = rmsIn * rmsIn, bo = rmsOut * rmsOut;
                inPow += MAKEUP_ALPHA * (bi - inPow);
                outPow += MAKEUP_ALPHA * (bo - outPow);
                if (outPow > 1e-12 && inPow > 1e-12) {
                    double db = 10 * Math.log10(inPow / outPow);
                    MAKEUP_DB = Math.max(MAKEUP_MIN_DB, Math.min(MAKEUP_MAX_DB, db));
                }
            }

            float g = currentGain(chan) * (float) Math.pow(10, AUTO_TRIM_DB / 20.0);
            int fadeTotal = chan.fadeRemaining;
            long clip = 0;
            long limited = 0;
            float peak = PEAK;
            for (int i = 0; i < frames; i++) {
                float l = chL[i] * g, r = chR[i] * g;
                if (i < fadeTotal) {
                    float k = (float) (i + 1) / fadeTotal;   // 淡入，避免切换爆音
                    l *= k;
                    r *= k;
                }
                if (l > LIMIT_CEIL || l < -LIMIT_CEIL) {
                    if (LIMIT_ON) {
                        l = softLimit(l);
                        limited++;
                    } else if (l > 1f || l < -1f) {
                        clip++;
                    }
                }
                if (r > LIMIT_CEIL || r < -LIMIT_CEIL) {
                    if (LIMIT_ON) {
                        r = softLimit(r);
                        limited++;
                    } else if (r > 1f || r < -1f) {
                        clip++;
                    }
                }
                float al = Math.abs(l), ar = Math.abs(r);
                if (al > peak) peak = al;
                if (ar > peak) peak = ar;
                chL[i] = l;
                chR[i] = r;
            }
            if (fadeTotal > 0) chan.fadeRemaining = Math.max(0, fadeTotal - frames);
            if (clip > 0) CLIP_SAMPLES.addAndGet(clip);
            if (limited > 0) LIMITED_SAMPLES.addAndGet(limited);

            // 持续过载就自动回退补偿；恢复要慢，否则会来回抽吸
            if (LIMIT_ON) {
                double ratio = (double) limited / Math.max(1, 2 * frames);
                if (ratio > LIMIT_FALLBACK_RATIO && AUTO_TRIM_DB > -12.0) {
                    double step = ratio > 0.05 ? 1.5 : (ratio > 0.02 ? 0.8 : 0.3);
                    AUTO_TRIM_DB = Math.max(-12.0, AUTO_TRIM_DB - step);
                    RELEASE_TICKS = 0;
                    log("LIMIT fallback autoTrimDb=" + String.format("%+.1f", AUTO_TRIM_DB)
                            + " limitedRatio=" + String.format("%.4f", ratio)
                            + " step=" + step);
                } else if (limited == 0 && AUTO_TRIM_DB < 0) {
                    if (++RELEASE_TICKS >= 32) {
                        RELEASE_TICKS = 0;
                        AUTO_TRIM_DB = Math.min(0, AUTO_TRIM_DB + 0.25);
                    }
                } else if (limited > 0) {
                    RELEASE_TICKS = 0;
                }
            }
            PEAK = peak;

            for (int i = 0; i < frames; i++) {
                inter[2 * i] = chL[i];
                inter[2 * i + 1] = chR[i];
            }
            buffer.write(0, inter, 0, samples);

            long dt = System.nanoTime() - t0;
            long blocks = BLOCKS.incrementAndGet();
            TOTAL_NS.addAndGet(dt);
            if (blocks <= 6) {
                log("CONV block=" + blocks + " ch=" + channel + " frames=" + frames
                        + " costUs=" + (dt / 1000)
                        + " rmsIn=" + String.format("%.5f", rmsIn)
                        + " rmsOut=" + String.format("%.5f", rmsOut)
                        + " gainDb=" + String.format("%+.1f", 20 * Math.log10(g))
                        + " partitions=" + chan.l.P);
            }
            if (blocks % 500 == 0) {
                log("CONV stats blocks=" + blocks + " avgUs=" + (TOTAL_NS.get() / blocks / 1000)
                        + " makeupDb=" + String.format("%+.1f", MAKEUP_DB)
                        + " gainDb=" + String.format("%+.1f",
                                20 * Math.log10(currentGain(chan)) + AUTO_TRIM_DB)
                        + " autoTrimDb=" + String.format("%+.1f", AUTO_TRIM_DB)
                        + " peak=" + String.format("%.3f", PEAK)
                        + " limitedSamples=" + LIMITED_SAMPLES.get()
                        + " clipSamples=" + CLIP_SAMPLES.get()
                        + " bypass=" + BYPASS);
            }
        } catch (Throwable t) {
            log("CONV ERROR " + t);
        }
    }

    static double rms(float[] d, int n) {
        double s = 0;
        for (int i = 0; i < n; i++) s += (double) d[i] * d[i];
        return Math.sqrt(s / Math.max(1, n));
    }

    // -------------------------------------------------------------- 扫描

    static final int[] CANDIDATES = buildCandidates();

    static int[] buildCandidates() {
        java.util.LinkedHashSet<Integer> s = new java.util.LinkedHashSet<>();
        for (int i = 1; i <= 1024; i++) s.add(i);
        for (long i = 0x80000000L; i < 0x80004000L; i++) s.add((int) i);
        int[] a = new int[s.size()];
        int k = 0;
        for (int v : s) a[k++] = v;
        return a;
    }

    static String pickPath(String model) throws Exception {
        String idx = System.getProperty("spwconv.index", "C:\\Users\\Lawrence Yao\\dsh\\tmp\\autoeq\\index.tsv");
        String prefer = System.getProperty("spwconv.source", "oratory1990");
        java.util.List<String[]> hits = new java.util.ArrayList<>();
        for (String line : java.nio.file.Files.readAllLines(
                java.nio.file.Paths.get(idx), java.nio.charset.StandardCharsets.UTF_8)) {
            String[] c = line.split("\t");
            if (c.length < 2) continue;
            if (!c[0].equalsIgnoreCase(model)) continue;
            hits.add(c);
        }
        if (hits.isEmpty()) throw new IllegalStateException("model not found: " + model);
        for (String[] h : hits) if (h.length > 2 && prefer.equalsIgnoreCase(h[2])) return h[1];
        return hits.get(0)[1];
    }

    static float[][] downloadForModel(String model) throws Exception {
        String path = pickPath(model);
        String last = path.substring(path.lastIndexOf('/') + 1);
        String base = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
                + path + "/" + last + "%20minimum%20phase%20";
        java.io.File dir = new java.io.File("C:\\Users\\Lawrence Yao\\dsh\\tmp\\autoeq\\cache");
        dir.mkdirs();
        Exception lastErr = null;
        for (int rate : new int[]{48000, 44100}) {
            java.io.File out = new java.io.File(dir, last.replace("%20", "_") + "-" + rate + ".wav");
            // 缓存命中就不联网：省 8 秒 CONNECT 超时，也方便离线用
            if (out.isFile() && out.length() > 1024 && !Boolean.getBoolean("spwconv.refresh")) {
                try {
                    log("CACHE hit " + out.getName());
                    return readWav(out.getAbsolutePath());
                } catch (Throwable ignored) {
                    // 缓存坏了就当没有，走下载
                }
            }
            String url = base + rate + "Hz.wav";
            try {
                byte[] b = httpGet(url);
                java.nio.file.Files.write(out.toPath(), b);
                log("DOWNLOAD ok bytes=" + b.length + " path=" + path + " rate=" + rate);
                return readWav(out.getAbsolutePath());
            } catch (Exception e) {
                lastErr = e;
                log("DOWNLOAD failed rate=" + rate + " : " + e);
            }
        }
        throw lastErr != null ? lastErr : new IllegalStateException("download failed");
    }

    static byte[] httpGet(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "SaltPlayer-Conv-PoC");
        try (java.io.InputStream in = c.getInputStream();
             java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    static String hostEqState() {
        try {
            String appdata = System.getenv("APPDATA");
            String def = (appdata == null ? "" : appdata)
                    + "\\Salt Player for Windows\\data\\equalizer\\presets.json";
            java.io.File f = new java.io.File(System.getProperty("spwconv.eqstate", def));
            if (!f.isFile()) return "missing(" + f + ")";
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            boolean enabled = s.matches("(?s).*\"enabled\"\\s*:\\s*true.*");
            int bands = 0;
            int i = s.indexOf("\"bands\"");
            if (i >= 0) {
                int j = s.indexOf('[', i);
                int k = j < 0 ? -1 : s.indexOf(']', j);
                if (j > 0 && k > j) {
                    String inner = s.substring(j + 1, k).trim();
                    if (!inner.isEmpty()) bands = inner.split("\\{", -1).length - 1;
                }
            }
            return "enabled=" + enabled + " bands=" + bands;
        } catch (Throwable t) {
            return "err=" + t;
        }
    }

    static volatile String lastTopo = "";

    static void logTopology(java.util.List<String> items) {
        String topo = String.join(" | ", items);
        if (!topo.equals(lastTopo)) {
            lastTopo = topo;
            log("TOPO " + (topo.isEmpty() ? "(none)" : topo));
        }
    }

    static void scanOnce(long pass) {
        if (BASS == null) {
            if (Kernel32.INSTANCE.GetModuleHandleW(new WString("bass.dll")) == null) return;
            BASS = Native.load("bass", Bass.class);
            log("bass bound: version=0x" + Integer.toHexString(BASS.BASS_GetVersion())
                    + " device=" + BASS.BASS_GetDevice());
        }
        if (IR_L == null) {
            try {
                String file = System.getProperty("spwconv.irfile", "").trim();
                if (file.isEmpty() && LAST_GOOD_FILE != null && !LAST_GOOD_FILE.isEmpty()) {
                    file = LAST_GOOD_FILE;
                    log("IR fallback to last good file: " + file);
                }
                String model = System.getProperty("spwconv.model", "").trim();
                float[][] pair;
                if (!file.isEmpty()) {
                    pair = readWav(file);
                    log("IR ready: file=" + file + " taps=" + pair[0].length
                            + " rate=" + (long) IR_RATE + " srcChans=" + IR_CHANS);
                } else if (!model.isEmpty()) {
                    pair = downloadForModel(model);
                    log("IR ready: model=" + model + " taps=" + pair[0].length
                            + " rate=" + (long) IR_RATE + " srcChans=" + IR_CHANS);
                } else {
                    pair = readWav(IR_PATH);
                    log("IR ready: default=" + IR_PATH + " taps=" + pair[0].length
                            + " rate=" + (long) IR_RATE + " srcChans=" + IR_CHANS);
                }
                IR_L = pair[0];
                IR_R = pair[1];
                if (!file.isEmpty()) LAST_GOOD_FILE = file;
                String eq = hostEqState();
                log("HOST EQ: " + eq);
                boolean eqActive = false;
                if (eq.startsWith("enabled=true")) {
                    int bi = eq.indexOf("bands=");
                    if (bi >= 0) {
                        try {
                            eqActive = Integer.parseInt(eq.substring(bi + 6).trim().split("\\s")[0]) > 0;
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (eqActive) {
                    log("WARN: 宿主 EQ 非直通，卷积将与其叠加（双重校正）");
                    if (Boolean.getBoolean("spwconv.skipIfEqActive")) {
                        SKIP_ATTACH = true;
                        log("WARN: skipIfEqActive=true，跳过卷积挂载");
                    }
                }
            } catch (Throwable t) {
                String msg = String.valueOf(t);
                if (!msg.equals(LAST_IR_ERROR)) {
                    LAST_IR_ERROR = msg;
                    log("IR LOAD FAILED: " + msg + "（后续相同错误不再刷屏）");
                }
                return;
            }
        }

        if (IR_L == null || SKIP_ATTACH) return;

        int mixer = 0;
        int fallback = 0;
        java.util.List<String> topo = new java.util.ArrayList<>();
        for (int h : CANDIDATES) {
            int act;
            try {
                act = BASS.BASS_ChannelIsActive(h);
            } catch (Throwable t) {
                break;
            }
            if (act == 1) {
                ChannelInfo info = new ChannelInfo();
                if (!BASS.BASS_ChannelGetInfo(h, info)) continue;
                if ((info.flags & FLOAT_FLAG) == 0) continue;
                topo.add(h + ":0x" + Integer.toHexString(info.ctype) + ":" + info.freq + "x" + info.chans + ":act" + act);
                ATTACHED.add(h);
                if ((h & 0xFFFFFFFFL) > MAX_HANDLE_U) MAX_HANDLE_U = h & 0xFFFFFFFFL;
            } else if (act == 0 && ATTACHED.contains(h)) {
                ATTACHED.remove(h);
                STATE.remove(h);
            }
        }

        logTopology(topo);
        for (int h : CANDIDATES) {
            if (!ATTACHED.contains(h)) continue;
            if (STATE.containsKey(h)) continue;
            ChannelInfo info = new ChannelInfo();
            if (!BASS.BASS_ChannelGetInfo(h, info)) continue;
            // 默认只挂混音器：其他通道挂上去也只是空转，白烧 CPU
            if (!ATTACH_ALL && info.ctype != MIXER_CTYPE) continue;
            attachChannel(h);
        }
    }

    /** 挂一个通道（幂等），IR 按该通道采样率重采样 */
    static void attachChannel(int h) {
        if (STATE.containsKey(h)) return;
        try {
            ChannelInfo info = new ChannelInfo();
            if (!BASS.BASS_ChannelGetInfo(h, info)) return;
            float[] irL = resample(IR_L, IR_RATE, info.freq);
            float[] irR = resample(IR_R, IR_RATE, info.freq);
            STATE.put(h, new Chan(irL, irR, info.ctype, info.freq));
            boolean ok = BASS.BASS_ChannelSetDSP(h, PROC, Pointer.NULL, 0);
            if (ok) {
                LAST_ATTACH_MS = System.currentTimeMillis();
            }
            log("ATTACH handle=" + h + " ok=" + ok + " ctype=0x" + Integer.toHexString(info.ctype)
                    + " freq=" + info.freq + " chans=" + info.chans + " irTaps=" + irL.length
                    + (IR_CHANS > 1 ? " (stereo)" : ""));
        } catch (Throwable t) {
            log("ATTACH FAILED h=" + h + " : " + t);
        }
    }

    /**
     * 窄窗快扫：只探测「最近分配的句柄」附近，25 ms 一次。
     * 宿主切歌会重建混音器（新句柄单调递增），这样能在几十毫秒内挂上，听不到断档。
     */
    static void fastScan() {
        if (BASS == null || IR_L == null || SKIP_ATTACH) return;
        long top = MAX_HANDLE_U;
        if (top == 0) return;

        // 已挂通道被销毁 → 立即摘除
        for (Integer h : new java.util.ArrayList<>(STATE.keySet())) {
            int act;
            try {
                act = BASS.BASS_ChannelIsActive(h);
            } catch (Throwable t) {
                return;
            }
            Chan cur = STATE.get(h);
            if (act != 0 && cur != null) {
                ChannelInfo ci = new ChannelInfo();
                if (BASS.BASS_ChannelGetInfo(h, ci) && Math.abs(ci.freq - cur.rate) > 0.5) {
                    log("RATE CHANGE h=" + h + " " + (long) cur.rate + " -> " + ci.freq + "，重挂");
                    act = 0;
                }
            }
            if (act == 0) {
                Chan ch = STATE.remove(h);
                if (ch != null && ch.dspHandle != Integer.MIN_VALUE) {
                    try {
                        BASS.BASS_ChannelRemoveDSP(h, ch.dspHandle);
                    } catch (Throwable ignored) {
                    }
                }
                ATTACHED.remove(h);
                log("DETACH h=" + h + " (channel gone)");
            }
        }

        long start = top - 32;
        for (long u = start; u < start + 512; u++) {
            int h = (int) u;
            if (STATE.containsKey(h)) continue;
            int act;
            try {
                act = BASS.BASS_ChannelIsActive(h);
            } catch (Throwable t) {
                return;
            }
            if (act <= 0) continue;
            if (u > MAX_HANDLE_U) MAX_HANDLE_U = u;
            ChannelInfo info = new ChannelInfo();
            if (!BASS.BASS_ChannelGetInfo(h, info)) continue;
            if ((info.flags & FLOAT_FLAG) == 0) continue;
            if (!ATTACH_ALL && info.ctype != MIXER_CTYPE) continue;
            ATTACHED.add(h);
            attachChannel(h);
        }
    }

    static void scanner() {
        long pass = 0;
        long nextFull = 0;
        while (ENGINE_ON) {
            try {
                long now = System.currentTimeMillis();
                if (now >= nextFull) {
                    scanOnce(pass++);
                    boolean recent = now - LAST_ATTACH_MS < 30000L;
                    nextFull = now + (recent ? 500L : 2000L);
                } else {
                    fastScan();
                }
            } catch (Throwable t) {
                log("SCAN ERROR " + t);
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // ---------------------------------------------------------- 控制文件

    static void ctlLoop() {
        long lastMod = Long.MIN_VALUE, lastLen = Long.MIN_VALUE;
        while (true) {
            try {
                java.io.File f = new java.io.File(CTL_PATH);
                boolean exists = f.isFile();
                long mod = exists ? f.lastModified() : -1;
                long len = exists ? f.length() : -1;
                if (mod != lastMod || len != lastLen) {
                    lastMod = mod;
                    lastLen = len;
                    if (exists) applyCtl(f);
                }
            } catch (Throwable t) {
                log("CTL ERROR " + t);
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    static void applyCtl(java.io.File f) {
        try {
            boolean oldB = BYPASS;
            double oldG = USER_GAIN_DB;
            String oldM = MAKEUP_MODE;
            for (String line : java.nio.file.Files.readAllLines(f.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int i = line.indexOf('=');
                if (i <= 0) continue;
                String k = line.substring(0, i).trim().toLowerCase();
                String v = line.substring(i + 1).trim();
                switch (k) {
                    case "bypass":
                        BYPASS = v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on");
                        break;
                    case "gaindb":
                        USER_GAIN_DB = Double.parseDouble(v);
                        break;
                    case "makeup":
                        MAKEUP_MODE = v.toLowerCase();
                        break;
                    default:
                        break;
                }
            }
            if (oldB != BYPASS || oldG != USER_GAIN_DB || !oldM.equals(MAKEUP_MODE)) {
                log("CTL bypass=" + BYPASS + " gainDb=" + USER_GAIN_DB + " makeup=" + MAKEUP_MODE);
            }
        } catch (Throwable t) {
            log("CTL parse ERROR " + t);
        }
    }

    /** 启动引擎：扫描通道 + 挂 DSP + 控制文件轮询。可重复调用（已启动则忽略） */
    public static void startEngine(String who) {
        if (ENGINE_ON) {
            log("engine already running, ignore start (" + who + ")");
            return;
        }
        MAKEUP_MODE = MAKEUP_MODE == null ? "rms" : MAKEUP_MODE.trim().toLowerCase();
        ENGINE_ON = true;
        log("=== convolve " + who + " pid=" + ProcessHandle.current().pid()
                + " makeup=" + MAKEUP_MODE + " gainDb=" + USER_GAIN_DB
                + " bypass=" + BYPASS + " attachAll=" + ATTACH_ALL
                + " ctl=" + CTL_PATH);
        Thread s = new Thread(SpwConvolve::scanner, "spwconv-scanner-" + System.nanoTime());
        s.setDaemon(true);
        SCANNER = s;
        s.start();
        Thread c = new Thread(SpwConvolve::ctlLoop, "spwconv-ctl");
        c.setDaemon(true);
        CTL_THREAD = c;
        c.start();
    }

    /** 停止引擎：摘除所有 DSP、停线程、清状态（插件停用时调用） */
    public static void stopEngine(String why) {
        ENGINE_ON = false;
        Thread s = SCANNER, c = CTL_THREAD;
        SCANNER = null;
        CTL_THREAD = null;
        if (s != null) s.interrupt();
        if (c != null) c.interrupt();
        int removed = 0;
        try {
            if (BASS != null) {
                for (Map.Entry<Integer, Chan> e : new java.util.ArrayList<>(STATE.entrySet())) {
                    Chan ch = e.getValue();
                    if (ch != null && ch.dspHandle != Integer.MIN_VALUE) {
                        if (BASS.BASS_ChannelRemoveDSP(e.getKey(), ch.dspHandle)) removed++;
                    }
                }
            }
        } catch (Throwable t) {
            log("stopEngine removeDSP ERROR " + t);
        }
        STATE.clear();
        ATTACHED.clear();
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] st = new Throwable().getStackTrace();
        for (int i = 1; i < Math.min(st.length, 6); i++) {
            sb.append(i > 1 ? " <- " : "").append(st[i].getClassName()).append('.').append(st[i].getMethodName());
        }
        log("=== engine stop (" + why + ") removedDsp=" + removed + " from " + sb);
    }

    static void boot(String who) {
        startEngine(who);
    }

    /** 丢掉内存里的 IR，下次扫描重新加载（配合插件「重新下载」按钮） */
    public static void resetIr() {
        IR_L = null;
        IR_R = null;
        SKIP_ATTACH = false;
        log("IR reset");
    }

    public static void premain(String args, Instrumentation inst) {
        boot("premain");
    }

    public static void agentmain(String args, Instrumentation inst) {
        boot("agentmain");
    }
}
