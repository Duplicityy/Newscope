import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Adds a local-map hook to the pinned client UI and recognizes completed Android taps. */
public final class PatchMobileMapInput implements Opcodes {
    private static final String CLIENT = "net/runelite/standalone/";
    private static final String MAP = CLIENT + "MobileMap";
    private static final String TAP = CLIENT + "MobileTap";
    private static final String TAP_DATA = TAP + "$Tap";
    private static final String TAP_DESCRIPTOR = "L" + TAP_DATA + ";";

    public static void main(String[] args) throws Exception {
        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        Files.createDirectories(output);
        ClassNode widgets = read(input.resolve("MusicPatchNode.class"));
        patchWidgets(widgets);
        write(widgets, output.resolve("MusicPatchNode.class"));
        ClassNode taps = read(input.resolve("MobileTap.class"));
        patchTaps(taps);
        write(taps, output.resolve("MobileTap.class"));
    }

    private static void patchWidgets(ClassNode type) {
        String signature = "([L" + CLIENT + "Widget;IIIIIII)V";
        MethodNode traverse = type.methods.stream()
                .filter(method -> method.name.equals("method3844") && method.desc.equals(signature))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing native widget traversal"));
        int hookCount = 0, guards = 0;
        for (AbstractInsnNode instruction : traverse.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (!call.owner.equals(MAP) || !call.name.equals("atlasInteractive") || !call.desc.equals("()Z"))
                continue;
            guards++;
            AbstractInsnNode end = call.getNext();
            while (end != null && end.getOpcode() < 0) end = end.getNext();
            if (end == null || end.getOpcode() != IFEQ)
                throw new IllegalStateException("Unexpected atlas input guard");
            AbstractInsnNode stop = ((org.objectweb.asm.tree.JumpInsnNode) end).label;
            boolean press = false;
            for (AbstractInsnNode next = end.getNext(); next != null && next != stop; next = next.getNext()) {
                if (!(next instanceof MethodInsnNode)) continue;
                MethodInsnNode downstream = (MethodInsnNode) next;
                if (downstream.owner.equals(CLIENT + "WorldMap") && downstream.name.equals("method6054")
                        && downstream.desc.equals("(IIZZ)V")) {
                    press = true;
                    break;
                }
            }
            if (!press) continue;

            // Match the native traversal's widget, screen origin and clipped tap flags.
            InsnList args = new InsnList();
            args.add(new VarInsnNode(ALOAD, 9));
            args.add(new VarInsnNode(ILOAD, 10));
            args.add(new VarInsnNode(ILOAD, 11));
            args.add(new VarInsnNode(ILOAD, 19));
            args.add(new VarInsnNode(ILOAD, 20));
            args.add(new InsnNode(IAND));
            traverse.instructions.insertBefore(call, args);
            traverse.instructions.set(call, new MethodInsnNode(INVOKESTATIC, MAP, "handleWidgetInput",
                    "(L" + CLIENT + "Widget;IIZ)Z", false));
            hookCount++;
        }
        if (guards != 3 || hookCount != 1) throw new IllegalStateException("Unexpected atlas button ABI");
    }

    private static void patchTaps(ClassNode type) {
        if (type.fields.stream().noneMatch(field -> field.name.equals("pressed") && field.desc.equals(TAP_DESCRIPTOR))
                || type.methods.stream().anyMatch(method -> method.name.equals("releasedLeftAt")))
            throw new IllegalStateException("Unexpected Android tap ABI");
        MethodVisitor check = type.visitMethod(ACC_STATIC | ACC_SYNCHRONIZED, "releasedLeftAt", "(II)Z", null, null);
        check.visitCode();
        Label fail = new Label();
        check.visitFieldInsn(GETSTATIC, TAP, "pressed", TAP_DESCRIPTOR);
        check.visitJumpInsn(IFNULL, fail);
        tapField(check, "button");
        check.visitInsn(ICONST_1);
        check.visitJumpInsn(IF_ICMPNE, fail);
        tapField(check, "x");
        check.visitVarInsn(ILOAD, 0);
        check.visitJumpInsn(IF_ICMPNE, fail);
        tapField(check, "y");
        check.visitVarInsn(ILOAD, 1);
        check.visitJumpInsn(IF_ICMPNE, fail);
        check.visitInsn(ICONST_1);
        check.visitInsn(IRETURN);
        check.visitLabel(fail);
        check.visitFrame(F_SAME, 0, null, 0, null);
        check.visitInsn(ICONST_0);
        check.visitInsn(IRETURN);
        check.visitMaxs(2, 2);
        check.visitEnd();
    }

    private static void tapField(MethodVisitor code, String name) {
        code.visitFieldInsn(GETSTATIC, TAP, "pressed", TAP_DESCRIPTOR);
        code.visitFieldInsn(GETFIELD, TAP_DATA, name, "I");
    }

    private static ClassNode read(Path path) throws Exception {
        ClassNode type = new ClassNode(ASM7);
        new ClassReader(Files.readAllBytes(path)).accept(type, 0);
        return type;
    }

    private static void write(ClassNode type, Path path) throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        Files.write(path, writer.toByteArray());
    }
}
