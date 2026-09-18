package com.taggu.core.knowledge;

/** How exactly a point in time was pinned down by the conversation it came from. */
public enum TimePrecision {
    EXACT,
    /** The day is known but the time is not, or the time was given loosely ("around 4"). */
    APPROXIMATE,
    /** A time was mentioned but could not be resolved to an instant ("next week sometime"). */
    UNRESOLVED
}
