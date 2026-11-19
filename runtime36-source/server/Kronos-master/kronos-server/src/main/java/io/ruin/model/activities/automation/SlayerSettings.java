package io.ruin.model.activities.automation;

import com.google.gson.annotations.Expose;
import io.ruin.model.skills.slayer.SlayerTask;

final class SlayerSettings {
    @Expose int master;
    @Expose SlayerTask.Type tier = SlayerTask.Type.EASY;
    @Expose int healthPercent = 60;
    @Expose int foodTarget = 12;
    @Expose int foodMinimum = 2;
    @Expose boolean loot = true;
    @Expose boolean useTeleports = true;
    @Expose boolean preferRanged;
    @Expose boolean preferHomeBank = true;

    int healthPercent() { return Math.max(45, Math.min(85, healthPercent)); }
    int foodTarget() { return Math.max(4, Math.min(20, foodTarget)); }
    int foodMinimum() { return Math.max(1, Math.min(foodTarget() - 1, foodMinimum)); }
}
