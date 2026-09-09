package com.synechis.fulfillment.runtime;

import com.synechis.fulfillment.contracts.Event;

public interface EventHandler {
    void handle(Event event);
}
