-- Provenance enforcement, PostgreSQL only.
--
-- The application already refuses to build a Fact, Event, ActionItem or Decision without evidence:
-- the Provenance type cannot be constructed empty. This migration puts the same rule in the database,
-- where it also holds for a bulk load, a future writer or a bug. The check is deferred to commit time
-- because a knowledge row and its sources are inserted as separate statements.
--
-- This migration is kept apart from V1 because it uses plpgsql. Tests run V1 alone against H2, which
-- keeps the portable schema honest while leaving this rule to the database the system actually ships on.

CREATE OR REPLACE FUNCTION taggu_require_provenance() RETURNS TRIGGER AS $$
DECLARE
    source_count INTEGER;
BEGIN
    EXECUTE format('SELECT count(*) FROM %I WHERE %I = $1', TG_ARGV[0], TG_ARGV[1])
        INTO source_count
        USING NEW.id;
    IF source_count = 0 THEN
        RAISE EXCEPTION 'row %.% was stored without provenance', TG_TABLE_NAME, NEW.id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER facts_require_provenance
    AFTER INSERT ON facts DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION taggu_require_provenance('fact_sources', 'fact_id');

CREATE CONSTRAINT TRIGGER events_require_provenance
    AFTER INSERT ON events DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION taggu_require_provenance('event_sources', 'event_id');

CREATE CONSTRAINT TRIGGER action_items_require_provenance
    AFTER INSERT ON action_items DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION taggu_require_provenance('action_item_sources', 'action_item_id');

CREATE CONSTRAINT TRIGGER decisions_require_provenance
    AFTER INSERT ON decisions DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION taggu_require_provenance('decision_sources', 'decision_id');
