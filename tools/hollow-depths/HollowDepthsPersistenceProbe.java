package io.ruin.model.activities.hollowdepths;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.newscape.dungeons.Rewards;
import com.sun.tools.attach.VirtualMachine;
import io.ruin.Server;
import io.ruin.api.utils.JsonUtils;
import io.ruin.model.World;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.item.Item;
import io.ruin.model.stat.Stat;
import io.ruin.model.stat.StatType;
import io.ruin.utility.OfflineMode;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Destructive fixture operations are restricted to explicitly marked isolated hdqa staging accounts. */
public final class HollowDepthsPersistenceProbe {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    public static void agentmain(String requestPath, Instrumentation instrumentation) throws Exception {
        check(Boolean.getBoolean("kronos.hollowDepths.test") && Boolean.getBoolean("kronos.adventurers.test"), "Staging flags required");
        check(Integer.getInteger("kronos.port", 0) == 14312 && "127.0.0.1".equals(System.getProperty("kronos.bind")), "Loopback staging port required");
        String stageName = System.getProperty("kronos.hollowDepths.stage", "");
        check(stageName.matches("hollow-depths-[a-z0-9-]+"), "Invalid staging identity");
        Path stage = Paths.get(System.getProperty("user.dir")).toRealPath();
        check(stage.getFileName().toString().equals(stageName), "Staging cwd identity mismatch");
        check(Paths.get(requestPath).toRealPath().equals(stage), "Requested staging identity mismatch");
        Path request = stage.resolve("persistence-probe-request.properties").toRealPath();
        check(request.getParent().equals(stage), "Probe request is outside staging");
        check(Paths.get(System.getProperty("kronos.playerData")).toRealPath().startsWith(stage), "Player data is outside staging");
        check(Paths.get(System.getProperty("kronos.accounts")).toRealPath().startsWith(stage), "Registry is outside staging");
        Properties config = new Properties();
        try (InputStream in = Files.newInputStream(request)) { config.load(in); }
        String account = config.getProperty("account", "");
        check(account.matches("hdqa[12]"), "Only disposable fixture accounts are permitted");
        String mode = config.getProperty("mode", "checks");
        check(Arrays.asList("checks", "prepare-restart", "verify-restart").contains(mode), "Unknown probe mode");
        Properties report = new Properties();
        report.setProperty("mode", mode); report.setProperty("account", account);
        report.setProperty("process", ManagementFactory.getRuntimeMXBean().getName());
        report.setProperty("stage", stageName);
        try {
            Server.worker.getExecutor().submit(() -> {
                try { run(stage, account, mode, report); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            }).get(60, TimeUnit.SECONDS);
            report.setProperty("result", "PASS");
        } catch (Throwable failure) {
            report.setProperty("result", "FAIL");
            report.setProperty("failure", failure.toString());
            throw failure;
        } finally {
            try (OutputStream out = Files.newOutputStream(stage.resolve("persistence-" + account + "-" + mode + ".properties"))) {
                report.store(out, "Actual 184 staging persistence verification; no production accounts touched");
            }
        }
        System.out.println("HOLLOW DEPTHS PERSISTENCE " + mode + " PASS account=" + account);
    }

    private static void run(Path stage, String name, String mode, Properties report) throws Exception {
        Player player = null;
        for (Player candidate : World.players) if (name.equalsIgnoreCase(candidate.getName())) { player = candidate; break; }
        check(player != null && player.isOnline() && player.getChannel() != null && player.getChannel().isActive(), "Disposable real client must be online");
        check(player.getUserId() >= 1000 && OfflineMode.hasStableAccountRegistry(), "Registered stable identity required");
        check(HollowDepths.rewards != null && HollowDepths.service != null, "Dungeon runtime must be initialized");
        check(!HollowDepths.inRun(player), "Finish the fixture dungeon before persistence tests");
        check(!PlayerFile.isSaveQuarantined(player), "Account is already quarantined");
        check(!player.isLocked() && !player.getCombat().isDead() && !player.getCombat().isDefending(17), "Fixture player must be idle and safe");
        HollowDepthsRewards.validateMappings();
        report.setProperty("resourceMappings", "39 verified against loaded 184 ItemDef and reciprocal notes");
        if (mode.equals("prepare-restart")) { prepareRestart(stage, player, report); return; }
        if (mode.equals("verify-restart")) { verifyRestart(stage, player, report); return; }
        checks(player, report);
    }

    private static Player reload(Player player) throws Exception {
        String json = new String(Files.readAllBytes(OfflineMode.playerSavePath(player.getName())), StandardCharsets.UTF_8);
        Player reloaded = PlayerFile.decode(json, Player.class);
        check(reloaded != null && reloaded.hollowDepths != null, "Saved account did not reload its receipt state");
        reloaded.hollowDepths.validate(HollowDepthsRewards.accountId(player));
        return reloaded;
    }

    private static Item[] copyInventory(Player player) {
        Item[] source = player.getInventory().getItems(), result = new Item[source.length];
        for (int i=0;i<source.length;i++) if(source[i]!=null)
            result[i]=new Item(source[i].getId(),source[i].getAmount(),source[i].copyOfAttributes());
        return result;
    }
    private static void restoreInventory(Player player, Item[] saved) {
        for (int i=0;i<saved.length;i++) player.getInventory().set(i,saved[i]);
    }

    private static void checks(Player player, Properties report) throws Exception {
        HollowDepthsRewards rewards = HollowDepths.rewards;
        String account = HollowDepthsRewards.accountId(player);
        Item[] original = copyInventory(player);
        Stat agility = player.getStats().get(StatType.Agility);
        double originalXp=agility.experience;
        int originalCurrent=agility.currentLevel, originalFixed=agility.fixedLevel;
        boolean originalXpLock=player.experienceLock;
        String bank=JsonUtils.GSON_EXPOSE.toJson(player.getBank());
        String equipment=JsonUtils.GSON_EXPOSE.toJson(player.getEquipment());
        try {
            String deposit="fixture:"+UUID.randomUUID();
            Rewards.Bundle bundle=Rewards.Bundle.of("COIN_VOUCHER",1337);
            long earnedBefore=rewards.balance(account).amount("COIN_VOUCHER");
            check(rewards.depositOnce(account,deposit,bundle)==Rewards.DepositResult.APPLIED,"Initial grant rejected");
            check(rewards.depositOnce(account,deposit,bundle)==Rewards.DepositResult.ALREADY_APPLIED,"Duplicate grant not recognized");
            check(rewards.balance(account).amount("COIN_VOUCHER")==earnedBefore+1337,"Duplicate grant changed earned balance");
            boolean conflict=false;
            try{rewards.depositOnce(account,deposit,Rewards.Bundle.of("COIN_VOUCHER",1338));}catch(IOException expected){conflict=true;}
            check(conflict,"Changed extraction payload was accepted");
            report.setProperty("mailboxIdempotence","PASS: same receipt no-op; changed payload rejected");

            player.getInventory().clear();
            check(player.getInventory().add(526,28)==28,"Could not fill fixture inventory");
            Rewards.Bundle available=rewards.available(player);
            check(rewards.redeem(player)==0,"Full inventory incorrectly claimed resources");
            check(rewards.available(player).equals(available),"Full inventory lost mailbox credits");
            report.setProperty("fullInventory","PASS: credits retained and no items dropped");

            player.getInventory().clear();
            player.getInventory().add(995,17);
            Field executorField=PlayerFile.class.getDeclaredField("SAVE_EXECUTOR"); executorField.setAccessible(true);
            ExecutorService saver=(ExecutorService)executorField.get(null);
            saver.submit(()->{try{Thread.sleep(200L);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
            PlayerFile.save(player,-1); // older detached snapshot must finish before the claim.
            long coinCredit=rewards.available(player).amount("COIN_VOUCHER");
            check(coinCredit>0&&coinCredit<Integer.MAX_VALUE-17,"Unexpected fixture credit size");
            check(rewards.redeem(player)>0,"Resource redemption failed");
            int expected=(int)coinCredit+17;
            check(player.getInventory().getAmount(995)==expected,"Redemption item count mismatch");
            Player loaded=reload(player);
            check(loaded.getInventory().getAmount(995)==expected,"Old queued save overwrote claimed items");
            check(loaded.hollowDepths.redeemed.equals(player.hollowDepths.redeemed),"Redeemed ledger did not commit with inventory");
            check(loaded.hollowDepths.redemptionReceipts.equals(player.hollowDepths.redemptionReceipts),"Claim receipt did not commit with inventory");
            int receiptCount=player.hollowDepths.redemptionReceipts.size();
            check(rewards.redeem(player)==0,"Duplicate collection granted more items");
            check(player.hollowDepths.redemptionReceipts.size()==receiptCount,"Empty collection created a claim receipt");
            report.setProperty("redemptionAndOrdering","PASS: older queued ordinary save, item+receipt reload and duplicate collection");

            String xpId=UUID.randomUUID()+":agility:0";
            player.experienceLock=false;
            double expectedXp=Math.min(Stat.MAX_XP,agility.experience+35);
            rewards.awardAgilityXpOnce(player,xpId,35);
            rewards.awardAgilityXpOnce(player,xpId,35);
            check(agility.experience==expectedXp,"Duplicate trap award changed XP");
            loaded=reload(player);
            check(loaded.getStats().get(StatType.Agility).experience==expectedXp,"XP did not survive reload");
            check(loaded.hollowDepths.xpReceipts.get(xpId)==35,"XP receipt did not survive reload");
            conflict=false;
            try{rewards.awardAgilityXpOnce(player,xpId,36);}catch(IllegalStateException expectedFailure){conflict=true;}
            check(conflict,"Changed XP payload accepted");
            report.setProperty("xpReceipt","PASS: XP+receipt reload, duplicate no-op, changed payload rejected");

            UUID run=UUID.randomUUID(); String extraction="dungeon:"+run+":extraction";
            rewards.recordRecovery(player,run,2043,3576,0);
            Rewards.Bundle extractionBundle=Rewards.Bundle.of("COIN_VOUCHER",911);
            rewards.prepareExtraction(player,extraction,extractionBundle);
            loaded=reload(player);
            check(extraction.equals(loaded.hollowDepths.pendingTransaction),"Outbox identity did not persist");
            check(loaded.hollowDepths.pendingRewards.equals(extractionBundle.amounts),"Complete outbox payload did not persist");
            long replayBefore=rewards.balance(account).amount("COIN_VOUCHER");
            rewards.depositOnce(account,extraction,extractionBundle); // simulate mailbox commit before player intent clear.
            rewards.recoverPendingExtraction(player);
            rewards.recoverPendingExtraction(player);
            check(rewards.balance(account).amount("COIN_VOUCHER")==replayBefore+911,"Replay duplicated committed extraction");
            HollowDepths.authorizedRelocation(player,()->rewards.recoverLogin(player));
            loaded=reload(player);
            check(loaded.hollowDepths.pendingTransaction==null&&loaded.hollowDepths.recoveryRunId==null,"Recovery state did not clear");
            check(loaded.getAbsX()==2043&&loaded.getAbsY()==3576&&loaded.getHeight()==0,"Recovered player save is not at verified lobby");
            report.setProperty("outboxReplay","PASS: persisted full intent, mailbox-committed replay once, durable lobby recovery (same process)");

            check(bank.equals(JsonUtils.GSON_EXPOSE.toJson(player.getBank())),"Unrelated bank data changed");
            check(equipment.equals(JsonUtils.GSON_EXPOSE.toJson(player.getEquipment())),"Unrelated equipment data changed");
            report.setProperty("unrelatedState","PASS: bank and equipped items preserved");
            report.setProperty("maxDurableSaveMillis",Long.toString(PlayerFile.maximumDurableSaveMillis));
        } finally {
            if(!PlayerFile.isSaveQuarantined(player)) {
                // Only disposable fixture inventory/XP are restored; legitimate durable receipts stay.
                restoreInventory(player,original);
                agility.experience=originalXp;agility.currentLevel=originalCurrent;agility.fixedLevel=originalFixed;agility.updated=true;
                player.experienceLock=originalXpLock;
                PlayerFile.saveDurably(player);
            }
        }
    }

    private static Path restartProof(Path stage,Player player) { return stage.resolve("persistence-restart-"+player.getName().toLowerCase(Locale.ROOT)+".properties"); }

    private static void prepareRestart(Path stage,Player player,Properties report) throws Exception {
        HollowDepthsRewards rewards=HollowDepths.rewards;
        check(player.hollowDepths==null||player.hollowDepths.pendingTransaction==null,"Existing pending intent must be resolved first");
        UUID run=UUID.randomUUID();String transaction="dungeon:"+run+":extraction";
        String account=HollowDepthsRewards.accountId(player);
        long earned=rewards.balance(account).amount("COIN_VOUCHER");
        rewards.recordRecovery(player,run,2043,3576,0);
        rewards.prepareExtraction(player,transaction,Rewards.Bundle.of("COIN_VOUCHER",2711));
        Player loaded=reload(player);
        check(transaction.equals(loaded.hollowDepths.pendingTransaction),"Restart intent not persisted");
        check(rewards.balance(account).amount("COIN_VOUCHER")==earned,"Preparation prematurely delivered mailbox credit");
        Properties proof=new Properties();
        proof.setProperty("process",ManagementFactory.getRuntimeMXBean().getName());
        proof.setProperty("account",account);proof.setProperty("transaction",transaction);proof.setProperty("earnedBefore",Long.toString(earned));
        proof.setProperty("inventoryCoinsBefore",Integer.toString(player.getInventory().getAmount(995)));
        try(OutputStream out=Files.newOutputStream(restartProof(stage,player))){proof.store(out,"Restart this isolated server, log the same account back in, then verify-restart");}
        report.setProperty("restartIntent","PASS: 2711 coins recorded only in durable outbox; actual restart still required");
    }

    private static void verifyRestart(Path stage,Player player,Properties report) throws Exception {
        Properties proof=new Properties();try(InputStream in=Files.newInputStream(restartProof(stage,player))){proof.load(in);}
        check(!ManagementFactory.getRuntimeMXBean().getName().equals(proof.getProperty("process")),"A genuinely different game process is required");
        String account=HollowDepthsRewards.accountId(player);
        check(account.equals(proof.getProperty("account")),"Stable account ID changed across restart");
        long expected=Long.parseLong(proof.getProperty("earnedBefore"))+2711;
        check(HollowDepths.rewards.balance(account).amount("COIN_VOUCHER")==expected,"Restart did not replay extraction exactly once");
        Player loaded=reload(player);
        check(loaded.hollowDepths.pendingTransaction==null&&loaded.hollowDepths.recoveryRunId==null,"Login did not durably finish recovery");
        check(loaded.getAbsX()==2043&&loaded.getAbsY()==3576&&loaded.getHeight()==0,"Restart recovered to wrong lobby");
        check(player.getInventory().getAmount(995)==Integer.parseInt(proof.getProperty("inventoryCoinsBefore")),"Mailbox replay improperly inserted physical coins");
        check(HollowDepths.rewards.depositOnce(account,proof.getProperty("transaction"),Rewards.Bundle.of("COIN_VOUCHER",2711))==Rewards.DepositResult.ALREADY_APPLIED,"Restart receipt was lost");
        report.setProperty("actualRestartOutbox","PASS: different JVM, stable account, exact grant once, intent cleared and lobby saved");
    }

    public static void main(String[] args) throws Exception {
        VirtualMachine vm=VirtualMachine.attach(args[0]);
        try{vm.loadAgent(args[1],args[2]);}finally{vm.detach();}
    }
}
