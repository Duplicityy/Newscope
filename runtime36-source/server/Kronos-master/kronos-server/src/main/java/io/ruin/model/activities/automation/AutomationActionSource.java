package io.ruin.model.activities.automation;

import io.ruin.process.event.Event;

/** Implemented by the selected Entity overlay to identify and cancel one exact native action. */
public interface AutomationActionSource {
    Event ownerAutomationAction();
    boolean cancelOwnerAutomationAction(Event expected);
}
