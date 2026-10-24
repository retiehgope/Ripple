package com.local.spwconv;

import java.util.Random;

/**
 * SpwConvolve 的离线自测（不碰 BASS / JNA，纯算法）
 *
 * 跑法：
 *   .\test.ps1
 *   或 java -cp "build\SpwConvolve3.jar;<宿主 fat jar>" SelfTest
 *
 * 输出刻意用英文：无论控制台是 GBK 还是 UTF-8 都不会乱码。
 */
public final class SelfTest {

    static int fails;
    static final int RATE = 48000;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        if (!ok) fails++;
    }

    static double rms(float[] d, int off, int len) {
        double s = 0;
        for (int i = 0; i < len; i++) { double v = d[off + i]; s += v * v; }
        return Math.sqrt(s / Math.max(1, len));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("SpwConvolve SelfTest  B=" + SpwConvolve.B + " N=" + SpwConvolve.N
                + " MAX_FRAMES=" + SpwConvolve.MAX_FRAMES);
        System.out.println();
        testDelta();
        testRandomIr();
        testChunkIndependence();
        testFlush();
        testAnchor();
        System.out.println();
        if (fails == 0) {
            System.out.println("ALL PASS");
        } else {
            System.out.println(fails + " FAILED");
            System.exit(1);
        }
    }

    /** delta IR 必须等于「延迟 B 帧的直通」 */
    static void testDelta() {
        SpwConvolve.Mono m = new SpwConvolve.Mono(new float[]{1f}, RATE);
        int n = 8192, B = SpwConvolve.B;
        float[] x = new float[n], y = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) Math.sin(2 * Math.PI * 440 * i / RATE) * 0.5f;
        m.process(x, 0, n, y, 0);
        double err = 0;
        for (int i = 0; i < B; i++) err = Math.max(err, Math.abs(y[i]));
        for (int i = 0; i < n - B; i++) err = Math.max(err, Math.abs(y[i + B] - x[i]));
        check("delta IR = passthrough with fixed delay " + B + " frames", err < 1e-4,
                "maxErr=" + String.format("%.2e", err));
    }

    /** 分区 overlap-add 必须与朴素线性卷积一致（误差只来自 float FFT） */
    static void testRandomIr() {
        Random rnd = new Random(42);
        int irLen = 4800, n = 8192, B = SpwConvolve.B;
        float[] ir = new float[irLen];
        for (int i = 0; i < irLen; i++) ir[i] = (float) (rnd.nextGaussian() * Math.exp(-i / 600.0));
        SpwConvolve.Mono m = new SpwConvolve.Mono(ir, RATE);
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) (rnd.nextGaussian() * 0.1);
        float[] y = new float[n];
        m.process(x, 0, n, y, 0);

        double[] ref = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int k = 0; k < irLen && k <= i; k++) s += (double) ir[k] * x[i - k];
            ref[i] = s * m.peakGain;
        }
        double err = 0, sig = 0;
        for (int i = 0; i < n - B; i++) {
            double d = y[i + B] - ref[i];
            err += d * d;
            sig += ref[i] * ref[i];
        }
        double rel = Math.sqrt(err / sig);
        check("partitioned FFT == naive convolution", rel < 1e-4,
                "relRMS=" + String.format("%.2e", rel) + " partitions=" + m.P);
    }

    /** 宿主块长不固定，结果必须与分块方式无关 */
    static void testChunkIndependence() {
        Random rnd = new Random(7);
        int irLen = 4800, n = 8192;
        float[] ir = new float[irLen];
        for (int i = 0; i < irLen; i++) ir[i] = (float) (rnd.nextGaussian() * Math.exp(-i / 500.0));
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) (rnd.nextGaussian() * 0.1);

        SpwConvolve.Mono one = new SpwConvolve.Mono(ir, RATE);
        SpwConvolve.Mono many = new SpwConvolve.Mono(ir, RATE);
        float[] y1 = new float[n], y2 = new float[n];
        one.process(x, 0, n, y1, 0);

        int[] chunks = {4320, 2866, 1006};   // 实机出现过的块长
        int off = 0;
        for (int c : chunks) {
            many.process(x, off, c, y2, off);
            off += c;
        }
        double err = 0;
        for (int i = 0; i < n; i++) err = Math.max(err, Math.abs(y1[i] - y2[i]));
        check("chunked (4320+2866+1006) == single 8192", err < 1e-6,
                "maxDiff=" + String.format("%.2e", err));
    }

    /** 旁路恢复后必须重新预热，不能把上段的 tail 拖进来 */
    static void testFlush() {
        Random rnd = new Random(3);
        float[] ir = new float[4800];
        for (int i = 0; i < 4800; i++) ir[i] = (float) (rnd.nextGaussian() * Math.exp(-i / 400.0));
        SpwConvolve.Mono m = new SpwConvolve.Mono(ir, RATE);
        int B = SpwConvolve.B, n = 4096;
        float[] x = new float[n], y = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) rnd.nextGaussian() * 0.2f;
        m.process(x, 0, n, y, 0);
        m.flush();
        float[] y2 = new float[n];
        m.process(x, 0, n, y2, 0);
        double head = 0;
        for (int i = 0; i < B; i++) head = Math.max(head, Math.abs(y2[i]));
        check("flush() re-prewarms (first " + B + " frames silent)", head == 0,
                "headPeak=" + head);
    }

    /** 1 kHz 锚点补偿后，1 kHz 正弦的电平应回到 0 dB */
    static void testAnchor() throws Exception {
        String irPath = "C:\\Users\\Lawrence Yao\\dsh\\salt-conv\\ir\\Sennheiser_HD_650-48000.wav";
        java.io.File f = new java.io.File(irPath);
        if (!f.isFile()) {
            System.out.println("SKIP  HD650 IR not found");
            return;
        }
        float[] ir = SpwConvolve.readWav(irPath)[0];
        SpwConvolve.Mono m = new SpwConvolve.Mono(ir, RATE);
        double anchorDb = 20 * Math.log10(m.anchorGain);

        int n = 48000, B = SpwConvolve.B;
        float[] x = new float[n], y = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) Math.sin(2 * Math.PI * 1000 * i / RATE) * 0.25f;
        m.process(x, 0, n, y, 0);
        double inR = rms(x, 12000, 12000);
        double outR = rms(y, B + 12000, 12000) * m.anchorGain;
        double db = 20 * Math.log10(outR / inR);
        check("HD650 anchor " + String.format("%+.2f", anchorDb) + " dB -> 1 kHz unity",
                Math.abs(db) < 1.0, "measured " + String.format("%+.2f", db) + " dB");
    }
}
