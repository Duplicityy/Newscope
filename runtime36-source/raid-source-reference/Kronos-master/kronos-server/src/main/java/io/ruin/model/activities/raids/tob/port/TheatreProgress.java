package io.ruin.model.activities.raids.tob.port;

import com.google.gson.annotations.Expose;
import io.ruin.model.item.Item;
import java.util.ArrayList;
import java.util.List;

/** Persisted with the native player save; never replaces inventory or equipment. */
public final class TheatreProgress {
    @Expose public String activeRun;
    @Expose public int returnX, returnY, returnZ;
    @Expose public int completions, deaths;
    @Expose public String rewardedRun;
    @Expose public List<Item> pendingRewards = new ArrayList<>();
}
