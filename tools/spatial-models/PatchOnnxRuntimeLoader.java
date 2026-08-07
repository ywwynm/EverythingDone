import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 对固定版本 ORT Java loader 做最小、可验证的字节码补丁。
 *
 * <p>ORT 1.28.0 的 Android 分支会直接调用 System.loadLibrary，因而忽略已经读取到的
 * onnxruntime.native.path。补丁只增加一个条件：当 libraryDirPathProperty 已设置时，
 * Android 也进入 ORT 原有的绝对路径加载分支。其余 API、初始化和 native 声明均保持原样。
 */
public final class PatchOnnxRuntimeLoader {
    private static final String OWNER = "ai/onnxruntime/OnnxRuntime";
    private static final String TARGET_ENTRY = OWNER + ".class";

    private PatchOnnxRuntimeLoader() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                "用法：PatchOnnxRuntimeLoader <原始 classes.jar> <输出 jar>"
            );
        }
        Path input = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());

        List<EntryData> entries = readEntries(input);
        int patchedEntries = 0;
        for (int index = 0; index < entries.size(); index++) {
            EntryData entry = entries.get(index);
            if (TARGET_ENTRY.equals(entry.name)) {
                entries.set(index, new EntryData(entry.name, patchClass(entry.bytes), false));
                patchedEntries++;
            }
        }
        require(patchedEntries == 1, "预期恰好一个 " + TARGET_ENTRY + "，实际为 " + patchedEntries);

        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        writeEntries(temporary, entries);
        verifyPatchedJar(temporary);
        try {
            Files.move(
                temporary,
                output,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("已生成 ORT Java loader 补丁：" + output);
    }

    private static List<EntryData> readEntries(Path jarPath) throws IOException {
        List<EntryData> entries = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> enumeration = jar.entries();
            while (enumeration.hasMoreElements()) {
                JarEntry entry = enumeration.nextElement();
                String upperName = entry.getName().toUpperCase();
                require(
                    !(upperName.startsWith("META-INF/") &&
                        (upperName.endsWith(".SF") ||
                            upperName.endsWith(".RSA") ||
                            upperName.endsWith(".DSA"))),
                    "原始 classes.jar 含签名条目，拒绝生成失效签名：" + entry.getName()
                );
                byte[] bytes = entry.isDirectory()
                    ? new byte[0]
                    : readAllBytes(jar.getInputStream(entry));
                entries.add(new EntryData(entry.getName(), bytes, entry.isDirectory()));
            }
        }
        entries.sort(Comparator.comparing(entry -> entry.name));
        return entries;
    }

    private static byte[] patchClass(byte[] original) {
        ClassNode classNode = new ClassNode();
        new ClassReader(original).accept(classNode, 0);
        require(OWNER.equals(classNode.name), "目标类名不匹配：" + classNode.name);

        MethodNode loadMethod = null;
        for (MethodNode method : classNode.methods) {
            if ("load".equals(method.name) && "(Ljava/lang/String;)V".equals(method.desc)) {
                require(loadMethod == null, "发现重复 load(String) 方法");
                loadMethod = method;
            }
        }
        require(loadMethod != null, "找不到 load(String) 方法");

        int matches = 0;
        for (AbstractInsnNode current = loadMethod.instructions.getFirst();
             current != null;
             current = current.getNext()) {
            if (!(current instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) current;
            if (call.getOpcode() != Opcodes.INVOKESTATIC ||
                !OWNER.equals(call.owner) ||
                !"isAndroid".equals(call.name) ||
                !"()Z".equals(call.desc)) {
                continue;
            }

            AbstractInsnNode jumpCandidate = nextReal(current.getNext());
            require(
                jumpCandidate instanceof JumpInsnNode &&
                    jumpCandidate.getOpcode() == Opcodes.IFEQ,
                "isAndroid() 后的控制流与固定 ORT 1.28.0 结构不符"
            );
            JumpInsnNode desktopJump = (JumpInsnNode) jumpCandidate;
            AbstractInsnNode argumentLoad = nextReal(jumpCandidate.getNext());
            AbstractInsnNode libraryCall = nextReal(argumentLoad == null ? null : argumentLoad.getNext());
            AbstractInsnNode returnInsn = nextReal(libraryCall == null ? null : libraryCall.getNext());
            require(
                argumentLoad instanceof VarInsnNode &&
                    argumentLoad.getOpcode() == Opcodes.ALOAD &&
                    ((VarInsnNode) argumentLoad).var == 0 &&
                    libraryCall instanceof MethodInsnNode &&
                    libraryCall.getOpcode() == Opcodes.INVOKESTATIC &&
                    "java/lang/System".equals(((MethodInsnNode) libraryCall).owner) &&
                    "loadLibrary".equals(((MethodInsnNode) libraryCall).name) &&
                    "(Ljava/lang/String;)V".equals(((MethodInsnNode) libraryCall).desc) &&
                    returnInsn != null &&
                    returnInsn.getOpcode() == Opcodes.RETURN,
                "Android loadLibrary 分支与固定 ORT 1.28.0 结构不符"
            );

            InsnList guard = new InsnList();
            guard.add(
                new FieldInsnNode(
                    Opcodes.GETSTATIC,
                    OWNER,
                    "libraryDirPathProperty",
                    "Ljava/lang/String;"
                )
            );
            guard.add(new JumpInsnNode(Opcodes.IFNONNULL, desktopJump.label));
            loadMethod.instructions.insert(jumpCandidate, guard);
            matches++;
        }
        require(matches == 1, "预期补丁一个 Android loader 分支，实际为 " + matches);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private static void verifyPatchedJar(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry target = jar.getJarEntry(TARGET_ENTRY);
            require(target != null, "输出 jar 缺少 " + TARGET_ENTRY);
            ClassNode classNode = new ClassNode();
            new ClassReader(readAllBytes(jar.getInputStream(target))).accept(classNode, 0);
            int guards = 0;
            for (MethodNode method : classNode.methods) {
                if (!"load".equals(method.name) ||
                    !"(Ljava/lang/String;)V".equals(method.desc)) {
                    continue;
                }
                for (AbstractInsnNode current = method.instructions.getFirst();
                     current != null;
                     current = current.getNext()) {
                    if (current instanceof FieldInsnNode) {
                        FieldInsnNode field = (FieldInsnNode) current;
                        AbstractInsnNode next = nextReal(current.getNext());
                        if (field.getOpcode() == Opcodes.GETSTATIC &&
                            OWNER.equals(field.owner) &&
                            "libraryDirPathProperty".equals(field.name) &&
                            next instanceof JumpInsnNode &&
                            next.getOpcode() == Opcodes.IFNONNULL) {
                            guards++;
                        }
                    }
                }
            }
            require(guards == 1, "输出 jar 的 loader guard 数量错误：" + guards);
        }
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode node) {
        AbstractInsnNode current = node;
        while (current != null &&
            (current.getType() == AbstractInsnNode.LABEL ||
                current.getType() == AbstractInsnNode.LINE ||
                current.getType() == AbstractInsnNode.FRAME)) {
            current = current.getNext();
        }
        return current;
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            stream.transferTo(output);
            return output.toByteArray();
        }
    }

    private static void writeEntries(Path output, List<EntryData> entries) throws IOException {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output))) {
            for (EntryData data : entries) {
                JarEntry entry = new JarEntry(data.name);
                entry.setTime(0L);
                jar.putNextEntry(entry);
                if (!data.directory) {
                    jar.write(data.bytes);
                }
                jar.closeEntry();
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class EntryData {
        final String name;
        final byte[] bytes;
        final boolean directory;

        EntryData(String name, byte[] bytes, boolean directory) {
            this.name = name;
            this.bytes = bytes;
            this.directory = directory;
        }
    }
}
