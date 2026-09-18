package com.taggu.core.knowledge;

/** Helpers for the 0..1 confidence score every extracted item carries. */
public final class Confidence {

    private Confidence() {}

    public static double require(double value) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        return value;
    }
}
