package io.ruin.model.activities.raids.toa.port;

/** One instance-owned native room controller. No callback may outlive close(). */
public interface ToaEncounter {
    void spawn();
    void tick();
    void close();
}
