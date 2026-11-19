import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Prepends the standalone selectors to the original client methods. */
public final class PatchStandaloneClient implements Opcodes {
    private static final String PACKAGE = "net/runelite/standalone/";

    public static void main(String[] args) throws Exception {
        try (JarFile client = new JarFile(args[0])) {
            Path classes = Paths.get(args[1]);
            prependSelector(client, classes, "WorldMapRegion", "java/io/File",
                "(Ljava/lang/String;Ljava/lang/String;I)Ljava/io/File;", "method337");
            prependSelector(client, classes, "CustomWorldType", "java/lang/String",
                "()Ljava/lang/String;", "getGameServerAddress", "getFileServerAddress");
        }
    }

    private static void prependSelector(JarFile client, Path classes, String name, String result,
                                       String descriptor, String... methods) throws Exception {
        String path = PACKAGE + name + ".class";
        JarEntry entry = client.getJarEntry(path);
        if (entry == null) {
            throw new IllegalStateException("Missing client class " + path);
        }
        Set<String> remaining = new HashSet<>(Arrays.asList(methods));
        try (InputStream input = client.getInputStream(entry)) {
            ClassReader reader = new ClassReader(input);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(ASM7, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String method, String type,
                                                 String signature, String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, method, type, signature, exceptions);
                    if (!type.equals(descriptor) || !remaining.remove(method)) {
                        return visitor;
                    }
                    return new MethodVisitor(ASM7, visitor) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            if (name.equals("WorldMapRegion")) {
                                visitMethodInsn(INVOKESTATIC, PACKAGE + "ManagedCacheDirectory",
                                    "select", "()Ljava/io/File;", false);
                            } else {
                                visitLdcInsn("kronos.host");
                                visitMethodInsn(INVOKESTATIC, "java/lang/System", "getProperty",
                                    "(Ljava/lang/String;)Ljava/lang/String;", false);
                            }
                            Label fallback = new Label();
                            visitInsn(DUP);
                            visitJumpInsn(IFNULL, fallback);
                            visitInsn(ARETURN);
                            visitLabel(fallback);
                            visitFrame(F_SAME1, 0, null, 1, new Object[]{result});
                            visitInsn(POP);
                        }
                    };
                }
            }, 0);
            if (!remaining.isEmpty()) {
                throw new IllegalStateException("Missing client methods in " + name + " " + remaining);
            }
            Path output = classes.resolve(path);
            Files.createDirectories(output.getParent());
            Files.write(output, writer.toByteArray());
        }
    }
}
