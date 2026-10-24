package com.local.spwconv;

/** 校验 SpwConvolve.readWav 对各种 WAV 格式的解析（不出声） */
public final class TestWav {
    public static void main(String[] args) throws Exception {
        java.io.File dir = new java.io.File("C:\\Users\\Lawrence Yao\\dsh\\tmp\\ir-test");
        java.io.File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".wav"));
        if (files == null) {
            System.out.println("no files");
            return;
        }
        java.util.Arrays.sort(files);
        int fail = 0;
        for (java.io.File f : files) {
            try {
                float[][] p = SpwConvolve.readWav(f.getAbsolutePath());
                float[] l = p[0];
                float[] r = p[1];
                double peakL = 0;
                for (float v : l) peakL = Math.max(peakL, Math.abs(v));
                boolean sameLr = true;
                for (int i = 0; i < l.length; i++) {
                    if (Math.abs(l[i] - r[i]) > 1e-6) {
                        sameLr = false;
                        break;
                    }
                }
                System.out.printf("%-28s taps=%-5d rate=%-6d chans=%d peakL=%.4f L==R=%s%n",
                        f.getName(), l.length, (long) SpwConvolve.IR_RATE, SpwConvolve.IR_CHANS, peakL, sameLr);
                if (l.length != 480 || Math.abs(peakL - 0.5) > 0.02) {
                    System.out.println("   !! 数值异常");
                    fail++;
                }
            } catch (Throwable t) {
                System.out.println(f.getName() + "  FAILED: " + t);
                fail++;
            }
        }
        System.out.println(fail == 0 ? "ALL PASS" : (fail + " FAILED"));
    }
}
