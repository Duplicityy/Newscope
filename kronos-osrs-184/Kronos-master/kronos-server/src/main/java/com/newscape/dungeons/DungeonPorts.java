package com.newscape.dungeons;

import java.util.*;
import com.newscape.dungeons.DungeonLayout.Tile;

/** The ONLY boundary that may touch Kronos. All calls except reward Store run on its world thread. */
public final class DungeonPorts {
    private DungeonPorts() { }
    public enum DamageKind { TRAP, MAGIC, RANGED, MELEE }
    public enum MotionKind { DOOR_WALK, AGILITY_CROSSING }

    public static final class Instance {
        public final String id;
        public final UUID runId;
        public Instance(String id, UUID runId) {
            this.id = Objects.requireNonNull(id); this.runId = Objects.requireNonNull(runId);
            if (id.isEmpty()) throw new IllegalArgumentException("empty instance identity");
        }
    }

    public static final class PlayerView {
        public final String accountId, instanceId;
        public final Tile tile;
        public final int plane, combatLevel, baseAgility, currentAgility, hp, maxHp;
        public final boolean online, busy;
        public PlayerView(String accountId, String instanceId, Tile tile, int combatLevel,
                int baseAgility, int currentAgility, int hp, int maxHp, boolean online, boolean busy) {
            this(accountId, instanceId, tile, 0, combatLevel, baseAgility, currentAgility, hp, maxHp, online, busy);
        }
        public PlayerView(String accountId, String instanceId, Tile tile, int plane, int combatLevel,
                int baseAgility, int currentAgility, int hp, int maxHp, boolean online, boolean busy) {
            this.accountId = Objects.requireNonNull(accountId); this.instanceId = instanceId;
            this.plane = plane;
            this.tile = tile; this.combatLevel = combatLevel; this.baseAgility = baseAgility;
            this.currentAgility = currentAgility; this.hp = hp; this.maxHp = maxHp;
            this.online = online; this.busy = busy;
            if (plane < 0 || plane > 3 || combatLevel < 3 || combatLevel > 126 || baseAgility < 1 || baseAgility > 99
                    || currentAgility < 0 || currentAgility > 125 || hp < 0 || maxHp < 1 || hp > maxHp)
                throw new IllegalArgumentException("invalid authoritative player stats");
        }
    }

    public static final class NpcStats {
        public final int hp, maxHit, attackTicks;
        public NpcStats(int hp, int maxHit, int attackTicks) {
            if (hp < 1 || maxHit < 0 || attackTicks < 1) throw new IllegalArgumentException("NPC stats");
            this.hp = hp; this.maxHit = maxHit; this.attackTicks = attackTicks;
        }
    }

    public static final class NpcTicket {
        public final UUID runId;
        public final String token, accountId, archetypeKey;
        public final int roomId;
        public final Tile tile;
        public final NpcStats stats;
        public final boolean gauntletGuard, boss;
        public NpcTicket(UUID runId, String token, String accountId, String archetypeKey,
                int roomId, Tile tile, NpcStats stats, boolean gauntletGuard, boolean boss) {
            this.runId = runId; this.token = token; this.accountId = accountId;
            this.archetypeKey = archetypeKey; this.roomId = roomId; this.tile = tile;
            this.stats = stats; this.gauntletGuard = gauntletGuard; this.boss = boss;
        }
    }

    public static final class Motion {
        public final UUID permit;
        public final MotionKind kind;
        public final List<Tile> path;
        public final long expiresAtTick;
        public Motion(UUID permit, MotionKind kind, List<Tile> path, long expiresAtTick) {
            this.permit = permit; this.kind = kind;
            this.path = Collections.unmodifiableList(new ArrayList<Tile>(path));
            this.expiresAtTick = expiresAtTick;
            if (path.size() < 2) throw new IllegalArgumentException("motion requires endpoints");
        }
    }

    public interface GamePort {
        /** Throw if called from anything other than the authoritative game/world thread. */
        void assertWorldThread();
        /** Immutable snapshot from server state, never from click packet coordinates. */
        PlayerView player(String accountId);
        /** Check actual registered portal, distance, plane, alive state, trades, locks and combat. null permits. */
        String entryDenial(String accountId, Rewards.Tier tier);
        /** Fail closed unless cache mappings, map/footprint/collision, boundary and safe-death hooks are verified. */
        String deploymentDenial();
        Instance reserve(UUID runId, String accountId, DungeonLayout layout);
        /** At most chunkBudget source chunks this call. true only when all terrain and clipping are ready. */
        boolean buildStep(Instance instance, DungeonLayout layout, int chunkBudget);
        /** Compare actual map clipping, doors, shortcut gaps, exit paths and NPC sizes to the blueprint. */
        String validateBuiltMap(Instance instance, DungeonLayout layout);
        /** Persist a safe return marker BEFORE entering; must survive restart/offline login. */
        void recordRecovery(String accountId, UUID runId, Instance instance);
        void enter(String accountId, Instance instance, Tile tile);
        /** Evacuate all occupants; offline owners need a persisted safe return. true only after confirmed empty. */
        boolean evacuate(Instance instance, String accountId);
        void recoverOrphanedLogin(String accountId);
        /** Destroy only a confirmed-empty lease. Idempotent for the exact lease/run identity. */
        void release(Instance instance);
        void spawnNpc(Instance instance, NpcTicket npc);
        void despawnNpc(Instance instance, String npcToken);
        int npcHp(Instance instance, String npcToken);
        /** Update both visual door object AND movement/projectile clipping. */
        void door(Instance instance, String doorId, Tile tile, boolean open);
        /** Script real walking (door) or agility animation/forced motion; lock manual movement until complete. */
        void startMotion(String accountId, Instance instance, Motion motion);
        /** Release only this completed motion's movement lock after confirmed arrival. */
        void finishMotion(String accountId, Instance instance, Motion motion);
        /** Request rollback; true only when safe-bank restoration and this lock's release are complete. */
        boolean cancelMotion(String accountId, Instance instance, Motion motion, Tile safeTile);
        /** Used while extraction commits. Block movement, combat, trade, teleport, item interaction. */
        void freeze(String accountId, UUID runId, boolean freeze);
        /** Durably record the exact extraction intent before dispatching its mailbox write. Idempotent. */
        void prepareExtraction(String accountId, String transactionId, Rewards.Bundle bundle);
        void message(String accountId, String text);
        /** Credits once per awardId in the same durable player save as XP. No generic unguarded addXp. */
        void awardAgilityXpOnce(String accountId, String awardId, int xp);
        /** Server combat hit; apply current protection rules and invoke safe dungeon death before vanilla death. */
        void hit(String accountId, int damage, DamageKind kind, String sourceId);
        void telegraph(Instance instance, String hazardId, List<Tile> tiles, DamageKind kind, long resolveTick);
        void clearTelegraph(Instance instance, String hazardId);
        void openedChest(Instance instance, int chestSlot, Tile tile);
        void audit(String event, String accountId, UUID runId, String detail);
    }
}
