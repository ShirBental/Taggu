package com.taggu.ingest.whatsapp;

/** Whether an export writes dates day-first ({@code 12/03}) or month-first ({@code 03/12}). */
public enum WhatsAppDateOrder {
    DAY_FIRST,
    MONTH_FIRST,
    /** The date is written year-first, so there is nothing to disambiguate. */
    YEAR_FIRST
}
