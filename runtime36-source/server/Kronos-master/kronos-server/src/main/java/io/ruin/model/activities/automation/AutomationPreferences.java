package io.ruin.model.activities.automation;

import com.google.gson.annotations.Expose;
import io.ruin.model.stat.StatType;

import java.util.EnumSet;

/** Login-independent preferences in the normal player snapshot. Running work is transient. */
public final class AutomationPreferences {
    @Expose boolean enabled = true;
    @Expose int goal = 99;
    @Expose String selected = StatType.Mining.name();
    @Expose EnumSet<StatType> disabled = EnumSet.noneOf(StatType.class);
    @Expose SlayerSettings slayer = new SlayerSettings();

    SlayerSettings slayer() {
        if (slayer == null) slayer = new SlayerSettings();
        return slayer;
    }

    public interface Source {
        AutomationPreferences automationPreferences();
    }
}
