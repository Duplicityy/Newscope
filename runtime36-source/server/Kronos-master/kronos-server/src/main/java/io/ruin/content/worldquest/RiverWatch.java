package io.ruin.content.worldquest;

/** Original Newscape survey and repair contract. */
final class RiverWatch {
    static final String ID = "river-watch";
    static final String TITLE = "River Watch";
    static final int COIN_REWARD = 1250;

    private RiverWatch() {}

    enum Action { ACCEPT, READ_UPSTREAM, READ_DOWNSTREAM, DIAGNOSE_LATCH, REPAIR, CLAIM }

    enum Stage {
        NOT_STARTED("A River Watch notice is posted at the west bridge approach in Lumbridge."),
        ACCEPTED("Read the east road signpost and search the survey crate beside the axe shop south of the bridge."),
        UPSTREAM_READ("The upstream gauge moved freely. Search the survey crate beside the axe shop south of the bridge."),
        DOWNSTREAM_READ("The old flood marks are dry but the alarm is still red. Read the east road signpost."),
        SURVEYED("The upstream gauge moved freely. The flood marks are dry but the alarm stayed red. Return to the west bridge signpost to report your findings."),
        DIAGNOSED("Take a hammer to the east road signpost and reset the sticking alarm latch."),
        REPAIRED("The alarm has reset. Report back at the west bridge signpost to collect 1,250 coins."),
        COMPLETE("The alarm is working again. The River Watch paid 1,250 coins for your help."),
        UNAVAILABLE("This quest's saved progress could not be read. Ask staff to check your save.");

        final String journal;

        Stage(String journal) {
            this.journal = journal;
        }

        Stage after(Action action) {
            switch (this) {
                case NOT_STARTED:
                    return action == Action.ACCEPT ? ACCEPTED : this;
                case ACCEPTED:
                    if (action == Action.READ_UPSTREAM) return UPSTREAM_READ;
                    if (action == Action.READ_DOWNSTREAM) return DOWNSTREAM_READ;
                    return this;
                case UPSTREAM_READ:
                    return action == Action.READ_DOWNSTREAM ? SURVEYED : this;
                case DOWNSTREAM_READ:
                    return action == Action.READ_UPSTREAM ? SURVEYED : this;
                case SURVEYED:
                    return action == Action.DIAGNOSE_LATCH ? DIAGNOSED : this;
                case DIAGNOSED:
                    return action == Action.REPAIR ? REPAIRED : this;
                case REPAIRED:
                    return action == Action.CLAIM ? COMPLETE : this;
                default:
                    return this;
            }
        }
    }
}
