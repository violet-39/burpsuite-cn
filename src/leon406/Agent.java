package leon406;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.security.CodeSource;
import java.util.jar.JarFile;

/**
 * Burp Suite 中文语言包（Java Agent）。
 * <p>
 * 用法：-javaagent:burpsuite-cn.jar=han
 * <p>
 * 与旧版汉化包的区别：注入层用 ASM 重写，不依赖 jdk.internal.org.objectweb.asm，
 * 也不再使用 javassist 修改 JDK 类，因此在 JDK 21 / 24 / 25 上都能正常工作。
 */
public final class Agent {

    private Agent() {
    }

    public static void premain(String args, Instrumentation inst) {
        String mode = (args == null || args.trim().isEmpty()) ? "han" : args.trim();
        if (!mode.contains("han")) {
            System.err.println("[burp-cn] disabled (no 'han' in args)");
            return;
        }
        System.out.println("[burp-cn] loading Chinese language pack ...");

        // 让被注入的 JDK 类（由 bootstrap 加载）能够直接调用 leon406.CnTranslator
        appendToBootstrap(inst);

        inst.addTransformer(new CnTransformer(), true);
        System.out.println("[burp-cn] transformer registered");
    }

    private static void appendToBootstrap(Instrumentation inst) {
        try {
            CodeSource cs = Agent.class.getProtectionDomain().getCodeSource();
            if (cs == null) {
                return;
            }
            URL loc = cs.getLocation();
            File f = new File(loc.toURI());
            if (f.isFile()) {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(f));
            }
        } catch (Throwable t) {
            System.err.println("[burp-cn] bootstrap append failed: " + t);
        }
    }
}
