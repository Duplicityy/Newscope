import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class PatchFirstPersonCamera implements Opcodes {
    private static final String CLIENT = "net/runelite/standalone/Client";
    private static final String CAMERA = "net/runelite/standalone/FirstPersonCamera";

    public static void main(String[] args) throws Exception {
        Path inputs = Paths.get(args[0]);
        Path outputs = Paths.get(args[1]);
        for (String name : new String[]{"Friend", "Scene", "MobileLayout"}) {
            ClassNode type = new ClassNode(ASM7);
            new ClassReader(Files.readAllBytes(inputs.resolve(name + ".class"))).accept(type, 0);
            if (name.equals("Friend")) {
                patchViewport(method(type, "method5176", "(IIII)V"));
            } else if (name.equals("Scene")) {
                patchPitch(method(type, "method3155", "(IIIIII)V"));
            } else {
                patchTouch(method(type, "camera", "(II)V"));
            }
            // Changed instructions retain their stack types. No branch targets or locals are added.
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            type.accept(writer);
            Files.write(outputs.resolve(name + ".class"), writer.toByteArray());
        }
    }

    private static MethodNode method(ClassNode type, String name, String desc) {
        return type.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc))
            .findFirst().orElseThrow(() -> new IllegalStateException("Missing " + type.name + "." + name + desc));
    }

    private static void patchViewport(MethodNode method) {
        int show = 0, position = 0, restoreCalls = 0;
        MethodInsnNode restore = null;
        boolean afterScene = false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (field(instruction, GETSTATIC, "renderSelf", "Z")) {
                method.instructions.set(instruction, call("renderSelf", "()Z"));
                show++;
            } else if (instruction instanceof MethodInsnNode) {
                MethodInsnNode inv = (MethodInsnNode) instruction;
                if (inv.owner.equals("net/runelite/standalone/NPCDefinition") && inv.name.equals("method4403")
                    && inv.desc.equals("(IIIIIII)V") && inv.getOpcode() == INVOKESTATIC) {
                    method.instructions.set(inv, call("position", inv.desc));
                    position++;
                }
                if (inv.owner.equals("net/runelite/standalone/Scene") && inv.name.equals("method3155")
                    && inv.desc.equals("(IIIIII)V")) {
                    afterScene = true;
                }
                if (afterScene && inv.owner.equals(CLIENT) && inv.name.equals("onCameraPitchChanged")
                    && inv.desc.equals("(I)V")) {
                    restore = inv;
                    restoreCalls++;
                }
            }
        }
        if (show != 1 || position != 1 || restoreCalls != 1) {
            throw new IllegalStateException("Unexpected viewport hook positions");
        }
        method.instructions.set(restore, call("restoreOrbitPitch", "(I)V"));
    }

    private static void patchPitch(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (field(instruction, GETSTATIC, "pitchRelaxEnabled", "Z")) {
                method.instructions.set(instruction, call("keepRawPitch", "()Z"));
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException("Unexpected scene pitch gate");
    }

    private static void patchTouch(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (field(instruction, GETSTATIC, "camAngleX", "I")) {
                // Insert immediately after the converted touch delta and before it
                // is added to the third-person pitch. Native edit/menu/cutscene guards already ran.
                AbstractInsnNode addition = instruction.getNext();
                while (addition != null && addition.getOpcode() != IADD) addition = addition.getNext();
                if (addition == null) throw new IllegalStateException("Missing touch delta addition");
                AbstractInsnNode conversion = addition.getPrevious();
                while (conversion != null && conversion.getOpcode() < 0) conversion = conversion.getPrevious();
                if (conversion == null || conversion.getOpcode() != L2I) {
                    throw new IllegalStateException("Unexpected touch delta computation");
                }
                method.instructions.insertBefore(addition, call("orbitPitchDelta", "(I)I"));
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException("Unexpected touch pitch gate");
    }

    private static boolean field(AbstractInsnNode instruction, int opcode, String name, String desc) {
        if (!(instruction instanceof FieldInsnNode) || instruction.getOpcode() != opcode) return false;
        FieldInsnNode field = (FieldInsnNode) instruction;
        return field.owner.equals(CLIENT) && field.name.equals(name) && field.desc.equals(desc);
    }

    private static MethodInsnNode call(String name, String desc) {
        return new MethodInsnNode(INVOKESTATIC, CAMERA, name, desc, false);
    }
}
