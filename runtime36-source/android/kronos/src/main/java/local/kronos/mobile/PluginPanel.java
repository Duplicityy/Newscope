package local.kronos.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Touch controls for the real desktop PluginManager and typed ConfigManager schema. */
final class PluginPanel {
    static final String HD_PLUGIN = "rs117.hd.HdPlugin";
    private static final int BACKGROUND = 0xff101a1e, PANEL = 0xff19272c, LINE = 0xff33454a;
    private static final int TEXT = 0xffe9eee7, MUTED = 0xffa8bec0, GOLD = 0xffdfbd7c, GREEN = 0xff9ad2b3;
    private final Activity activity;
    private final PluginBridgeClient bridge = new PluginBridgeClient();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<JSONObject> plugins = new ArrayList<>(), filtered = new ArrayList<>();
    private final PluginAdapter adapter = new PluginAdapter();
    private final Runnable poll = this::loadList;
    private Dialog dialog;
    private EditText search;
    private TextView count, status, title, description, runtimeState;
    private CheckBox enabled;
    private LinearLayout form;
    private String selectedId = "", query = "";
    private boolean listInFlight, binding, paused, closed, toggleInFlight;
    private int configGeneration, transitionGeneration;

    PluginPanel(Activity activity) { this.activity = activity; }

    void show() {
        if (closed || activity.isFinishing() || activity.isDestroyed()) return;
        if (dialog != null && dialog.isShowing()) { loadList(); return; }
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = vertical(); root.setPadding(dp(14), dp(8), dp(14), dp(8)); root.setBackgroundColor(BACKGROUND);
        LinearLayout header = horizontal(); header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = vertical();
        names.addView(text("RuneLite plugins", 18, TEXT, true));
        names.addView(text("Your desktop client’s plugin controls", 11, MUTED, false));
        header.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = button("Back to game", () -> dialog.dismiss()); header.addView(close);
        root.addView(header); root.addView(rule());
        boolean wide = activity.getResources().getConfiguration().screenWidthDp >= 640;
        LinearLayout body = new LinearLayout(activity); body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout catalogue = vertical(); catalogue.setPadding(0, dp(5), wide ? dp(12) : 0, 0);
        search = input("Search plugins", query); search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { query = s.toString(); filter(); }
            public void afterTextChanged(Editable editable) {}
        });
        catalogue.addView(search);
        count = text("Loading desktop plugins…", 11, MUTED, false); count.setPadding(dp(4), dp(4), 0, dp(4)); catalogue.addView(count);
        ListView list = new ListView(activity); list.setAdapter(adapter); list.setDividerHeight(0);
        list.setOnItemClickListener((parent, row, position, id) -> select(filtered.get(position)));
        catalogue.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        if (wide) body.addView(catalogue, new LinearLayout.LayoutParams(dp(240), -1));
        else body.addView(catalogue, new LinearLayout.LayoutParams(-1, dp(164)));
        ScrollView settings = new ScrollView(activity); settings.setFillViewport(true);
        LinearLayout detail = vertical(); detail.setPadding(dp(14), dp(9), dp(14), dp(11)); detail.setBackground(round(PANEL, 10));
        title = text("Choose a plugin", 16, TEXT, true); detail.addView(title);
        description = text("Plugin switches change the same native settings used on desktop. Some plugins require a compatible renderer or desktop services.", 11, MUTED, false);
        description.setPadding(0, dp(4), 0, dp(6)); detail.addView(description);
        enabled = new CheckBox(activity); enabled.setText("Enabled"); enabled.setTextSize(12); enabled.setMinHeight(dp(48)); enabled.setTextColor(TEXT); enabled.setButtonTintList(android.content.res.ColorStateList.valueOf(GOLD));
        enabled.setVisibility(View.GONE);
        enabled.setOnCheckedChangeListener((button, checked) -> { if (!binding) toggleSelected(checked); });
        detail.addView(enabled);
        runtimeState = text("", 11, MUTED, false); runtimeState.setPadding(dp(4), 0, 0, dp(6)); detail.addView(runtimeState);
        form = vertical(); detail.addView(form);
        settings.addView(detail);
        body.addView(settings, new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : 0, 1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        status = text("Connecting to the embedded client…", 11, MUTED, false); status.setPadding(dp(3), dp(6), dp(3), 0); root.addView(status);
        dialog.setContentView(root);
        dialog.setOnDismissListener(ignored -> {
            handler.removeCallbacks(poll); bridge.flush();
            // Existing callbacks may finish, but must not mutate a new panel's view tree.
            configGeneration++;
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        paused = false; filter(); loadList();
    }

    void showHd() {
        selectedId = HD_PLUGIN;
        query = "";
        show();
        JSONObject found = find(HD_PLUGIN);
        if (found != null) select(found);
    }

    void enableHd() {
        showHd();
        status.setText("Preparing genuine 117 HD. Standard graphics are retained if setup fails.");
        JSONObject request = PluginBridgeClient.put(PluginBridgeClient.op("toggle"), "plugin", HD_PLUGIN);
        PluginBridgeClient.put(request, "enabled", true);
        bridge.request(request, reply -> {
            if (!showing()) return;
            showReply(reply, "117 HD request queued; checking renderer state…");
            loadList();
        });
    }

    /** Keep all waits off the UI thread; running=false alone does not prove GL shutdown is complete. */
    void stopHdThen(Runnable next) {
        if (closed) return;
        int generation = ++transitionGeneration;
        long deadline = SystemClock.uptimeMillis() + 12000;
        JSONObject request = PluginBridgeClient.put(PluginBridgeClient.op("toggle"), "plugin", HD_PLUGIN);
        PluginBridgeClient.put(request, "enabled", false);
        bridge.request(request, reply -> {
            if (closed || generation != transitionGeneration) return;
            if (!reply.optBoolean("ok", false) && !reply.optBoolean("pending", false)) {
                toast(reply.optString("error", "Could not stop 117 HD safely.")); return;
            }
            awaitStopped(generation, deadline, next);
        });
    }

    private void awaitStopped(int generation, long deadline, Runnable next) {
        if (closed || generation != transitionGeneration) return;
        bridge.request(PluginBridgeClient.op("list"), reply -> {
            if (closed || generation != transitionGeneration) return;
            boolean running = reply.optBoolean("hdRunning", true);
            boolean ready = reply.optBoolean("rendererReady", false);
            if (!running && ready) { next.run(); return; }
            if (SystemClock.uptimeMillis() >= deadline) { toast("117 HD is still releasing its graphics context. Keep the current renderer and try again."); return; }
            handler.postDelayed(() -> awaitStopped(generation, deadline, next), 250);
        });
    }

    private void loadList() {
        handler.removeCallbacks(poll);
        if (paused || !showing() || listInFlight) return;
        listInFlight = true;
        bridge.request(PluginBridgeClient.op("list"), reply -> {
            listInFlight = false;
            if (paused || !showing()) return;
            JSONArray array = reply.optJSONArray("plugins");
            if (reply.optBoolean("ready", false) && array != null) {
                plugins.clear();
                for (int i = 0; i < array.length(); i++) { JSONObject plugin = array.optJSONObject(i); if (plugin != null && !plugin.optString("id").isEmpty()) plugins.add(plugin); }
                plugins.sort(Comparator.comparing(p -> p.optString("name").toLowerCase(Locale.ROOT)));
                filter();
                JSONObject current = find(selectedId);
                if (current != null) {
                    bindState(current);
                    if (form.getChildCount() == 0) loadConfig(current);
                } else if (!selectedId.isEmpty()) {
                    title.setText(selectedId.equals(HD_PLUGIN) ? "117 HD" : "Plugin not available");
                    enabled.setVisibility(View.GONE); form.removeAllViews();
                    description.setText(selectedId.equals(HD_PLUGIN) ? "Genuine 117 HD must be registered successfully by the desktop plugin manager before it can be enabled." : "Select an installed desktop plugin from the list.");
                    runtimeState.setText("This plugin has not been registered by the embedded client.");
                }
                String hd = reply.optString("hdStatus", "");
                String failure = reply.optString("lastError", "");
                if (failure.isEmpty()) failure = reply.optString("hdRegistrationError", "");
                String operation = reply.optString("operation", "");
                status.setText(!failure.isEmpty() ? "Last plugin issue: " + failure : !operation.isEmpty() ? operation + "…" : plugins.size() + " desktop plugins · " + reply.optString("version", "server-compatible engine") + (hd.isEmpty() ? "" : " · " + hd));
            } else {
                status.setText(reply.optString("error", "Waiting for the desktop plugin manager to finish loading…"));
            }
            handler.postDelayed(poll, 1000);
        });
    }

    private void filter() {
        filtered.clear(); String q = query.trim().toLowerCase(Locale.ROOT);
        for (JSONObject plugin : plugins) if (q.isEmpty() || (plugin.optString("name") + " " + plugin.optString("description") + " " + plugin.optString("id")).toLowerCase(Locale.ROOT).contains(q)) filtered.add(plugin);
        adapter.notifyDataSetChanged();
        if (count != null) count.setText(filtered.size() + " of " + plugins.size() + " desktop plugins");
    }

    private JSONObject find(String id) { for (JSONObject plugin : plugins) if (id.equals(plugin.optString("id"))) return plugin; return null; }

    private void select(JSONObject plugin) {
        selectedId = plugin.optString("id"); configGeneration++;
        title.setText(clean(plugin.optString("name", "Plugin")));
        description.setText(clean(plugin.optString("description", "")));
        form.removeAllViews(); bindState(plugin); loadConfig(plugin); adapter.notifyDataSetChanged();
    }

    private void bindState(JSONObject plugin) {
        title.setText(clean(plugin.optString("name", "Plugin")));
        description.setText(clean(plugin.optString("description", "")));
        binding = true;
        enabled.setVisibility(View.VISIBLE); enabled.setChecked(plugin.optBoolean("enabled", false));
        String blocked = plugin.optString("blockedReason", "");
        enabled.setEnabled(!toggleInFlight && blocked.isEmpty());
        boolean configured = plugin.optBoolean("enabled", false), running = plugin.optBoolean("running", false);
        runtimeState.setText(!blocked.isEmpty() ? blocked : running ? "Running in the desktop plugin manager" : configured ? "Enabled in settings · waiting for startup or renderer readiness" : "Disabled");
        runtimeState.setTextColor(running ? GREEN : MUTED);
        binding = false;
    }

    private void toggleSelected(boolean on) {
        JSONObject plugin = find(selectedId); if (plugin == null) return;
        final String id = selectedId; toggleInFlight = true; enabled.setEnabled(false);
        JSONObject request = PluginBridgeClient.put(PluginBridgeClient.op("toggle"), "plugin", id);
        PluginBridgeClient.put(request, "enabled", on);
        bridge.request(request, reply -> {
            toggleInFlight = false;
            if (!showing()) return;
            showReply(reply, "Plugin change queued; checking actual state…");
            if (id.equals(selectedId)) { JSONObject current = find(id); if (current != null) bindState(current); }
            loadList();
        });
    }

    private void loadConfig(JSONObject plugin) {
        int generation = ++configGeneration; String id = plugin.optString("id");
        form.removeAllViews();
        if (!plugin.optBoolean("configurable", false)) { form.addView(text("This plugin has no exposed configuration options.", 12, MUTED, false)); return; }
        form.addView(text("Loading settings…", 12, MUTED, false));
        bridge.request(PluginBridgeClient.put(PluginBridgeClient.op("config"), "plugin", id), reply -> {
            if (!showing() || generation != configGeneration || !id.equals(selectedId)) return;
            form.removeAllViews(); JSONArray items = reply.optJSONArray("items");
            if (items == null) {
                form.addView(text(reply.optString("error", "Settings are not ready."), 12, MUTED, false));
                form.addView(button("Retry settings", () -> loadConfig(plugin))); return;
            }
            form.addView(text("PLUGIN SETTINGS", 10, GOLD, true));
            int exposed = 0;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || item.optBoolean("secret", false) || item.optBoolean("hidden", false)) continue;
                addSetting(item, id); exposed++;
            }
            if (exposed == 0) form.addView(text("No editable, non-private settings are exposed by this plugin.", 12, MUTED, false));
        });
    }

    private void addSetting(JSONObject item, String pluginId) {
        String key = item.optString("key"); if (key.isEmpty()) return;
        LinearLayout row = vertical(); row.setPadding(0, dp(8), 0, dp(8));
        row.addView(text(clean(item.optString("name", key)), 12, TEXT, true));
        String desc = clean(item.optString("description", ""));
        if (!desc.isEmpty()) row.addView(text(desc, 11, MUTED, false));
        String type = kind(item.optString("type", "string"));
        String value = item.isNull("value") ? "" : String.valueOf(item.opt("value"));
        if (item.optBoolean("readOnly", false) || type.equals("unsupported")) {
            TextView read = text(value.isEmpty() ? "Not set" : value, 12, MUTED, false); read.setTextIsSelectable(true); read.setPadding(0, dp(6), 0, 0); row.addView(read);
            String reason = item.optString("readOnlyReason", item.optString("reason", "This setting type is available as a reference in this build."));
            row.addView(text(reason, 10, MUTED, false));
        } else if (type.equals("boolean")) {
            CheckBox control = new CheckBox(activity); control.setText("Enabled"); control.setTextSize(12); control.setMinHeight(dp(48)); control.setTextColor(TEXT);
            control.setButtonTintList(android.content.res.ColorStateList.valueOf(GOLD)); control.setChecked(Boolean.parseBoolean(value));
            control.setOnCheckedChangeListener((button, checked) -> confirmSetting(item, () -> save(pluginId, item, String.valueOf(checked), control), () -> { control.setOnCheckedChangeListener(null); control.setChecked(!checked); loadCurrentConfig(); }));
            row.addView(control);
        } else if (type.equals("enum")) {
            JSONArray options = item.optJSONArray("options");
            List<String> values = new ArrayList<>(), labels = new ArrayList<>();
            if (options != null) for (int i = 0; i < options.length(); i++) {
                Object option = options.opt(i);
                if (option instanceof JSONObject) { JSONObject o = (JSONObject)option; values.add(o.optString("value")); labels.add(o.optString("label", o.optString("value"))); }
                else { values.add(String.valueOf(option)); labels.add(String.valueOf(option).replace('_', ' ')); }
            }
            Button choose = button(value.replace('_', ' '), () -> new AlertDialog.Builder(activity).setTitle(clean(item.optString("name", key)))
                    .setItems(labels.toArray(new String[0]), (dialog, index) -> confirmSetting(item, () -> save(pluginId, item, values.get(index), null), null)).setNegativeButton("Cancel", null).show());
            choose.setEnabled(!values.isEmpty()); row.addView(choose);
        } else if (type.equals("keybind")) {
            row.addView(button(bindingLabel(value), () -> chooseBinding(item, pluginId, value)));
        } else {
            LinearLayout line = horizontal(); line.setGravity(Gravity.CENTER_VERTICAL);
            EditText control = input(type.equals("color") ? "#RRGGBB or #AARRGGBB" : type.equals("dimension") ? "width x height" : type.equals("keybind") ? "Key binding" : "Value", value);
            control.setFilters(new InputFilter[]{new InputFilter.LengthFilter(type.equals("string") ? 8192 : 128)});
            if (type.equals("integer") || type.equals("long")) control.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
            else if (type.equals("double") || type.equals("float")) control.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            else control.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            control.setSingleLine(!type.equals("string")); control.setSelectAllOnFocus(true);
            if (type.equals("string")) control.setMaxLines(4);
            line.addView(control, new LinearLayout.LayoutParams(0, -2, 1));
            Button apply = button("Apply", () -> {
                String text = control.getText().toString();
                if (!type.equals("string")) text = text.trim();
                if (type.equals("dimension")) text = text.replaceAll("\\s+", "").replace('X', 'x').replace(',', 'x');
                String error = validate(item, type, text);
                if (error != null) { control.setError(error); return; }
                final String normalized = text;
                confirmSetting(item, () -> save(pluginId, item, normalized, control), null);
            }); line.addView(apply); row.addView(line);
            if (item.has("min") || item.has("max")) row.addView(text("Range: " + (item.has("min") ? item.optString("min") : "—") + " to " + (item.has("max") ? item.optString("max") : "—"), 10, MUTED, false));
        }
        form.addView(row); form.addView(rule());
    }

    private void chooseBinding(JSONObject item, String pluginId, String value) {
        List<String> labels = new ArrayList<>(); List<Integer> codes = new ArrayList<>();
        labels.add("Not set"); codes.add(0);
        for (int i = 0; i < 12; i++) { labels.add("F" + (i + 1)); codes.add(112 + i); }
        for (int i = 0; i < 26; i++) { labels.add(String.valueOf((char)('A' + i))); codes.add(65 + i); }
        for (int i = 0; i < 10; i++) { labels.add(String.valueOf(i)); codes.add(48 + i); }
        String[] other = {"Space", "Escape", "Enter", "Tab", "Backspace", "Delete", "Up", "Down", "Left", "Right", "Home", "End", "Page Up", "Page Down", "Insert"};
        int[] otherCodes = {32, 27, 10, 9, 8, 127, 38, 40, 37, 39, 36, 35, 33, 34, 155};
        for (int i = 0; i < other.length; i++) { labels.add(other[i]); codes.add(otherCodes[i]); }
        int oldCode = 0, modifiers = 0;
        try { String[] fields = value.split(":"); oldCode = Integer.parseInt(fields[0]); modifiers = Integer.parseInt(fields[1]); } catch (RuntimeException ignored) {}
        if (!codes.contains(oldCode)) { labels.add("Current key (" + oldCode + ")"); codes.add(oldCode); }
        LinearLayout pane = vertical(); pane.setPadding(dp(18), dp(7), dp(18), dp(7));
        pane.addView(text("Choose a desktop hotkey. A keyboard or a configured mobile hotkey can send it.", 12, MUTED, false));
        Spinner keys = new Spinner(activity);
        ArrayAdapter<String> options = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, labels);
        keys.setAdapter(options); keys.setSelection(codes.indexOf(oldCode)); pane.addView(keys);
        String[] modifierLabels = {"Shift", "Ctrl", "Alt", "Meta"}; int[] masks = {64, 128, 512, 256};
        CheckBox[] boxes = new CheckBox[4]; LinearLayout modifierRow = horizontal();
        for (int i = 0; i < boxes.length; i++) { boxes[i] = new CheckBox(activity); boxes[i].setText(modifierLabels[i]); boxes[i].setTextColor(TEXT); boxes[i].setChecked((modifiers & masks[i]) != 0); modifierRow.addView(boxes[i]); }
        pane.addView(modifierRow);
        new AlertDialog.Builder(activity).setTitle(clean(item.optString("name", "Hotkey"))).setView(pane)
                .setPositiveButton("Apply", (d, w) -> {
                    int mask = 0; for (int i = 0; i < boxes.length; i++) if (boxes[i].isChecked()) mask |= masks[i];
                    final String binding = codes.get(keys.getSelectedItemPosition()) + ":" + mask;
                    confirmSetting(item, () -> save(pluginId, item, binding, null), null);
                }).setNegativeButton("Cancel", null).show();
    }

    private static String bindingLabel(String value) {
        try {
            String[] pieces = value.split(":"); int code = Integer.parseInt(pieces[0]), mask = Integer.parseInt(pieces[1]);
            if (code == 0 && mask == 0) return "Set hotkey…";
            String key = code >= 112 && code <= 123 ? "F" + (code - 111) : code >= 65 && code <= 90 || code >= 48 && code <= 57 ? String.valueOf((char)code) : code == 0 ? "Modifier only" : "Key " + code;
            return ((mask & 256) != 0 ? "Meta+" : "") + ((mask & 128) != 0 ? "Ctrl+" : "") + ((mask & 512) != 0 ? "Alt+" : "") + ((mask & 64) != 0 ? "Shift+" : "") + key;
        } catch (RuntimeException invalid) { return "Set hotkey…"; }
    }

    private void confirmSetting(JSONObject item, Runnable apply, Runnable cancel) {
        String warning = clean(item.optString("warning", ""));
        if (warning.isEmpty()) { apply.run(); return; }
        new AlertDialog.Builder(activity).setTitle("Change setting?").setMessage(warning)
                .setPositiveButton("Apply", (d, w) -> apply.run()).setNegativeButton("Cancel", (d, w) -> { if (cancel != null) cancel.run(); })
                .setOnCancelListener(d -> { if (cancel != null) cancel.run(); }).show();
    }

    private void save(String pluginId, JSONObject item, String value, View control) {
        if (control != null) control.setEnabled(false);
        JSONObject request = PluginBridgeClient.put(PluginBridgeClient.op("set"), "plugin", pluginId);
        PluginBridgeClient.put(request, "key", item.optString("key")); PluginBridgeClient.put(request, "value", value);
        bridge.request(request, reply -> {
            if (!showing()) return;
            showReply(reply, "Setting change queued; refreshing native values…");
            if (control != null) control.setEnabled(true);
            handler.postDelayed(() -> { if (showing() && pluginId.equals(selectedId)) loadCurrentConfig(); }, 400);
        });
    }
    private void loadCurrentConfig() { JSONObject selected = find(selectedId); if (selected != null) loadConfig(selected); }
    private void showReply(JSONObject reply, String success) {
        String error = reply.optString("error", "");
        status.setText(!error.isEmpty() ? error : reply.optBoolean("ok", false) || reply.optBoolean("pending", false) ? success : "The request was not accepted.");
        if (!error.isEmpty()) toast(error);
    }

    private static String validate(JSONObject item, String type, String text) {
        try {
            if (type.equals("integer") || type.equals("long") || type.equals("double") || type.equals("float")) {
                double number = Double.parseDouble(text.trim());
                if (!Double.isFinite(number)) return "Enter a finite number";
                if (type.equals("integer")) Integer.parseInt(text.trim());
                if (type.equals("long")) Long.parseLong(text.trim());
                if (item.has("min") && number < item.optDouble("min")) return "Below the allowed minimum";
                if (item.has("max") && number > item.optDouble("max")) return "Above the allowed maximum";
            }
            if (type.equals("color") && !text.matches("#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) return "Use #RRGGBB or #AARRGGBB";
            if (type.equals("dimension") && !text.matches("\\s*[1-9][0-9]{0,4}\\s*[xX,]\\s*[1-9][0-9]{0,4}\\s*")) return "Use width x height";
        } catch (NumberFormatException invalid) { return "Enter a valid " + type + " value"; }
        return null;
    }

    private static String kind(String raw) {
        String type = raw.toLowerCase(Locale.ROOT);
        if (type.equals("boolean") || type.endsWith(".boolean")) return "boolean";
        if (type.equals("int") || type.endsWith("integer")) return "integer";
        if (type.equals("long") || type.endsWith(".long")) return "long";
        if (type.equals("double") || type.endsWith(".double")) return "double";
        if (type.equals("float") || type.endsWith(".float")) return "float";
        if (type.equals("enum")) return "enum";
        if (type.equals("color") || type.endsWith(".color")) return "color";
        if (type.equals("dimension") || type.endsWith(".dimension")) return "dimension";
        if (type.equals("keybind") || type.endsWith(".keybind") || type.endsWith(".modifierlesskeybind")) return "keybind";
        if (type.equals("string") || type.endsWith(".string")) return "string";
        return "unsupported";
    }
    private static String clean(String text) { return text.replaceAll("<[^>]*>", "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&nbsp;", " "); }
    private boolean showing() { return !closed && dialog != null && dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed(); }
    void pause() { paused = true; handler.removeCallbacks(poll); bridge.flush(); }
    void resume() { paused = false; if (showing()) loadList(); }
    void close() { if (closed) return; transitionGeneration++; handler.removeCallbacksAndMessages(null); if (dialog != null && dialog.isShowing()) dialog.dismiss(); bridge.close(); closed = true; }
    private void toast(String message) { if (!activity.isFinishing() && !activity.isDestroyed()) Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private LinearLayout vertical() { LinearLayout l = new LinearLayout(activity); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout horizontal() { LinearLayout l = new LinearLayout(activity); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private TextView text(String value, int size, int color, boolean bold) { TextView t = new TextView(activity); t.setText(value); t.setTextSize(size); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t; }
    private EditText input(String hint, String value) { EditText t = new EditText(activity); t.setTextColor(TEXT); t.setHintTextColor(MUTED); t.setTextSize(12); t.setMinHeight(dp(48)); t.setHint(hint); t.setText(value); t.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); t.setSaveEnabled(false); return t; }
    private Button button(String name, Runnable action) { Button b = new Button(activity); b.setText(name); b.setTextColor(GOLD); b.setTextSize(11); b.setMinHeight(dp(48)); b.setAllCaps(false); b.setOnClickListener(v -> action.run()); return b; }
    private View rule() { View line = new View(activity); line.setBackgroundColor(LINE); line.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1))); return line; }
    private GradientDrawable round(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }

    private final class PluginAdapter extends BaseAdapter {
        public int getCount() { return filtered.size(); }
        public Object getItem(int position) { return filtered.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View recycled, ViewGroup parent) {
            JSONObject plugin = filtered.get(position);
            LinearLayout row = vertical(); row.setPadding(dp(10), dp(6), dp(8), dp(6)); row.setMinimumHeight(dp(48));
            row.setBackground(round(selectedId.equals(plugin.optString("id")) ? 0xff34443a : PANEL, 7));
            TextView name = text(clean(plugin.optString("name", "Plugin")), 12, selectedId.equals(plugin.optString("id")) ? GOLD : TEXT, true); row.addView(name);
            boolean running = plugin.optBoolean("running", false), configured = plugin.optBoolean("enabled", false);
            String blocked = plugin.optString("blockedReason", "");
            TextView state = text(!blocked.isEmpty() ? "Unavailable on Android" : running ? "Running" : configured ? "Enabled · not running" : "Disabled", 10, running ? GREEN : MUTED, false); state.setPadding(0, dp(2), 0, 0); row.addView(state);
            row.setContentDescription(name.getText() + ". " + state.getText());
            return row;
        }
    }
}
