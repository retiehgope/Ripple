package com.local.spwconv;

import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.config.ConfigManager;

import java.util.function.Consumer;

/**
 * Salt Player 创意工坊插件入口（PF4J）。
 *
 * 用法：用户自己选一个 .wav 卷积滤波器（IR），插件把它挂到宿主混音器上做分块 FFT 卷积。
 * 不联网、不搜索型号；能力全部来自 JNA 直调宿主已加载的 bass.dll，不需要 CLASS_TRANSFORM 权限。
 */
public class MainPlugin extends SpwPlugin {

    private ConfigManager manager;
    private Consumer<ConfigHelper> listener;

    public MainPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        applyConfig(readConfig());
        syncDisplay();
        SpwConvolve.startEngine("plugin");
        try {
            manager = WorkshopApi.manager().createConfigManager();
            listener = MainPlugin::applyConfig;
            manager.addConfigChangeListener(listener);
        } catch (Throwable t) {
            System.out.println("[SpwConv] config listener failed: " + t);
        }
    }

    /** 把当前滤波器文件名写进界面上的「当前滤波器」一行 */
    private static void syncDisplay() {
        String path = System.getProperty("spwconv.irfile", "");
        if (path.isEmpty()) {
            path = readState();
        }
        String name = path.isEmpty() ? "（未选择）" : new java.io.File(path).getName();
        persist("display", name);
    }

    @Override
    public void stop() {
        try {
            if (manager != null && listener != null) {
                manager.removeConfigChangeListener(listener);
            }
        } catch (Throwable ignored) {
        }
        manager = null;
        listener = null;
        SpwConvolve.stopEngine("plugin");
    }

    @Override
    public void delete() {
        stop();
    }

    /** 配置界面按钮：选择 .wav 卷积滤波器 */
    public static void chooseFile() {
        try {
            javax.swing.SwingUtilities.invokeLater(() -> {
                try {
                    javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
                    fc.setDialogTitle("选择卷积滤波器 (.wav)");
                    fc.setAcceptAllFileFilterUsed(true);
                    fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                            "WAV 卷积滤波器 (*.wav)", "wav"));
                    String cur = System.getProperty("spwconv.irfile", "");
                    if (!cur.isEmpty()) {
                        java.io.File f = new java.io.File(cur);
                        if (f.isFile()) {
                            fc.setSelectedFile(f);
                        } else if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
                            fc.setCurrentDirectory(f.getParentFile());
                        }
                    }
                    if (fc.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) {
                        return;
                    }
                    String path = fc.getSelectedFile().getAbsolutePath();
                    System.setProperty("spwconv.irfile", path);
                    System.setProperty("spwconv.model", "");
                    persist("irfile", path);
                    writeState(path);
                    syncDisplay();
                    reloadEngine("choose-file");
                    System.out.println("[SpwConv] IR file = " + path);
                    toast("已加载：" + new java.io.File(path).getName(), true);
                } catch (Throwable t) {
                    System.out.println("[SpwConv] chooseFile failed: " + t);
                    toast("打开文件选择器失败：" + t, false);
                }
            });
        } catch (Throwable t) {
            System.out.println("[SpwConv] chooseFile dispatch failed: " + t);
            toast("无法打开文件选择器：" + t, false);
        }
    }

    /** 配置界面按钮：显示当前使用的滤波器 */
    public static void showStatus() {
        String path = System.getProperty("spwconv.irfile", "");
        if (path.isEmpty()) {
            path = readState();
        }
        if (path.isEmpty()) {
            toast("尚未选择卷积滤波器，点「选择 .wav 文件」", false);
        } else {
            toast("当前滤波器：" + new java.io.File(path).getName(), true);
        }
    }

    private static void toast(String msg, boolean ok) {
        try {
            WorkshopApi.ui().toast(msg, ok
                    ? WorkshopApi.Ui.ToastType.Success
                    : WorkshopApi.Ui.ToastType.Error);
        } catch (Throwable t) {
            System.out.println("[SpwConv] toast failed: " + t + " / " + msg);
        }
    }

    /** 配置界面按钮：重新加载当前文件 */
    public static void reload() {
        reloadEngine("reload");
    }

    private static void reloadEngine(String why) {
        SpwConvolve.stopEngine(why);
        SpwConvolve.resetIr();
        SpwConvolve.startEngine(why);
    }

    private static void persist(String key, String value) {
        try {
            ConfigHelper cfg = WorkshopApi.manager().createConfigManager().getConfig();
            cfg.set(key, value);
            cfg.save();
        } catch (Throwable t) {
            System.out.println("[SpwConv] persist failed: " + t);
        }
    }

    private static java.io.File stateFile() {
        String base = System.getenv("APPDATA");
        if (base == null || base.isEmpty()) {
            base = System.getProperty("user.home", ".");
        }
        return new java.io.File(base,
                "Salt Player for Windows\\workshop\\data\\spwconv-lastpath.txt");
    }

    private static String readState() {
        try {
            java.io.File f = stateFile();
            if (!f.isFile()) {
                return "";
            }
            return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeState(String path) {
        try {
            java.io.File f = stateFile();
            java.io.File dir = f.getParentFile();
            if (dir != null) {
                dir.mkdirs();
            }
            java.nio.file.Files.write(f.toPath(), path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            System.out.println("[SpwConv] writeState failed: " + t);
        }
    }

    private static ConfigHelper readConfig() {
        try {
            return WorkshopApi.manager().createConfigManager().getConfig();
        } catch (Throwable t) {
            System.out.println("[SpwConv] readConfig failed: " + t);
            return null;
        }
    }

    private static void applyConfig(ConfigHelper cfg) {
        if (cfg == null) {
            return;
        }
        try {
            String irfile = cfg.get("irfile", "");
            Object gainRaw = cfg.get("gain", 0f);
            String makeup = cfg.get("makeup", "rms");
            boolean bypass = cfg.get("bypass", false);

            String cfgPath = irfile == null ? "" : irfile.trim();
            String current = System.getProperty("spwconv.irfile", "");
            String path = cfgPath;
            if (path.isEmpty()) {
                // 宿主在页面切换/保存时可能回传空值，不能因此丢掉已生效的路径
                String saved = readState();
                path = saved.isEmpty() ? current : saved;
                if (!path.isEmpty()) {
                    System.out.println("[SpwConv] config 中 irfile 为空，沿用 " + path);
                }
            } else {
                writeState(path);
            }

            double gain = gainRaw instanceof Number ? ((Number) gainRaw).doubleValue() : 0.0;
            boolean changed = !path.isEmpty() && !path.equals(current);

            System.setProperty("spwconv.irfile", path);
            SpwConvolve.USER_GAIN_DB = gain;
            SpwConvolve.MAKEUP_MODE = makeup == null ? "rms" : makeup.trim().toLowerCase();
            SpwConvolve.BYPASS = bypass;

            if (changed) {
                SpwConvolve.resetIr();
            }
            System.out.println("[SpwConv] cfg file=" + (path.isEmpty() ? "(未选择)" : path)
                    + " gainDb=" + gain + " makeup=" + SpwConvolve.MAKEUP_MODE + " bypass=" + bypass);
        } catch (Throwable t) {
            System.out.println("[SpwConv] applyConfig failed: " + t);
        }
    }
}
