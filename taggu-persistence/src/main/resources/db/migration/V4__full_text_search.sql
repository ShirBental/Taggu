-- Full-text search over messages and extracted knowledge. PostgreSQL only; adds columns and indexes
-- and changes no existing data.
--
-- Each searchable table gets a tsvector that PostgreSQL keeps in step with the text by itself (a
-- stored generated column), and a GIN index over it. Search until now was lower(body) LIKE '%...%',
-- which reads every message for every query and cannot see knowledge at all.
--
-- The 'simple' configuration is deliberate: no stemming and no stop-word list, so the index behaves
-- the same whatever language a conversation is in. A per-language configuration can be added beside
-- it later without touching these columns.

ALTER TABLE messages
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple', body)) STORED;
CREATE INDEX idx_messages_search ON messages USING gin (search_vector);

ALTER TABLE facts
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple', statement)) STORED;
CREATE INDEX idx_facts_search ON facts USING gin (search_vector);

ALTER TABLE events
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
        to_tsvector('simple', title || ' ' || coalesce(location, ''))) STORED;
CREATE INDEX idx_events_search ON events USING gin (search_vector);

ALTER TABLE action_items
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple', description)) STORED;
CREATE INDEX idx_action_items_search ON action_items USING gin (search_vector);

ALTER TABLE decisions
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple', statement)) STORED;
CREATE INDEX idx_decisions_search ON decisions USING gin (search_vector);
