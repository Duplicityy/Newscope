package local.kronos.mobile;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Serial ART-to-HotSpot requests. The Android UI thread never attaches to the game JVM. */
final class PluginBridgeClient {
    interface Reply { void received(JSONObject response); }
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(24), task -> {
                Thread thread = new Thread(task, "newscape-plugin-bridge");
                thread.setDaemon(true);
                return thread;
            });
    private volatile boolean closed;

    void request(JSONObject request, Reply reply) {
        if (closed) return;
        final String body = request.toString();
        try {
            worker.execute(() -> {
                JSONObject result;
                try {
                    String response = MainActivity.pluginRequest(body);
                    result = response == null ? error("The plugin bridge returned no response.") : new JSONObject(response);
                } catch (Exception | LinkageError failure) {
                    result = error("Plugin controls are not ready: " + failure.getClass().getSimpleName());
                }
                final JSONObject response = result;
                if (reply != null && !closed) ui.post(() -> { if (!closed) reply.received(response); });
            });
        } catch (RejectedExecutionException busy) {
            if (reply != null && !closed) ui.post(() -> { if (!closed) reply.received(error("Plugin controls are busy. Try again.")); });
        }
    }

    void flush() { request(op("flush"), null); }

    void close() {
        if (closed) return;
        // Keep the already queued flush ahead of shutdown; never wait for native code on the UI thread.
        flush();
        closed = true;
        ui.removeCallbacksAndMessages(null);
        worker.shutdown();
    }

    static JSONObject op(String operation) {
        JSONObject object = new JSONObject();
        put(object, "op", operation);
        return object;
    }
    static JSONObject error(String text) {
        JSONObject object = new JSONObject();
        put(object, "ok", false);
        put(object, "error", text);
        return object;
    }
    static JSONObject put(JSONObject object, String key, Object value) {
        try { object.put(key, value); }
        catch (org.json.JSONException impossible) { throw new IllegalArgumentException(key, impossible); }
        return object;
    }
}
