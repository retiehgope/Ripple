import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * 直接用宿主自己的 manifest 读取逻辑读插件包（不依赖方法名，按签名找）。
 * 用法：java -cp "<finder>;<hostFat>" TestManifest <插件包路径> <hostFat>
 */
public final class TestManifest {

    public static void main(String[] args) throws Exception {
        Path target = Paths.get(args[0]);
        String fat = args[1];

        URLClassLoader cl = new URLClassLoader(new URL[]{Paths.get(fat).toUri().toURL()},
                TestManifest.class.getClassLoader());

        int tried = 0;
        try (JarFile jf = new JarFile(fat)) {
            var it = jf.entries();
            while (it.hasMoreElements()) {
                JarEntry e = it.nextElement();
                String n = e.getName();
                if (!n.endsWith(".class")) continue;
                if (!n.startsWith("com/xuncorp/voxzen/workshop/")) continue;

                String cn = n.substring(0, n.length() - 6).replace('/', '.');
                Class<?> c;
                try {
                    c = Class.forName(cn, false, cl);
                } catch (Throwable ignored) {
                    continue;
                }

                for (Method m : c.getDeclaredMethods()) {
                    if (m.getReturnType() != Manifest.class) continue;
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length < 1 || ps.length > 2) continue;
                    if (ps[0] != Path.class) continue;
                    if (ps.length == 2 && ps[1] != String.class) continue;

                    Object inst = Modifier.isStatic(m.getModifiers()) ? null : instanceOf(c);
                    if (!Modifier.isStatic(m.getModifiers()) && inst == null) continue;

                    Object[] call = ps.length == 1
                            ? new Object[]{target}
                            : new Object[]{target, ""};
                    m.setAccessible(true);
                    tried++;
                    System.out.println("== " + cn + "." + m.getName()
                            + "(" + ps.length + " params)");
                    try {
                        Object r = m.invoke(inst, call);
                        System.out.println("   OK " + describe(r));
                    } catch (Throwable t) {
                        Throwable c2 = t.getCause() != null ? t.getCause() : t;
                        System.out.println("   FAIL " + c2.getClass().getSimpleName() + ": " + c2.getMessage());
                        Throwable cc = c2.getCause();
                        while (cc != null) {
                            System.out.println("        caused by " + cc.getClass().getSimpleName() + ": " + cc.getMessage());
                            cc = cc.getCause();
                        }
                    }
                }
            }
        }
        if (tried == 0) {
            System.out.println("没有找到 (Path) -> Manifest 的方法");
        }
    }

    private static Object instanceOf(Class<?> c) {
        try {
            var f = c.getDeclaredField("INSTANCE");
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable ignored) {
        }
        try {
            var ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String describe(Object r) {
        if (r == null) return "null";
        if (r instanceof Manifest mf) {
            var a = mf.getMainAttributes();
            return "Manifest{Plugin-Id=" + a.getValue("Plugin-Id")
                    + ", Plugin-Class=" + a.getValue("Plugin-Class") + "}";
        }
        return String.valueOf(r);
    }
}
