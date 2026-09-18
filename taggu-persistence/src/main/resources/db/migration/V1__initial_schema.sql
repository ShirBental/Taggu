-- Taggu initial schema.
--
-- Two rules shape this design:
--   1. Nothing outside the "messages" side of the schema records which product a message came from
--      beyond a plain "source" label, so a new source is rows, not columns.
--   2. Extracted knowledge is worthless without evidence, so facts, events, action items and
--      decisions each have a *_sources table. V2 adds the PostgreSQL trigger that refuses a row which
--      reaches the end of a transaction without one.
--
-- Everything here is written in SQL both PostgreSQL and H2 accept, so the tests run these exact
-- migrations rather than a schema generated from the code.

-- ---------------------------------------------------------------------------
-- Conversations, participants, messages
-- ---------------------------------------------------------------------------

CREATE TABLE conversations (
    id                     UUID PRIMARY KEY,
    source                 VARCHAR        NOT NULL,
    source_conversation_id VARCHAR        NOT NULL,
    title                  VARCHAR        NOT NULL,
    started_at             TIMESTAMP WITH TIME ZONE,
    ended_at               TIMESTAMP WITH TIME ZONE,
    imported_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_conversations_source UNIQUE (source, source_conversation_id)
);

-- A participant is identified by the source that knows them, not by the conversation they appear in,
-- so the same person recognised in two threads is one row. This is a deliberate deviation from a
-- participants-belong-to-a-conversation design: it is what lets knowledge be joined across threads
-- later without a migration.
CREATE TABLE participants (
    id                UUID PRIMARY KEY,
    source            VARCHAR NOT NULL,
    source_identifier VARCHAR NOT NULL,
    display_name      VARCHAR NOT NULL,
    CONSTRAINT uq_participants_source UNIQUE (source, source_identifier)
);

CREATE TABLE conversation_participants (
    conversation_id UUID NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    participant_id  UUID NOT NULL REFERENCES participants (id) ON DELETE CASCADE,
    PRIMARY KEY (conversation_id, participant_id)
);

CREATE TABLE messages (
    id                  UUID PRIMARY KEY,
    conversation_id     UUID        NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    source              VARCHAR        NOT NULL,
    source_message_id   VARCHAR        NOT NULL,
    sender_id           UUID REFERENCES participants (id),
    sent_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    message_type        VARCHAR        NOT NULL,
    body                VARCHAR        NOT NULL DEFAULT '',
    reply_to_message_id UUID REFERENCES messages (id),
    raw_content         VARCHAR,
    CONSTRAINT uq_messages_source UNIQUE (conversation_id, source_message_id)
);

CREATE INDEX idx_messages_conversation_sent_at ON messages (conversation_id, sent_at);
CREATE INDEX idx_messages_sent_at ON messages (sent_at);

-- Source-specific extras live as rows rather than a JSON column so that adapters can record whatever
-- they need without every reader having to understand a document format.
CREATE TABLE message_metadata (
    message_id    UUID NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    metadata_key  VARCHAR NOT NULL,
    metadata_value VARCHAR,
    PRIMARY KEY (message_id, metadata_key)
);

-- ---------------------------------------------------------------------------
-- Classification: tags and entities
-- ---------------------------------------------------------------------------

CREATE TABLE tags (
    id    UUID PRIMARY KEY,
    slug  VARCHAR NOT NULL,
    label VARCHAR NOT NULL,
    CONSTRAINT uq_tags_slug UNIQUE (slug)
);

CREATE TABLE message_tags (
    message_id UUID             NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    tag_id     UUID             NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    confidence DOUBLE PRECISION NOT NULL,
    extractor  VARCHAR             NOT NULL,
    PRIMARY KEY (message_id, tag_id)
);

CREATE TABLE entities (
    id              UUID PRIMARY KEY,
    entity_type     VARCHAR NOT NULL,
    name            VARCHAR NOT NULL,
    normalized_name VARCHAR NOT NULL,
    CONSTRAINT uq_entities_identity UNIQUE (entity_type, normalized_name)
);

CREATE TABLE entity_mentions (
    entity_id  UUID             NOT NULL REFERENCES entities (id) ON DELETE CASCADE,
    message_id UUID             NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    confidence DOUBLE PRECISION NOT NULL,
    extractor  VARCHAR             NOT NULL,
    PRIMARY KEY (entity_id, message_id)
);

-- ---------------------------------------------------------------------------
-- Knowledge, each kind with its own evidence table
-- ---------------------------------------------------------------------------

CREATE TABLE facts (
    id              UUID PRIMARY KEY,
    conversation_id UUID             NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    statement       VARCHAR             NOT NULL,
    subject         VARCHAR,
    confidence      DOUBLE PRECISION NOT NULL,
    extractor       VARCHAR             NOT NULL,
    extracted_at    TIMESTAMP WITH TIME ZONE      NOT NULL
);

CREATE TABLE fact_sources (
    fact_id    UUID NOT NULL REFERENCES facts (id) ON DELETE CASCADE,
    message_id UUID NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    excerpt    VARCHAR,
    PRIMARY KEY (fact_id, message_id)
);

CREATE TABLE events (
    id              UUID PRIMARY KEY,
    conversation_id UUID             NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    title           VARCHAR             NOT NULL,
    starts_at       TIMESTAMP WITH TIME ZONE,
    ends_at         TIMESTAMP WITH TIME ZONE,
    time_precision  VARCHAR             NOT NULL,
    time_expression VARCHAR,
    location        VARCHAR,
    confidence      DOUBLE PRECISION NOT NULL,
    extractor       VARCHAR             NOT NULL,
    extracted_at    TIMESTAMP WITH TIME ZONE      NOT NULL
);

CREATE TABLE event_sources (
    event_id   UUID NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    message_id UUID NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    excerpt    VARCHAR,
    PRIMARY KEY (event_id, message_id)
);

CREATE TABLE action_items (
    id              UUID PRIMARY KEY,
    conversation_id UUID             NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    description     VARCHAR             NOT NULL,
    assignee        VARCHAR,
    due_at          TIMESTAMP WITH TIME ZONE,
    due_precision   VARCHAR             NOT NULL,
    due_expression  VARCHAR,
    status          VARCHAR             NOT NULL,
    confidence      DOUBLE PRECISION NOT NULL,
    extractor       VARCHAR             NOT NULL,
    extracted_at    TIMESTAMP WITH TIME ZONE      NOT NULL
);

CREATE TABLE action_item_sources (
    action_item_id UUID NOT NULL REFERENCES action_items (id) ON DELETE CASCADE,
    message_id     UUID NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    excerpt        VARCHAR,
    PRIMARY KEY (action_item_id, message_id)
);

CREATE TABLE decisions (
    id              UUID PRIMARY KEY,
    conversation_id UUID             NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    statement       VARCHAR             NOT NULL,
    decided_at      TIMESTAMP WITH TIME ZONE,
    confidence      DOUBLE PRECISION NOT NULL,
    extractor       VARCHAR             NOT NULL,
    extracted_at    TIMESTAMP WITH TIME ZONE      NOT NULL
);

CREATE TABLE decision_sources (
    decision_id UUID NOT NULL REFERENCES decisions (id) ON DELETE CASCADE,
    message_id  UUID NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    excerpt     VARCHAR,
    PRIMARY KEY (decision_id, message_id)
);

CREATE INDEX idx_facts_conversation ON facts (conversation_id);
CREATE INDEX idx_events_conversation ON events (conversation_id);
CREATE INDEX idx_events_starts_at ON events (starts_at);
CREATE INDEX idx_action_items_conversation ON action_items (conversation_id);
CREATE INDEX idx_action_items_due_at ON action_items (due_at);
CREATE INDEX idx_decisions_conversation ON decisions (conversation_id);

-- ---------------------------------------------------------------------------
-- Import bookkeeping
-- ---------------------------------------------------------------------------

-- Counts and a file name only. Nothing a participant wrote is recorded here, because this is the
-- table an operator is most likely to look at.
CREATE TABLE import_runs (
    id                 UUID PRIMARY KEY,
    conversation_id    UUID REFERENCES conversations (id) ON DELETE SET NULL,
    source             VARCHAR        NOT NULL,
    source_reference   VARCHAR,
    status             VARCHAR        NOT NULL,
    started_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at        TIMESTAMP WITH TIME ZONE,
    parsed_messages    INTEGER     NOT NULL DEFAULT 0,
    new_messages       INTEGER     NOT NULL DEFAULT 0,
    parse_issues       INTEGER     NOT NULL DEFAULT 0,
    knowledge_items    INTEGER     NOT NULL DEFAULT 0
);
