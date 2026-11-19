package net.runelite.standalone;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local Android bridge. Normal authenticated client packets carry actions and snapshots. */
public final class OwnerAutomationClient {
    private static final String PREFIX = "NewscapeAutomation ";
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(4);
    private static final Pattern RESPONSE = Pattern.compile(PREFIX
            + "([a-f0-9]{24}) ([0-9]{1,2})/([0-9]{1,2}) ([a-zA-Z0-9_-]{1,160})");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicReference<Request> PENDING = new AtomicReference<>();

    private OwnerAutomationClient() {}

    /** Returns null for all requests owned by the existing plugin bridge. */
    public static String request(String json) {
        JsonObject data;
        try { data = new JsonParser().parse(json).getAsJsonObject(); }
        catch (RuntimeException malformed) { return null; }
        if (!data.has("op") || !"automation".equals(data.get("op").getAsString())) return null;
        if (!data.has("action")) return error("invalid_action", "Missing action");
        String action = data.get("action").getAsString();
        if (!valid(action)) return error("invalid_action", "Invalid automation action");
        Request request = new Request(nonce(), action, System.nanoTime() + TIMEOUT_NANOS);
        if (!PENDING.compareAndSet(null, request))
            return error("busy", "Another automation action is pending");
        try {
            return request.result.get(TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return error("cancelled", "Automation request cancelled");
        } catch (TimeoutException timeout) {
            return request.timeout();
        } catch (ExecutionException failure) {
            return error("invalid_reply", "The automation reply could not be read.");
        } finally {
            request.cancel();
            PENDING.compareAndSet(request, null);
        }
    }

    /** Called by the game update loop even when Android suspends drawing. */
    public static void pump() {
        RaidClickIndicator.pump();
        Request request = PENDING.get();
        if (request != null) request.dispatch();
    }

    static boolean valid(String action) {
        return action.matches("status|pause|resume|stop|raidstart|raidstop|dungeonopen|dungeonstop|master (true|false)"
                + "|enable [a-z]{3,20} (true|false)|start [a-z]{3,20} [0-9]{1,2}"
                + "|slayer(?:config|start) [0-9]{1,5} (easy|medium|hard)"
                + "|progress [0-9]{1,2}|boss (smokedevil|vorkath|zulrah) (1|5|10)");
    }

    /** Called before the chatbox receives a message, on the same client thread. */
    public static boolean receive(int type, String sender, String text, String unused) {
        if (RaidClickIndicator.receive(type, sender, text)) return true;
        if (type != 0 || sender != null && !sender.isEmpty() || text == null || !text.startsWith(PREFIX))
            return false;
        Matcher reply = RESPONSE.matcher(text);
        if (!reply.matches()) return false;
        Request request = PENDING.get();
        if (request != null && request.nonce.equals(reply.group(1))) {
            request.part(Integer.parseInt(reply.group(2)), Integer.parseInt(reply.group(3)), reply.group(4));
        }
        // A delayed reply may outlive the panel request. Keep protocol messages out of chat.
        return true;
    }

    private static String nonce() {
        byte[] random = new byte[12];
        RANDOM.nextBytes(random);
        char[] token = new char[random.length * 2];
        String hex = "0123456789abcdef";
        for (int i = 0; i < random.length; i++) {
            token[i * 2] = hex.charAt((random[i] & 255) >>> 4);
            token[i * 2 + 1] = hex.charAt(random[i] & 15);
        }
        return new String(token);
    }

    private static String error(String code, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", false);
        json.addProperty("code", code);
        json.addProperty("error", message);
        return json.toString();
    }

    static final class Request {
        final String nonce;
        private final String action;
        private final long deadline;
        final CompletableFuture<String> result = new CompletableFuture<>();
        private boolean sent;
        private String[] chunks;
        private int received;

        Request(String nonce, String action, long deadline) {
            this.nonce = nonce;
            this.action = action;
            this.deadline = deadline;
        }

        synchronized void dispatch() {
            if (sent || result.isDone() || System.nanoTime() - deadline >= 0) return;
            int state = Client.gameState;
            if (state == 20 || state == 25 || state == 40 || state == 45) return;
            if (state != 30 || Client.packetWriter == null || Client.packetWriter.isaacCipher == null) {
                result.complete(error("sign_in", "Sign in to the private owner server to use automation."));
                return;
            }
            try {
                if ("raidstart".equals(action)) WorldMapSection0.method3906("raid auto feedback");
                WorldMapSection0.method3906("bot mobile " + nonce + " " + action);
                sent = true;
            } catch (RuntimeException failure) {
                result.complete(error("send_failed", "The game client could not queue the automation request."));
            }
        }

        synchronized String timeout() {
            result.complete(sent
                    ? error("reply_timeout", "The server reply took too long.")
                    : error("not_sent", "The game client did not send the request in time."));
            return result.join();
        }

        synchronized void cancel() {
            if (!result.isDone()) result.cancel(false);
        }

        void part(int index, int total, String data) {
            if (!sent || result.isDone()) return;
            try {
                if (total < 1 || total > 24 || index < 0 || index >= total)
                    throw new IllegalArgumentException("Invalid chunk");
                if (chunks == null) chunks = new String[total];
                if (chunks.length != total) throw new IllegalArgumentException("Inconsistent chunks");
                if (chunks[index] != null) return;
                chunks[index] = data;
                if (++received == total) {
                    byte[] bytes = Base64.getUrlDecoder().decode(String.join("", chunks));
                    result.complete(new String(bytes, StandardCharsets.UTF_8));
                }
            } catch (RuntimeException failure) {
                result.complete(error("invalid_reply", "The automation reply could not be read."));
            }
        }
    }
}
