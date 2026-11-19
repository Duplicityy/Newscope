package net.runelite.standalone;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs against the selected embedded JARs without opening a client or a socket. */
public final class OwnerAutomationTransportChecks {
    private static final String RESPONSE = "{\"status\":\"Ready\",\"ok\":true,\"skills\":[\"Mining\"]}";
    private static final int[] SEED = {1, 2, 3, 4};

    public static void main(String[] args) {
        try {
            runChecks();
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void runChecks() throws Exception {
        check(OwnerAutomationClient.valid("start woodcutting 99"), "skill goal");
        check(OwnerAutomationClient.valid("boss vorkath 10"), "boss goal");
        check(OwnerAutomationClient.valid("dungeonopen"), "native dungeon settings");
        check(OwnerAutomationClient.valid("slayerconfig 8623 hard"), "persist master and tier");
        check(OwnerAutomationClient.valid("slayerstart 6797 medium"), "native Slayer start with settings");
        check(!OwnerAutomationClient.valid("slayerstart 6797 boss"), "native unsupported Slayer tier");
        check(!OwnerAutomationClient.valid("start mining 99;giveitem"), "malformed command");
        check(!OwnerAutomationClient.valid("enable mining true\nabc"), "multi-line command");
        check(!OwnerAutomationClient.valid("boss unregistered 1"), "unknown boss");

        Field field = OwnerAutomationClient.class.getDeclaredField("PENDING");
        field.setAccessible(true);
        AtomicReference<?> pending = (AtomicReference<?>) field.get(null);
        ExecutorService panel = Executors.newSingleThreadExecutor();
        try {
            MobileFrame.foreground(false);
            checkBackgroundDispatch(panel, pending);
            checkLoginRequired(panel, pending);
            checkNoLateDispatch(panel, pending);
            checkDelayedReply(panel, pending);
        } finally {
            panel.shutdownNow();
        }
        System.out.println("PASS background game update, native command and reply, duplicate suppression, "
                + "login guard, and both timeout paths");
    }

    private static void checkBackgroundDispatch(ExecutorService panel, AtomicReference<?> pending) throws Exception {
        resetWriter(25);
        Future<String> request = request(panel, pending, "enable mining false");
        MobileFrame.afterLogic();
        check(Client.packetWriter.bufferSize == 0 && !request.isDone(), "wait for region load");
        Client.gameState = 30;
        check(!MobileFrame.foreground(), "game drawing must be suspended");
        MobileFrame.afterLogic();
        String nonce = packetNonce("enable mining false");
        int sentBytes = Client.packetWriter.bufferSize;
        MobileFrame.afterLogic();
        MobileFrame.afterLogic();
        check(Client.packetWriter.bufferSize == sentBytes, "one packet per toggle");

        String encoded = encode(RESPONSE);
        reply(nonce, "1/2", encoded.substring(12));
        reply(nonce, "1/2", encoded.substring(12));
        check(!request.isDone(), "duplicate chunk cannot complete a reply");
        reply(nonce, "0/2", encoded.substring(0, 12));
        check(RESPONSE.equals(request.get(1, TimeUnit.SECONDS)), "reply through the patched game-message entry");
        check(!OwnerAutomationClient.receive(2, "", "ordinary chat", ""), "normal chat passes through");
        check(!OwnerAutomationClient.receive(0, null, "NewscapeAutomation announcement", ""), "ordinary system message passes through");
    }

    private static void checkLoginRequired(ExecutorService panel, AtomicReference<?> pending) throws Exception {
        resetWriter(10);
        Future<String> request = request(panel, pending, "status");
        MobileFrame.afterLogic();
        check(request.get(1, TimeUnit.SECONDS).contains("\"code\":\"sign_in\""), "signed-out status");
        check(Client.packetWriter.bufferSize == 0, "signed-out client sends nothing");
    }

    private static void checkNoLateDispatch(ExecutorService panel, AtomicReference<?> pending) throws Exception {
        resetWriter(30);
        Future<String> request = request(panel, pending, "start mining 99");
        check(request.get(6, TimeUnit.SECONDS).contains("\"code\":\"not_sent\""), "timeout before game update");
        MobileFrame.afterLogic();
        check(Client.packetWriter.bufferSize == 0, "an expired start cannot execute when updates resume");
    }

    private static void checkDelayedReply(ExecutorService panel, AtomicReference<?> pending) throws Exception {
        resetWriter(30);
        Future<String> outgoing = request(panel, pending, "start mining 99");
        MobileFrame.afterLogic();
        String expiredNonce = packetNonce("start mining 99");
        check(outgoing.get(6, TimeUnit.SECONDS).contains("\"code\":\"reply_timeout\""), "timeout after queuing a packet");
        int sentBytes = Client.packetWriter.bufferSize;
        MobileFrame.afterLogic();
        check(Client.packetWriter.bufferSize == sentBytes, "a queued action is never resent");

        resetWriter(30);
        Future<String> refresh = request(panel, pending, "status");
        MobileFrame.afterLogic();
        String refreshNonce = packetNonce("status");
        check(!expiredNonce.equals(refreshNonce), "new request gets a new nonce");
        reply(expiredNonce, "0/1", encode("{\"status\":\"Old reply\"}"));
        check(!refresh.isDone(), "late response is swallowed and cannot resolve Refresh");
        reply(refreshNonce, "0/1", encode(RESPONSE));
        check(RESPONSE.equals(refresh.get(1, TimeUnit.SECONDS)), "Refresh succeeds after a timeout");

        Future<String> invalid = request(panel, pending, "status");
        resetWriter(30);
        MobileFrame.afterLogic();
        reply(packetNonce("status"), "0/99", "abc");
        check(invalid.get(1, TimeUnit.SECONDS).contains("\"code\":\"invalid_reply\""), "oversized chunk list");
    }

    private static void resetWriter(int state) {
        Client.gameState = state;
        Client.packetWriter.method1618();
        Client.packetWriter.isaacCipher = new IsaacCipher(SEED);
    }

    private static Future<String> request(ExecutorService panel, AtomicReference<?> pending, String action)
            throws InterruptedException {
        Future<String> result = panel.submit(() -> OwnerAutomationClient.request(
                "{\"op\":\"automation\",\"action\":\"" + action + "\"}"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (pending.get() == null && !result.isDone() && System.nanoTime() - deadline < 0) {
            Thread.sleep(2);
        }
        check(pending.get() != null, "panel worker published request");
        return result;
    }

    private static String packetNonce(String action) {
        PacketBufferNode packet = (PacketBufferNode) Client.packetWriter.packetBufferNodes.method5044();
        check(packet != null && Client.packetWriter.bufferSize == packet.index, "one native game command");
        byte[] bytes = packet.packetBuffer.array;
        int opcode = (bytes[0] - new IsaacCipher(SEED).method6223()) & 255;
        check(opcode == 17 && (bytes[1] & 255) == packet.index - 2 && bytes[packet.index - 1] == 0,
                "native game command opcode and framing");
        String command = new String(bytes, 2, packet.index - 3, StandardCharsets.US_ASCII);
        check(command.matches("bot mobile [a-f0-9]{24} " + action), "native command payload");
        return command.split(" ")[2];
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(String nonce, String sequence, String data) {
        WorldMapData_1.method514(0, null, "NewscapeAutomation " + nonce + " " + sequence + " " + data, "");
    }

    private static void check(boolean result, String scenario) {
        if (!result) throw new AssertionError(scenario);
    }
}
