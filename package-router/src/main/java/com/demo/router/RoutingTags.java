package com.demo.router;

import com.demo.commons.observability.TagDescriptor;

/**
 * Micrometer tag keys the aspect attaches to {@link RoutingEvents}
 * counters. Each value comes from the active
 * {@link com.demo.commons.observability.ProcessContext}.
 */
public enum RoutingTags implements TagDescriptor {

    TOPIC("topic"),
    PRIORITY("priority"),
    GRADE("grade"),
    ROUTING_REASON("routing_reason");

    private final String tagName;

    RoutingTags(String tagName) {
        this.tagName = tagName;
    }

    @Override
    public String tagName() {
        return tagName;
    }
}
