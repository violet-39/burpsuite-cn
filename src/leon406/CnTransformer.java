package leon406;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Swing/AWT 的文本入口接到 {@link CnTranslator} 上。
 * <p>
 * 只做一件事：在目标方法开头插入
 * <pre>
 *   argN = CnTranslator.translate([this,] "cn", argN);
 * </pre>
 * 不使用 javassist，不生成任何分支，因此不依赖 StackMapTable 重算，
 * 在 JDK 21~25 上生成的字节码都能通过校验。
 */
public final class CnTransformer implements ClassFileTransformer {

    /** 参数：owner -> 需要注入的方法列表 */
    private static final Map<String, List<Target>> TARGETS = new HashMap<>();

    private static final class Target {
        final String name;
        final String desc;
        final int argIndex;
        final boolean isStatic;

        Target(String name, String desc, int argIndex, boolean isStatic) {
            this.name = name;
            this.desc = desc;
            this.argIndex = argIndex;
            this.isStatic = isStatic;
        }
    }

    private static void add(String owner, String name, String desc, int argIndex, boolean isStatic) {
        TARGETS.computeIfAbsent(owner, k -> new ArrayList<>())
                .add(new Target(name, desc, argIndex, isStatic));
    }

    static {
        // 窗口 / 对话框标题
        add("java/awt/Frame", "setTitle", "(Ljava/lang/String;)V", 1, false);
        add("java/awt/Dialog", "setTitle", "(Ljava/lang/String;)V", 1, false);

        // 标签、按钮、文本框、提示
        add("javax/swing/JLabel", "setText", "(Ljava/lang/String;)V", 1, false);
        add("javax/swing/AbstractButton", "setText", "(Ljava/lang/String;)V", 1, false);
        add("javax/swing/text/JTextComponent", "setText", "(Ljava/lang/String;)V", 1, false);
        add("javax/swing/JEditorPane", "setText", "(Ljava/lang/String;)V", 1, false);
        add("javax/swing/JComponent", "setToolTipText", "(Ljava/lang/String;)V", 1, false);
        add("javax/swing/text/AbstractDocument", "insertString",
                "(ILjava/lang/String;Ljavax/swing/text/AttributeSet;)V", 2, false);

        // 选项卡
        add("javax/swing/JTabbedPane", "insertTab",
                "(Ljava/lang/String;Ljavax/swing/Icon;Ljava/awt/Component;Ljava/lang/String;I)V", 1, false);
        add("javax/swing/JTabbedPane", "addTab",
                "(Ljava/lang/String;Ljavax/swing/Icon;Ljava/awt/Component;Ljava/lang/String;)V", 1, false);

        // 弹窗标题（静态方法，参数表里没有 this）
        add("javax/swing/JOptionPane", "showOptionDialog",
                "(Ljava/awt/Component;Ljava/lang/Object;Ljava/lang/String;IILjavax/swing/Icon;[Ljava/lang/Object;Ljava/lang/Object;)I",
                3, true);
        add("javax/swing/JOptionPane", "showMessageDialog",
                "(Ljava/awt/Component;Ljava/lang/Object;Ljava/lang/String;I)V", 3, true);
        add("javax/swing/JOptionPane", "showInputDialog",
                "(Ljava/awt/Component;Ljava/lang/Object;Ljava/lang/String;I)Ljava/lang/Object;", 3, true);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || !TARGETS.containsKey(className)) {
            return null;
        }
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            cr.accept(new Injector(className, cw), 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[burp-cn] inject failed " + className + ": " + t);
            return null;
        }
    }

    private static final class Injector extends ClassVisitor {
        private final String owner;

        Injector(String owner, ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
            this.owner = owner;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (mv == null) {
                return null;
            }
            final Target target = match(name, descriptor);
            if (target == null) {
                return mv;
            }
            return new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    if (!target.isStatic) {
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                    }
                    super.visitLdcInsn("cn");
                    super.visitVarInsn(Opcodes.ALOAD, target.argIndex);
                    if (target.isStatic) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "leon406/CnTranslator", "translate",
                                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
                    } else {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "leon406/CnTranslator", "translate",
                                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
                    }
                    super.visitVarInsn(Opcodes.ASTORE, target.argIndex);
                }
            };
        }

        private Target match(String name, String desc) {
            List<Target> list = TARGETS.get(owner);
            if (list == null) {
                return null;
            }
            for (Target t : list) {
                if (t.name.equals(name) && t.desc.equals(desc)) {
                    return t;
                }
            }
            return null;
        }
    }
}
