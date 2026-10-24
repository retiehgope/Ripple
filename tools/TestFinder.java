import org.pf4j.PluginDescriptor;
import org.pf4j.PluginDescriptorFinder;
import org.pf4j.ManifestPluginDescriptorFinder;
import org.pf4j.PropertiesPluginDescriptorFinder;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 用「宿主自己的」描述符查找器解析 .spmod，复现导入时的真实异常。
 *
 * 用法：java -cp "<hostFat>;<classes>" TestFinder <spmod路径>
 */
public final class TestFinder {
    public static void main(String[] args) throws Exception {
        Path spmod = Paths.get(args[0]);
        String fat = args.length > 1 ? args[1] : System.getProperty("host.jar");

        URLClassLoader cl = new URLClassLoader(new URL[]{Paths.get(fat).toUri().toURL()},
                TestFinder.class.getClassLoader());

        List<Class<?>> candidates = new ArrayList<>();
        try (JarFile jf = new JarFile(fat)) {
            var it = jf.entries();
            while (it.hasMoreElements()) {
                JarEntry e = it.nextElement();
                String n = e.getName();
                if (!n.endsWith(".class")) continue;
                if (!n.startsWith("com/xuncorp/voxzen/workshop/")) continue;
                String cn = n.substring(0, n.length() - 6).replace('/', '.');
                try {
                    Class<?> c = Class.forName(cn, false, cl);
                    if (PluginDescriptorFinder.class.isAssignableFrom(c)
                            && c != ManifestPluginDescriptorFinder.class
                            && c != PropertiesPluginDescriptorFinder.class) {
                        candidates.add(c);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        System.out.println("候选描述符查找器: " + candidates.size());
        for (Class<?> c : candidates) {
            System.out.println("  " + c.getName());
            try {
                Object finder = c.getDeclaredConstructor().newInstance();
                PluginDescriptorFinder pdf = (PluginDescriptorFinder) finder;
                System.out.println("     isApplicable=" + pdf.isApplicable(spmod));
                PluginDescriptor d = pdf.find(spmod);
                System.out.println("     OK id=" + d.getPluginId()
                        + " class=" + d.getPluginClass()
                        + " version=" + d.getVersion()
                        + " provider=" + d.getProvider());
            } catch (Throwable t) {
                System.out.println("     EXCEPTION " + t.getClass().getName() + ": " + t.getMessage());
                Throwable cause = t.getCause();
                while (cause != null) {
                    System.out.println("       caused by " + cause.getClass().getName() + ": " + cause.getMessage());
                    cause = cause.getCause();
                }
            }
        }

        // 对照：PF4J 原生两个查找器
        System.out.println("--- PF4J ManifestPluginDescriptorFinder ---");
        try {
            PluginDescriptor d = new ManifestPluginDescriptorFinder().find(spmod);
            System.out.println("  OK id=" + d.getPluginId() + " class=" + d.getPluginClass());
        } catch (Throwable t) {
            System.out.println("  EXCEPTION " + t + " / cause=" + t.getCause());
        }
    }
}
