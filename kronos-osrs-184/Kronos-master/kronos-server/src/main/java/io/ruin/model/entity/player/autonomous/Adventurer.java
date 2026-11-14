package io.ruin.model.entity.player.autonomous;

import com.google.gson.annotations.Expose;
import io.ruin.api.protocol.login.LoginInfo;
import io.ruin.api.utils.ListUtils;
import io.ruin.model.World;
import io.ruin.model.entity.player.PlayerGroup;
import io.ruin.model.entity.player.ai.AIPlayer;
import io.ruin.model.entity.player.ai.ArtificialChannel;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.map.Position;
import io.ruin.model.stat.StatType;
import rsps.sim.*;
import java.util.*;

/** A real world Player with a decision controller instead of a remote socket. */
public final class Adventurer extends AIPlayer {
    @Expose public String actorId;
    @Expose public String displayName;
    @Expose public BuildPolicy.Archetype archetype;
    @Expose public BuildPolicy.CombatStyle combatStyle;
    @Expose public long seed;
    @Expose public String quarantineReason;
    @Expose public AdventurerMemory memory = new AdventurerMemory();
    @Expose public boolean normalStarterReceived;
    transient ProgressionBrain brain;
    transient KronosActivityPort port;
    transient AdventurerSafety safety;
    transient BuildPolicy policy;
    transient boolean ready;
    transient long saveAt;

    public Adventurer() { super("", new Position(2030, 3577, 0)); }

    void attach(Properties checkpoint, long now) {
        Map<String,Integer> targets = new LinkedHashMap<>();
        Set<String> combat = new LinkedHashSet<>();
        for (StatType type : StatType.values()) {
            String id = type.name().toLowerCase(Locale.ROOT);
            // Explicit owner decision: skillers exclude Slayer, not silently blocked.
            if (archetype != BuildPolicy.Archetype.SKILLER || type != StatType.Slayer) targets.put(id, 99);
            if (type.isCombat()) combat.add(id);
        }
        policy = BuildPolicy.create(archetype, combatStyle, targets, combat);
        int slot = World.players.add(this, 1);
        setIndex(slot);
        LoginInfo info = new LoginInfo(ArtificialChannel.getSingleton(), displayName, "", "", "", null, 0, false, 0, World.id, new int[4]);
        info.update(-100000 - Integer.parseInt(actorId), displayName, "", ListUtils.toList(PlayerGroup.REGISTERED.id), 0);
        init(info);
        Config.load(this);
        lastRegion = getPosition().getRegion();
        if (checkpoint == null && !normalStarterReceived) {
            io.ruin.model.entity.npc.actions.edgeville.StarterGuide.giveEcoStarter(this);
            normalStarterReceived = true;
        }
        newPlayer = false;
        xpMode = io.ruin.model.entity.player.XpMode.HARD;
        setOnline(true);
        start();
        port = new KronosActivityPort(this);
        safety = new AdventurerSafety(this);
        brain = new ProgressionBrain(actorId, port, ProgressionBrain.Config.humanDefaults(600), policy, seed);
        if (checkpoint != null) brain.restore(checkpoint, now);
        ready = checkpoint != null;
        saveAt = now + 30 + Integer.parseInt(actorId) * 3;
    }

    public boolean allowXp(StatType type) {
        if (quarantineReason != null || policy == null || policy.forbiddenXpSkills.contains(type.name().toLowerCase(Locale.ROOT))) {
            quarantine("Blocked XP reward: " + type.name());
            return false;
        }
        // A break is not permitted to hide a still-running XP job.
        if (brain != null && (brain.state() == ProgressionBrain.State.RESTING || brain.state() == ProgressionBrain.State.MAXED_IDLE || brain.state() == ProgressionBrain.State.FAULTED)) {
            quarantine("XP attempted while " + brain.state());
            return false;
        }
        return true;
    }

    void quarantine(String reason) {
        if (quarantineReason == null) quarantineReason = reason;
        if (!isLocked()) {
            resetActions(true, true, true);
            getPrayer().deactivateAll();
        }
    }

    @Override public void checkLogout() {
        if (logoutStage == -1) {
            setOnline(false);
            finish();
            World.players.remove(getIndex());
            Adventurers.detached(this);
        }
    }
}
