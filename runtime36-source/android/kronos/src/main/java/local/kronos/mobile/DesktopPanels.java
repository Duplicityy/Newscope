package local.kronos.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Native touch viewer for the actual installed desktop plugin sidebar panels. */
final class DesktopPanels {
    private static final int BACKGROUND = 0xff101a1e, TEXT = 0xffe9eee7, MUTED = 0xffa8bec0, GOLD = 0xffdfbd7c;
    private final Activity activity;
    private final PluginBridgeClient bridge = new PluginBridgeClient();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String viewer = UUID.randomUUID().toString();
    private final ThreadPoolExecutor decoder = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), task -> { Thread thread = new Thread(task, "newscape-panel-image"); thread.setDaemon(true); return thread; });
    private final List<JSONObject> catalogue = new ArrayList<>();
    private final Runnable poll = this::refresh;
    private Dialog dialog;
    private AlertDialog fieldDialog;
    private TextView status, panelName;
    private ImageView image;
    private CheckBox internalScroll;
    private ScrollView scroll;
    private Bitmap bitmap;
    private String selected = "", session = "";
    private long frame, lastTap;
    private float downX, downY, lastTapX, lastTapY;
    private int sourceWidth, sourceHeight, generation;
    private boolean closed, paused, inFlight, dragging, fieldOpen;

    DesktopPanels(Activity activity) { this.activity = activity; }

    void show() {
        if (closed || activity.isFinishing() || activity.isDestroyed()) return;
        if (showing()) { loadList(true); return; }
        generation++; paused = false;
        dialog = new Dialog(activity); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = column(); root.setPadding(dp(12), dp(6), dp(12), dp(6)); root.setBackgroundColor(BACKGROUND);
        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = column(); titles.addView(label("Desktop plugin panels", 18, TEXT, true));
        titles.addView(label("The same sidebar tools used by your desktop client", 11, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1)); header.addView(button("Back to game", () -> dialog.dismiss())); root.addView(header);
        LinearLayout toolbar = row(); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(button("Choose panel", this::choosePanel));
        panelName = label("Loading available panels…", 13, GOLD, true); panelName.setPadding(dp(12), 0, dp(8), 0);
        toolbar.addView(panelName, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(button("Refresh", () -> { if (session.isEmpty()) loadList(true); else refresh(); })); root.addView(toolbar);
        internalScroll = new CheckBox(activity); internalScroll.setText("Scroll within panel"); internalScroll.setTextSize(12); internalScroll.setMinHeight(dp(48)); internalScroll.setTextColor(MUTED);
        internalScroll.setButtonTintList(android.content.res.ColorStateList.valueOf(GOLD)); root.addView(internalScroll);
        scroll = new ScrollView(activity); scroll.setFillViewport(false); scroll.setBackgroundColor(0xff242424);
        image = new ImageView(activity); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_START);
        image.setContentDescription("Actual desktop plugin panel. Tap controls to interact; drag to scroll."); image.setOnTouchListener(this::touch);
        ImageView panelImage = image;
        scroll.addView(panelImage, new ScrollView.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll.addOnLayoutChangeListener((viewer, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int panelWidth = Math.min(dp(360), right - left - viewer.getPaddingLeft() - viewer.getPaddingRight());
            ViewGroup.LayoutParams params = panelImage.getLayoutParams();
            if (panelWidth > 0 && params.width != panelWidth) {
                params.width = panelWidth;
                panelImage.setLayoutParams(params);
            }
        });
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        status = label("Connecting to desktop plugins…", 11, MUTED, false); status.setPadding(dp(3), dp(5), dp(3), 0); root.addView(status);
        dialog.setContentView(root);
        dialog.setOnDismissListener(ignored -> {
            generation++; handler.removeCallbacks(poll); dismissField(); closeSession(); inFlight = false;
            image.setImageDrawable(null); bitmap = null;
        });
        Window window = dialog.getWindow();
        if (window != null) { window.setBackgroundDrawableResource(android.R.color.transparent); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); }
        dialog.show();
        if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        loadList(true);
    }

    private void loadList(boolean choose) {
        if (!showing() || paused || inFlight) return;
        final int expected = generation; inFlight = true;
        bridge.request(PluginBridgeClient.op("panelList"), reply -> {
            if (expected != generation || !showing()) return;
            inFlight = false;
            if (!reply.optBoolean("ok")) { status.setText(reply.optString("error", "Desktop panels are unavailable.")); return; }
            catalogue.clear(); JSONArray panels = reply.optJSONArray("panels");
            if (panels != null) for (int index = 0; index < panels.length(); index++) { JSONObject panel = panels.optJSONObject(index); if (panel != null) catalogue.add(panel); }
            if (catalogue.isEmpty()) {
                panelName.setText("No enabled plugin panels"); status.setText("Enable a desktop plugin with a sidebar panel in RuneLite plugins, then refresh."); return;
            }
            status.setText("Choose a panel. All displayed controls belong to the actual desktop plugin.");
            JSONObject previous = find(selected);
            if (previous != null) open(previous);
            else if (choose) choosePanel();
        });
    }

    private void choosePanel() {
        if (!showing() || paused) return;
        if (catalogue.isEmpty()) { loadList(true); return; }
        String[] names = new String[catalogue.size()];
        for (int index = 0; index < names.length; index++) names[index] = catalogue.get(index).optString("name", "Plugin panel");
        new AlertDialog.Builder(activity).setTitle("Enabled desktop panels").setItems(names, (dialog, which) -> open(catalogue.get(which)))
                .setNegativeButton("Cancel", null).show();
    }

    private void open(JSONObject panel) {
        if (!showing() || paused) return;
        generation++; final int expected = generation;
        handler.removeCallbacks(poll); dismissField(); closeSession();
        selected = panel.optString("id"); panelName.setText(panel.optString("name", "Plugin panel"));
        image.setImageDrawable(null); bitmap = null; sourceWidth = sourceHeight = 0; frame = 0; inFlight = true;
        status.setText("Opening the actual desktop panel…");
        JSONObject request = PluginBridgeClient.put(PluginBridgeClient.op("panelOpen"), "panel", selected);
        PluginBridgeClient.put(request, "viewer", viewer);
        bridge.request(request, reply -> {
            if (expected != generation || !showing() || paused) {
                String abandoned = reply.optString("session");
                if (!abandoned.isEmpty()) closeRemote(abandoned);
                return;
            }
            session = reply.optString("session");
            receive(reply, expected);
        });
    }

    private void refresh() {
        if (!showing() || paused || inFlight || dragging || fieldOpen || session.isEmpty()) return;
        handler.removeCallbacks(poll); inFlight = true; final int expected = generation;
        JSONObject request = sessionRequest("panelFrame");
        bridge.request(request, reply -> { if (expected == generation && showing() && !paused) receive(reply, expected); });
    }

    private JSONObject sessionRequest(String op) {
        JSONObject request = PluginBridgeClient.put(PluginBridgeClient.op(op), "session", session);
        PluginBridgeClient.put(request, "frame", frame); return request;
    }

    private void receive(JSONObject reply, int expected) {
        if (expected != generation || !showing() || paused) return;
        String problem = reply.optBoolean("ok") ? "" : reply.optString("error", "The panel request failed.");
        JSONObject focus = reply.optJSONObject("focus");
        String encoded = reply.optString("png");
        if (!encoded.isEmpty()) {
            int width = reply.optInt("width"), height = reply.optInt("height");
            if (encoded.length() > 4 * 1024 * 1024 || width < 1 || width > 512 || height < 1 || height > 4096) {
                inFlight = false; status.setText("The desktop panel image exceeds the mobile viewer limits."); return;
            }
            try {
                decoder.execute(() -> {
                    Bitmap decoded = null; String failure = null;
                    try {
                        byte[] png = Base64.decode(encoded, Base64.DEFAULT);
                        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
                        if (bounds.outWidth != width || bounds.outHeight != height) throw new IllegalArgumentException("Panel image dimensions do not match.");
                        decoded = BitmapFactory.decodeByteArray(png, 0, png.length);
                        if (decoded == null) throw new IllegalArgumentException("The desktop panel image could not be decoded.");
                        decoded.setDensity(Bitmap.DENSITY_NONE);
                    } catch (RuntimeException | OutOfMemoryError error) { failure = "The desktop panel image could not be loaded."; }
                    final Bitmap loaded = decoded; final String imageError = failure;
                    handler.post(() -> {
                        if (closed || expected != generation || !showing() || paused) return;
                        inFlight = false;
                        if (imageError != null) { status.setText(imageError); schedule(); return; }
                        bitmap = loaded; image.setImageBitmap(bitmap); sourceWidth = width; sourceHeight = height;
                        frame = reply.optLong("frame");
                        if (!reply.optString("session").isEmpty()) session = reply.optString("session");
                        status.setText(!problem.isEmpty() ? problem : reply.optString("warning", "Tap to use this desktop panel. Drag to scroll."));
                        if (focus != null && problem.isEmpty()) edit(focus); else schedule();
                    });
                });
            } catch (RejectedExecutionException busy) { inFlight = false; status.setText("The panel image is busy. Refresh shortly."); schedule(); }
        } else {
            inFlight = false;
            if (!problem.isEmpty()) status.setText(problem);
            else if (reply.has("warning")) status.setText(reply.optString("warning"));
            if (focus != null && problem.isEmpty()) edit(focus); else schedule();
        }
    }

    private void schedule() {
        handler.removeCallbacks(poll);
        if (showing() && !paused && !fieldOpen && !session.isEmpty()) handler.postDelayed(poll, 850);
    }

    private boolean touch(View target, MotionEvent event) {
        if (inFlight || fieldOpen || bitmap == null || session.isEmpty() || paused) { dragging = false; return true; }
        if (event.getPointerCount() > 1 || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) { dragging = false; schedule(); return true; }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY(); dragging = true; handler.removeCallbacks(poll);
            if (internalScroll.isChecked()) target.getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) { dragging = false; schedule(); return true; }
        if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
        if (!dragging) return true;
        dragging = false;
        float dx = event.getX() - downX, dy = event.getY() - downY;
        boolean moved = dx * dx + dy * dy > dp(10) * dp(10);
        if (moved && !internalScroll.isChecked()) { schedule(); return true; }
        float[] point = { moved ? downX : event.getX(), moved ? downY : event.getY() };
        Matrix inverse = new Matrix();
        if (!image.getImageMatrix().invert(inverse)) { schedule(); return true; }
        point[0] -= image.getPaddingLeft(); point[1] -= image.getPaddingTop(); inverse.mapPoints(point);
        if (point[0] < 0 || point[1] < 0 || point[0] >= sourceWidth || point[1] >= sourceHeight) { schedule(); return true; }
        int x = (int)point[0], y = (int)point[1];
        JSONObject request = sessionRequest("panelTouch"); PluginBridgeClient.put(request, "x", x); PluginBridgeClient.put(request, "y", y);
        PluginBridgeClient.put(request, "action", moved ? "wheel" : "tap");
        if (moved) PluginBridgeClient.put(request, "rotation", Math.max(-20, Math.min(20, Math.round(-dy / Math.max(1, dp(35))))));
        else {
            long now = SystemClock.uptimeMillis();
            boolean twice = now - lastTap < 320 && Math.abs(lastTapX - event.getX()) < dp(18) && Math.abs(lastTapY - event.getY()) < dp(18);
            PluginBridgeClient.put(request, "clicks", twice ? 2 : 1);
            lastTap = twice ? 0 : now; lastTapX = event.getX(); lastTapY = event.getY();
        }
        sendInput(request); return true;
    }

    private void sendInput(JSONObject request) {
        if (!showing() || paused || inFlight || session.isEmpty()) return;
        handler.removeCallbacks(poll); inFlight = true; final int expected = generation;
        bridge.request(request, reply -> { if (expected == generation && showing() && !paused) receive(reply, expected); });
    }

    private void edit(JSONObject focus) {
        fieldOpen = true; handler.removeCallbacks(poll);
        final int expected = generation;
        String type = focus.optString("type");
        if (type.equals("choice")) {
            JSONArray options = focus.optJSONArray("options");
            if (options == null) { fieldOpen = false; status.setText("This desktop choice has no available options."); schedule(); return; }
            String[] labels = new String[options.length()]; for (int index = 0; index < labels.length; index++) labels[index] = options.optString(index);
            fieldDialog = new AlertDialog.Builder(activity).setTitle("Desktop plugin choice")
                    .setSingleChoiceItems(labels, focus.optInt("selected", -1), (dialog, which) -> {
                        JSONObject request = sessionRequest("panelChoice"); PluginBridgeClient.put(request, "focus", focus.optString("token")); PluginBridgeClient.put(request, "index", which);
                        dialog.dismiss(); if (expected == generation) sendInput(request);
                    }).setNegativeButton("Cancel", null).create();
        } else if (type.equals("text") || type.equals("password")) {
            boolean secret = type.equals("password"), readOnly = focus.optBoolean("readOnly"), multiline = focus.optBoolean("multiline");
            EditText input = new EditText(activity); input.setTextSize(12); input.setMinHeight(dp(48)); input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8192)});
            input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); input.setSaveEnabled(false);
            input.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES : InputType.TYPE_TEXT_VARIATION_NORMAL));
            input.setSingleLine(!multiline); input.setMinLines(multiline ? 3 : 1); input.setMaxLines(multiline ? 9 : 1);
            if (secret) input.setHint("New value — current value is hidden"); else input.setText(focus.optString("value"));
            input.setEnabled(!readOnly);
            LinearLayout holder = column(); holder.setPadding(dp(20), dp(8), dp(20), 0); holder.addView(input);
            AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(readOnly ? "Desktop text (read only)" : secret ? "Desktop password" : "Desktop plugin text")
                    .setView(holder).setNegativeButton("Cancel", (dialog, which) -> input.setText(""));
            if (!readOnly) builder.setPositiveButton(multiline ? "Save" : "Apply / Enter", (dialog, which) -> {
                JSONObject request = sessionRequest("panelText"); PluginBridgeClient.put(request, "focus", focus.optString("token"));
                PluginBridgeClient.put(request, "text", input.getText().toString()); PluginBridgeClient.put(request, "submit", !multiline); input.setText("");
                if (expected == generation) sendInput(request);
            });
            fieldDialog = builder.create();
            fieldDialog.setOnShowListener(ignored -> {
                if (!readOnly) { input.requestFocus(); Window window = fieldDialog.getWindow(); if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); }
            });
        } else { fieldOpen = false; status.setText("This desktop control cannot be edited in the mobile viewer."); schedule(); return; }
        fieldDialog.setOnDismissListener(ignored -> { fieldOpen = false; fieldDialog = null; if (expected == generation) schedule(); });
        fieldDialog.show();
    }

    private JSONObject find(String id) { for (JSONObject panel : catalogue) if (panel.optString("id").equals(id)) return panel; return null; }
    private void closeRemote(String id) {
        JSONObject request = PluginBridgeClient.put(PluginBridgeClient.op("panelClose"), "session", id);
        PluginBridgeClient.put(request, "viewer", viewer); bridge.request(request, null);
    }
    private void closeSession() { closeRemote(session); session = ""; frame = 0; }
    private void dismissField() { if (fieldDialog != null) { fieldDialog.dismiss(); fieldDialog = null; } fieldOpen = false; }
    void pause() { paused = true; generation++; handler.removeCallbacks(poll); dismissField(); closeSession(); inFlight = false; dragging = false; }
    void resume() { if (closed) return; paused = false; if (showing()) { JSONObject previous = find(selected); if (previous != null) open(previous); else loadList(true); } }
    void close() {
        if (closed) return;
        pause(); if (dialog != null) dialog.dismiss(); closed = true; handler.removeCallbacksAndMessages(null); decoder.shutdownNow(); bridge.close(); bitmap = null;
    }
    private boolean showing() { return !closed && dialog != null && dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed(); }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private TextView label(String text, int size, int color, boolean bold) { TextView view = new TextView(activity); view.setText(text); view.setTextSize(size); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view; }
    private Button button(String text, Runnable action) { Button view = new Button(activity); view.setText(text); view.setTextSize(11); view.setMinHeight(dp(48)); view.setAllCaps(false); view.setTextColor(GOLD); view.setOnClickListener(ignored -> action.run()); return view; }
}
