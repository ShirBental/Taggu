package com.taggu.core.knowledge;

/** Where an {@link ActionItem} stands. Extraction only ever produces {@link #OPEN}. */
public enum ActionItemStatus {
    OPEN,
    DONE,
    CANCELLED
}
