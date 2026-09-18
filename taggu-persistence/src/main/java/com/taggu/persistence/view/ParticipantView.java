package com.taggu.persistence.view;

import java.util.UUID;

/** A participant as the API returns them. */
public record ParticipantView(UUID id, String displayName, String sourceIdentifier) {}
