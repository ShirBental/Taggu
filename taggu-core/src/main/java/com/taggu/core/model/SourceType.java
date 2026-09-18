package com.taggu.core.model;

/**
 * The kind of system a conversation was ingested from.
 *
 * <p>This is the only place in the canonical model that names a concrete product, and it exists so
 * that identifiers can be namespaced per source. Nothing in the extraction or knowledge layers is
 * allowed to branch on the value.
 */
public enum SourceType {
    WHATSAPP,
    GMAIL,
    SMS,
    SLACK,
    TELEGRAM,
    OTHER
}
