import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.objectweb.asm.Opcodes.*;

/** Apply narrow hooks to the actual winning, woven private server classes. */
public final class PatchBoundBloodFury {
    private static final String ROOT = "io/ruin/";
    private static final String PLAYER = ROOT + "model/entity/player/Player";
    private static final String ITEM = ROOT + "model/item/Item";
    private static final String CONTAINER = ROOT + "model/item/ItemContainerG";
    private static final String FURY = ROOT + "model/item/actions/impl/jewellery/BoundBloodFury";
    private static final String NPC_COMBAT = ROOT + "model/entity/npc/NPCCombat";
    private static final String KILLER = ROOT + "model/combat/Killer";
    private static final String GROUND = ROOT + "model/map/ground/GroundItem";
    private static final String DEATH = ROOT + "model/entity/shared/listeners/DeathListener";
    private static final String MAP = "Ljava/util/Map;";

    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args[0]);
        ClassNode equipment = read(ROOT + "model/item/containers/Equipment");
        guardItem(equipment, "equip", "(" + type(ITEM) + ")V", true, "equipAllowed");
        write(out, equipment);

        ClassNode trade = read(ROOT + "model/item/containers/Trade");
        guardItem(trade, "offer", "(" + type(ITEM) + "I)V", true, "transferAllowed");
        write(out, trade);

        ClassNode shop = read(ROOT + "model/shop/Shop");
        guardItem(shop, "sellToShop", "(" + type(PLAYER) + type(ITEM) + ")V", false, "transferAllowed");
        write(out, shop);

        ClassNode pin = read(ROOT + "model/item/containers/bank/BankPin");
        MethodNode allowed = new MethodNode(ACC_PUBLIC, "allowsPassiveDeposit", "()Z", null, null);
        LabelNode checkPin = new LabelNode();
        allowed.instructions.add(new VarInsnNode(ALOAD, 0));
        allowed.instructions.add(new FieldInsnNode(GETFIELD, pin.name, "verified", "Z"));
        allowed.instructions.add(new JumpInsnNode(IFEQ, checkPin));
        allowed.instructions.add(new InsnNode(ICONST_1));
        allowed.instructions.add(new InsnNode(IRETURN));
        frame(allowed.instructions, checkPin);
        allowed.instructions.add(new VarInsnNode(ALOAD, 0));
        allowed.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, pin.name, "hasPin", "()Z", false));
        allowed.instructions.add(new InsnNode(ICONST_1));
        allowed.instructions.add(new InsnNode(IXOR));
        allowed.instructions.add(new InsnNode(IRETURN));
        pin.methods.add(allowed);
        write(out, pin);

        ClassNode container = read(CONTAINER);
        InsnList inventory = new InsnList();
        inventory.add(new VarInsnNode(ALOAD, 0));
        inventory.add(new VarInsnNode(ILOAD, 1));
        inventory.add(new VarInsnNode(ILOAD, 2));
        inventory.add(new VarInsnNode(ALOAD, 3));
        inventory.add(call("bankInventory", "(" + type(CONTAINER) + "II" + MAP + ")Z"));
        LabelNode ordinaryAdd = new LabelNode();
        inventory.add(new JumpInsnNode(IFEQ, ordinaryAdd));
        inventory.add(new VarInsnNode(ILOAD, 2));
        inventory.add(new InsnNode(IRETURN));
        frame(inventory, ordinaryAdd);
        method(container, "add", "(II" + MAP + ")I").instructions.insert(inventory);
        write(out, container);

        ClassNode ground = read(GROUND);
        InsnList bankDrop = new InsnList();
        bankDrop.add(new VarInsnNode(ALOAD, 0));
        bankDrop.add(new VarInsnNode(ILOAD, 1));
        bankDrop.add(new VarInsnNode(ALOAD, 0));
        bankDrop.add(new FieldInsnNode(GETFIELD, GROUND, "attributes", MAP));
        bankDrop.add(call("bankGround", "(" + type(GROUND) + "I" + MAP + ")Z"));
        LabelNode ordinaryDrop = new LabelNode();
        bankDrop.add(new JumpInsnNode(IFEQ, ordinaryDrop));
        bankDrop.add(new VarInsnNode(ALOAD, 0));
        bankDrop.add(new InsnNode(ARETURN));
        frame(bankDrop, ordinaryDrop);
        method(ground, "spawn", "(I)" + type(GROUND)).instructions.insert(bankDrop);

        InsnList pickup = new InsnList();
        pickup.add(new VarInsnNode(ALOAD, 1));
        pickup.add(new VarInsnNode(ALOAD, 0));
        pickup.add(new FieldInsnNode(GETFIELD, GROUND, "id", "I"));
        pickup.add(new VarInsnNode(ALOAD, 0));
        pickup.add(new FieldInsnNode(GETFIELD, GROUND, "originalOwner", "I"));
        pickup.add(call("pickupAllowed", "(" + type(PLAYER) + "II)Z"));
        allowOrReturn(pickup);
        method(ground, "pickup", "(" + type(PLAYER) + "I)V").instructions.insert(pickup);
        write(out, ground);

        ClassNode npc = read(NPC_COMBAT);
        int dropHooks = 0, deathHooks = 0, heads = 0;
        for (MethodNode body : npc.methods) {
            if (!body.name.equals("startDeath") && !body.name.startsWith("lambda$startDeath$")
                    && !body.name.equals("vorkathHead")) continue;
            for (AbstractInsnNode node : body.instructions.toArray()) {
                if (!(node instanceof MethodInsnNode)) continue;
                MethodInsnNode invoked = (MethodInsnNode) node;
                if (invoked.owner.equals(NPC_COMBAT) && invoked.name.equals("dropItems")
                        && invoked.desc.equals("(" + type(KILLER) + ")V")) {
                    body.instructions.set(invoked, call("dropItems", "(" + type(NPC_COMBAT) + type(KILLER) + ")V"));
                    dropHooks++;
                } else if (invoked.owner.equals(DEATH) && invoked.name.equals("handle")) {
                    body.instructions.set(invoked, call("deathListener", "(" + type(DEATH) + invoked.desc.substring(1)));
                    deathHooks++;
                } else if (body.name.equals("vorkathHead") && invoked.owner.equals(GROUND) && invoked.name.equals("spawn")) {
                    body.instructions.insertBefore(invoked, new VarInsnNode(ALOAD, 2));
                    body.instructions.set(invoked, call("spawnVorkathHead", "(" + type(GROUND) + type(PLAYER) + ")" + type(GROUND)));
                    heads++;
                }
            }
        }
        if (dropHooks != 1 || deathHooks != 2 || heads != 1)
            throw new IllegalStateException("Expected 1 drop call, 2 death listeners and 1 special head, got "
                    + dropHooks + "/" + deathHooks + "/" + heads);
        write(out, npc);
    }

    private static void guardItem(ClassNode owner, String name, String signature, boolean playerField, String hook) {
        InsnList guard = new InsnList();
        if (playerField) {
            guard.add(new VarInsnNode(ALOAD, 0));
            guard.add(new FieldInsnNode(GETFIELD, owner.name, "player", type(PLAYER)));
        } else {
            guard.add(new VarInsnNode(ALOAD, 1));
        }
        guard.add(new VarInsnNode(ALOAD, playerField ? 1 : 2));
        guard.add(call(hook, "(" + type(PLAYER) + type(ITEM) + ")Z"));
        allowOrReturn(guard);
        method(owner, name, signature).instructions.insert(guard);
    }

    private static void allowOrReturn(InsnList instructions) {
        LabelNode allowed = new LabelNode();
        instructions.add(new JumpInsnNode(IFNE, allowed));
        instructions.add(new InsnNode(RETURN));
        frame(instructions, allowed);
    }

    private static void frame(InsnList instructions, LabelNode label) {
        instructions.add(label);
        instructions.add(new FrameNode(F_SAME, 0, null, 0, null));
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(INVOKESTATIC, FURY, name, descriptor, false);
    }

    private static String type(String name) { return "L" + name + ";"; }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
        }
        throw new IllegalStateException("Missing " + owner.name + "." + name + descriptor);
    }

    private static ClassNode read(String name) throws Exception {
        try (InputStream in = PatchBoundBloodFury.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (in == null) throw new IllegalArgumentException("Missing baseline class " + name);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static void write(Path output, ClassNode node) throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Path file = output.resolve(node.name + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, writer.toByteArray());
    }
}
