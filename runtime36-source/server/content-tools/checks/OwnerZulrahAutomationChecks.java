package io.ruin.model.activities.automation;

import io.ruin.model.activities.bosses.zulrah.Zulrah;
import io.ruin.model.map.Position;
import io.ruin.model.skills.prayer.Prayer;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Verifies detached decisions on all actual native rotation entries. No encounter is started. */
public final class OwnerZulrahAutomationChecks {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Method fixture = OwnerAutomationNativeChecks.class.getDeclaredMethod("loadFixture", Path.class);
        fixture.setAccessible(true);
        fixture.invoke(null, Paths.get(args[0]));
        Object rotations = field(Zulrah.class, "ROTATIONS").get(null);
        Field rotation = field(Zulrah.class, "rotation");
        Field currentPhase = field(Zulrah.class, "currentPhase");
        Field useRange = field(Zulrah.class, "useRange");
        Field meleeTarget = field(Zulrah.class, "meleeTarget");
        Zulrah combat = new Zulrah();
        ZulrahAutomation.Observation observation = (ZulrahAutomation.Observation) combat;
        int jads = 0, phases = 0;
        for (int r = 0; r < Array.getLength(rotations); r++) {
            Object entries = Array.get(rotations, r);
            rotation.set(combat, entries);
            for (int phase = 0; phase < Array.getLength(entries); phase++) {
                currentPhase.setInt(combat, phase);
                require(observation.ownerZulrahPhaseIndex() == phase, "native phase index preserved");
                String form = observation.ownerZulrahForm();
                phases++;
                switch (form) {
                    case "NORMAL":
                        require(ZulrahAutomation.protection(form, false) == Prayer.PROTECT_FROM_MISSILES, "normal ranged");
                        break;
                    case "MAGMA":
                        require(ZulrahAutomation.protection(form, false) == null, "tail uses movement");
                        break;
                    case "TANZANITE":
                        require(ZulrahAutomation.protection(form, true) == Prayer.PROTECT_FROM_MAGIC, "Tanzanite default");
                        break;
                    case "JAD":
                        jads++;
                        useRange.setBoolean(combat, true);
                        require(ZulrahAutomation.protection(form, observation.ownerZulrahNextJadRanged())
                                == Prayer.PROTECT_FROM_MISSILES, "Jad range");
                        useRange.setBoolean(combat, false);
                        require(ZulrahAutomation.protection(form, observation.ownerZulrahNextJadRanged())
                                == Prayer.PROTECT_FROM_MAGIC, "Jad magic");
                        break;
                    default:
                        throw new AssertionError("Unexpected native form " + form);
                }
            }
        }
        require(jads == 4 && phases == 43, "all native phase entries were checked");
        require(ZulrahAutomation.protection("UNKNOWN", true) == null, "unrecognized form is not assumed");
        require(observation.ownerZulrahMeleeTarget() == null, "missing telegraph");
        Position shared = new Position(6010, 6040, 0);
        meleeTarget.set(combat, shared);
        Position observed = observation.ownerZulrahMeleeTarget();
        require(observed != shared && observed.getX() == shared.getX() && observed.getY() == shared.getY(),
                "telegraph position is defensive copy");
        for (int x = -4; x <= 6; x++) {
            for (int y = -4; y <= 6; y++) {
                boolean protectedTile = x >= 0 && x <= 2 && y >= 0 && y <= 2;
                require(ZulrahAutomation.insideFume(6160+x, 6220+y, 6160, 6220) == protectedTile,
                        "matches the native inclusive 3x3 fume hit region");
            }
        }
        System.out.println("PASS " + assertions + " native Zulrah observations, prayers and fume assertions. No encounter run.");
        System.exit(0);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        assertions++;
    }
}
