package local.kronos.mobile;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Portrait-only controls for the signed owner client. The game session stays in MainActivity. */
public final class AutomationActivity extends Activity {
    private static final int INK = 0xffe8eee9, MUTED = 0xffaab8b0, BG = 0xff101814;
    private static final int CARD = 0xff1b2922, GOLD = 0xffd1ad67, DARK = 0xff18190f;
    private static final List<String> CATEGORIES = Arrays.asList("Gathering", "Production and support",
            "Combat and Slayer", "Bossing", "Account progression", "Dungeoneering and raids");
    private static final Set<String> GATHERING = new HashSet<>(Arrays.asList(
            "Mining", "Woodcutting", "Fishing", "Hunter", "Farming"));
    private static final Set<String> COMBAT = new HashSet<>(Arrays.asList("Attack", "Strength",
            "Defence", "Hitpoints", "Ranged", "Magic", "Slayer"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PluginBridgeClient bridge = new PluginBridgeClient();
    private final Runnable poll = () -> send("status");
    private final List<String> available = new ArrayList<>();
    private final List<String> bosses = new ArrayList<>();
    private final Set<String> disabled = new HashSet<>();
    private Spinner category, activity, goal, slayerMaster, slayerTier;
    private LinearLayout genericGoal, slayerSettings;
    private Button saveSlayer;
    private final List<Integer> slayerMasterIds = new ArrayList<>();
    private final List<String> slayerMasterNames = new ArrayList<>();
    private int savedSlayerMaster;
    private String savedSlayerTier = "";
    private LinearLayout controls;
    private TextView status, note, legacy;
    private Switch master, enable;
    private Button start, pause, stop, refresh;
    private int preferredLevel = 99;
    private boolean visible, pending, applying, running, paused, authorized, statusCurrent;
    private boolean masterEnabled, raidRunning, dungeonRunning;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout body = column();
        body.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(body);
        setContentView(scroll);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("Automation", 25, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button back = button("Back to game", CARD, INK);
        top.addView(back);
        back.setOnClickListener(v -> finish());
        body.addView(top);

        LinearLayout summary = card();
        status = label("Checking owner session...", 16, INK);
        status.setMinHeight(dp(42));
        note = label("", 14, MUTED);
        summary.addView(status);
        summary.addView(note);
        refresh = button("Refresh", CARD, INK);
        summary.addView(refresh);
        refresh.setOnClickListener(v -> send("status"));
        add(body, summary);

        controls = column();
        controls.setVisibility(View.GONE);
        body.addView(controls);
        LinearLayout settings = card();
        master = toggle("Enable automation");
        settings.addView(master);
        category = picker(settings, "Category");
        setChoices(category, CATEGORIES);
        activity = picker(settings, "Activity");
        enable = toggle("Enable this skill");
        settings.addView(enable);
        genericGoal = column();
        goal = picker(genericGoal, "Goal");
        settings.addView(genericGoal);

        slayerSettings = column();
        slayerMaster = picker(slayerSettings, "Slayer master");
        slayerTier = picker(slayerSettings, "Task tier");
        setChoices(slayerTier, Arrays.asList("Easy", "Medium", "Hard"));
        saveSlayer = button("Save Slayer settings", CARD, INK);
        saveSlayer.setOnClickListener(v -> sendSlayer("slayerconfig"));
        slayerSettings.addView(saveSlayer);
        slayerSettings.setVisibility(View.GONE);
        settings.addView(slayerSettings);
        legacy = label("", 14, MUTED);
        settings.addView(legacy);
        add(controls, settings);

        LinearLayout commands = column();
        start = button("Start", GOLD, DARK);
        pause = button("Pause", CARD, INK);
        stop = button("Stop", 0xff4b2927, INK);
        commands.addView(start);
        add(commands, pause);
        add(commands, stop);
        add(controls, commands);

        master.setOnCheckedChangeListener((v, checked) -> { if (!applying) send("master " + checked); });
        enable.setOnCheckedChangeListener((v, checked) -> {
            String chosen = selected(activity);
            if (!applying && available.contains(chosen))
                send("enable " + chosen.toLowerCase(Locale.ROOT) + " " + checked);
        });
        category.setOnItemSelectedListener(selected(this::populateActivity));
        activity.setOnItemSelectedListener(selected(this::populateGoal));
        start.setOnClickListener(v -> begin());
        pause.setOnClickListener(v -> send(paused ? "resume" : "pause"));
        stop.setOnClickListener(v -> send(running ? "stop" : raidRunning ? "raidstop" : "dungeonstop"));
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        send("status");
    }

    @Override protected void onPause() {
        visible = false;
        main.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        bridge.close();
        super.onDestroy();
    }

    private void send(String action) {
        if (!visible || pending) return;
        pending = true;
        main.removeCallbacks(poll);
        buttons();
        JSONObject request = PluginBridgeClient.put(PluginBridgeClient.op("automation"), "action", action);
        bridge.request(request, reply -> {
            pending = false;
            if (!visible) return;
            if (action.equals("dungeonopen") && reply.optBoolean("ok")) {
                finish();
                return;
            }
            if (!reply.has("skills")) {
                showRequestFailure(action, reply);
                return;
            }
            render(reply);
            main.postDelayed(poll, 4500);
        });
    }

    private void showRequestFailure(String action, JSONObject reply) {
        statusCurrent = false;
        boolean needsLogin = reply.optString("code").equals("sign_in");
        if (needsLogin) {
            authorized = false;
            controls.setVisibility(View.GONE);
        }

        applying = true;
        master.setChecked(masterEnabled);
        enable.setChecked(!disabled.contains(selected(activity)));
        applying = false;

        boolean statusRequest = action.equals("status");
        status.setText(reply.optString("error", "Automation status unavailable"));
        note.setText(needsLogin ? "" : statusRequest
                ? "Retrying status. You can also tap Refresh."
                : "Checking the result. Wait for the updated status before trying again.");
        buttons();
        main.postDelayed(poll, statusRequest ? 4500 : 1200);
    }

    private void render(JSONObject reply) {
        applying = true;
        authorized = true;
        statusCurrent = true;
        List<String> skills = values(reply.optJSONArray("skills"));
        boolean first = controls.getVisibility() != View.VISIBLE;
        List<String> bossList = values(reply.optJSONArray("bosses"));
        boolean changed = !available.equals(skills) || !bosses.equals(bossList);
        bosses.clear();
        bosses.addAll(bossList);
        available.clear();
        available.addAll(skills);
        disabled.clear();
        disabled.addAll(values(reply.optJSONArray("disabled")));
        running = reply.optBoolean("running");
        paused = reply.optBoolean("paused");
        raidRunning = reply.optBoolean("raidActive");
        dungeonRunning = reply.optBoolean("dungeonActive");
        updateSlayerSettings(reply);
        masterEnabled = reply.optBoolean("enabled");
        master.setChecked(masterEnabled);
        status.setText(reply.optString("status", "Ready"));
        String warning = reply.optString("error");
        note.setText(warning.isEmpty() ? reply.optString("notice") : warning);
        legacy.setText("iDungeon: " + reply.optString("dungeon", "Stopped")
                + "\nRaid automation: " + (raidRunning ? "Running" : "Stopped"));
        controls.setVisibility(View.VISIBLE);
        if (first) {
            preferredLevel = reply.optInt("goal", 99);
            String saved = reply.optString("selected", "Mining");
            if (available.contains(saved)) {
                category.setSelection(GATHERING.contains(saved) ? 0 : COMBAT.contains(saved) ? 2 : 1);
                populateActivity();
                for (int i = 0; i < activity.getCount(); i++) {
                    if (saved.equals(activity.getItemAtPosition(i))) {
                        activity.setSelection(i);
                        break;
                    }
                }
            } else {
                populateActivity();
            }
        } else if (changed) {
            populateActivity();
        }
        enable.setChecked(!disabled.contains(selected(activity)));
        applying = false;
        buttons();
    }

    private void populateActivity() {
        if (activity == null) return;
        String group = selected(category), old = selected(activity);
        List<String> choices = new ArrayList<>();
        switch (group) {
            case "Gathering": for (String skill : available) if (GATHERING.contains(skill)) choices.add(skill); break;
            case "Combat and Slayer": for (String skill : available) if (COMBAT.contains(skill)) choices.add(skill); break;
            case "Production and support":
                for (String skill : available) if (!GATHERING.contains(skill) && !COMBAT.contains(skill)) choices.add(skill);
                break;
            case "Bossing": choices.addAll(bosses); break;
            case "Account progression": choices.add("Train enabled skills"); break;
            case "Dungeoneering and raids": choices.add("Current raid"); choices.add("iDungeon settings"); break;
            default: break;
        }
        setChoices(activity, choices);
        int index = choices.indexOf(old);
        if (index >= 0) activity.setSelection(index);
        populateGoal();
    }

    private void populateGoal() {
        if (goal == null) return;
        String group = selected(category);
        boolean isSlayer = editingSlayer();
        slayerSettings.setVisibility(isSlayer ? View.VISIBLE : View.GONE);
        genericGoal.setVisibility(isSlayer ? View.GONE : View.VISIBLE);
        boolean old = applying;
        applying = true;
        enable.setVisibility(available.contains(selected(activity)) ? View.VISIBLE : View.GONE);
        enable.setChecked(!disabled.contains(selected(activity)));
        if (group.equals("Bossing")) setChoices(goal, Arrays.asList("1 kill", "5 kills", "10 kills"));
        else if (group.equals("Dungeoneering and raids")) setChoices(goal, Arrays.asList("Current encounter"));
        else {
            String previous = selected(goal);
            List<String> levels = new ArrayList<>(Arrays.asList("Level 99", "Level 15", "Level 41", "Level 61"));
            String saved = "Level " + Math.min(99, Math.max(2, preferredLevel));
            if (!levels.contains(saved)) levels.add(saved);
            setChoices(goal, levels);
            int prior = levels.indexOf(previous);
            goal.setSelection(prior >= 0 ? prior : levels.indexOf(saved));
        }
        legacy.setVisibility(group.equals("Dungeoneering and raids") ? View.VISIBLE : View.GONE);
        applying = old;
        buttons();
    }

    private boolean editingSlayer() {
        return slayerMasterIds.size() > 1 && "Slayer".equals(selected(activity));
    }

    private void updateSlayerSettings(JSONObject reply) {
        List<Integer> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ids.add(0);
        names.add("Select a Slayer master");
        JSONArray masters = reply.optJSONArray("slayerMasters");
        if (masters != null) {
            for (int i = 0; i < masters.length(); i++) {
                JSONObject master = masters.optJSONObject(i);
                if (master == null || master.optInt("id") <= 0 || master.optString("name").isEmpty()) continue;
                ids.add(master.optInt("id"));
                names.add(master.optString("name"));
            }
        }
        int master = reply.optInt("slayerMaster");
        if (!ids.equals(slayerMasterIds) || !names.equals(slayerMasterNames)) {
            slayerMasterIds.clear();
            slayerMasterIds.addAll(ids);
            slayerMasterNames.clear();
            slayerMasterNames.addAll(names);
            setChoices(slayerMaster, names);
            slayerMaster.setSelection(Math.max(0, ids.indexOf(master)));
        } else if (savedSlayerMaster != master) {
            slayerMaster.setSelection(Math.max(0, ids.indexOf(master)));
        }
        savedSlayerMaster = master;
        String tier = reply.optString("slayerTier", "easy");
        if (!savedSlayerTier.equals(tier)) {
            slayerTier.setSelection("hard".equals(tier) ? 2 : "medium".equals(tier) ? 1 : 0);
            savedSlayerTier = tier;
        }
    }

    private void sendSlayer(String action) {
        int index = slayerMaster.getSelectedItemPosition();
        if (index <= 0 || index >= slayerMasterIds.size()) {
            note.setText("Select a Slayer master before starting.");
            slayerMaster.requestFocus();
            slayerMaster.performClick();
            return;
        }
        send(action + " " + slayerMasterIds.get(index) + " " + selected(slayerTier).toLowerCase(Locale.ROOT));
    }

    private void begin() {
        if (editingSlayer()) {
            sendSlayer("slayerstart");
            return;
        }
        String selected = selected(activity), target = selected(goal);
        switch (selected(category)) {
                        case "Bossing":
                String boss = selected.equals("Thermonuclear Smoke Devil") ? "smokedevil" : selected.toLowerCase(Locale.ROOT);
                send("boss " + boss + " " + target.split(" ")[0]);
                break;
            case "Account progression": send("progress " + target.replace("Level ", "")); break;
            case "Dungeoneering and raids": send(selected.equals("iDungeon settings") ? "dungeonopen" : "raidstart"); break;
            default: if (!selected.isEmpty()) send("start " + selected.toLowerCase(Locale.ROOT)
                    + " " + target.replace("Level ", ""));
        }
    }

    private void buttons() {
        if (start == null) return;
        boolean ready = authorized && statusCurrent && !pending;
        refresh.setEnabled(!pending);
        start.setEnabled(ready && masterEnabled && !running && !raidRunning && (!dungeonRunning || selected(activity).equals("iDungeon settings")) && !selected(activity).isEmpty()
                && !disabled.contains(selected(activity)));
        pause.setText(paused ? "Resume" : "Pause");
        pause.setEnabled(ready && running);
        stop.setEnabled(authorized && !pending && (running || raidRunning || dungeonRunning));
        master.setEnabled(ready);
        enable.setEnabled(ready && !running);
        boolean canEditSlayer = ready && !running && !raidRunning && !dungeonRunning;
        slayerMaster.setEnabled(canEditSlayer);
        slayerTier.setEnabled(canEditSlayer);
        saveSlayer.setEnabled(canEditSlayer);
    }

    private List<String> values(JSONArray array) {
        List<String> strings = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) strings.add(array.optString(i));
        return strings;
    }
    private AdapterView.OnItemSelectedListener selected(Runnable task) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { task.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
    }
    private static String selected(Spinner spinner) {
        Object item = spinner == null ? null : spinner.getSelectedItem();
        return item == null ? "" : item.toString();
    }
    private Spinner picker(LinearLayout parent, String caption) {
        TextView label = label(caption, 14, MUTED);
        parent.addView(label);
        Spinner view = new Spinner(this);
        view.setMinimumHeight(dp(54));
        view.setContentDescription(caption);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private void setChoices(Spinner spinner, List<String> choices) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private LinearLayout card() {
        LinearLayout view = column();
        view.setPadding(dp(16), dp(14), dp(16), dp(14));
        view.setBackground(rounded(CARD));
        return view;
    }
    private Switch toggle(String text) {
        Switch view = new Switch(this);
        view.setText(text);
        view.setTextColor(INK);
        view.setTextSize(16);
        view.setMinHeight(dp(52));
        return view;
    }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
    private Button button(String text, int fill, int color) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(text);
        view.setTextSize(16);
        view.setTextColor(color);
        view.setMinHeight(dp(52));
        view.setBackgroundTintList(ColorStateList.valueOf(fill));
        return view;
    }
    private GradientDrawable rounded(int color) {
        GradientDrawable view = new GradientDrawable();
        view.setColor(color);
        view.setCornerRadius(dp(15));
        return view;
    }
    private void add(LinearLayout parent, View child) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        parent.addView(child, params);
    }
    private int dp(float amount) { return (int) (amount * getResources().getDisplayMetrics().density + .5f); }
}
