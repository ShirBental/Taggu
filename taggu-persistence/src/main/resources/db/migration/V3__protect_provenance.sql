-- Closes the ways stored knowledge could lose its evidence after it was written. PostgreSQL only,
-- like V2. Nothing here changes or removes existing rows.
--
-- V2 checks provenance when a knowledge row is inserted. Three gaps remained:
--
--   1. Evidence could be deleted out from under knowledge. Every *_sources.message_id was declared
--      ON DELETE CASCADE, so deleting a message silently removed the source rows that cited it and
--      left a fact, event, action item or decision with nothing behind it.
--   2. Source rows could be deleted or re-pointed directly, with the same result.
--   3. A knowledge row inserted and deleted again inside one transaction made V2's trigger fail at
--      commit, because it counted sources for a row that no longer existed.
--
-- (1) is fixed by replacing the cascading foreign keys with deferred, non-cascading ones: a message
-- that is evidence cannot be deleted on its own. Deleting a whole conversation still works, because
-- the check runs at commit, by which time the conversation's knowledge has cascaded away with it.
-- (2) is fixed by a deferred trigger on the source tables. (3) by re-defining V2's function.

-- ---------------------------------------------------------------------------
-- 1. Evidence messages cannot be deleted from under the knowledge they support
-- ---------------------------------------------------------------------------

-- The original constraints were created unnamed, so they are looked up rather than assumed.
DO $$
DECLARE
    source_table TEXT;
    old_constraint TEXT;
BEGIN
    FOREACH source_table IN ARRAY ARRAY['fact_sources', 'event_sources', 'action_item_sources', 'decision_sources']
    LOOP
        FOR old_constraint IN
            SELECT con.conname
              FROM pg_constraint con
             WHERE con.conrelid = format('%I', source_table)::regclass
               AND con.confrelid = 'messages'::regclass
               AND con.contype = 'f'
        LOOP
            EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', source_table, old_constraint);
        END LOOP;
        EXECUTE format(
            'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (message_id) REFERENCES messages (id) '
                || 'DEFERRABLE INITIALLY DEFERRED',
            source_table, 'fk_' || source_table || '_message');
    END LOOP;
END;
$$;

-- ---------------------------------------------------------------------------
-- 2. The last source row of a knowledge row cannot be removed or re-pointed
-- ---------------------------------------------------------------------------

CREATE FUNCTION taggu_keep_provenance() RETURNS TRIGGER AS $$
DECLARE
    owner_id UUID;
    owner_exists BOOLEAN;
    source_count INTEGER;
BEGIN
    -- TG_ARGV: the knowledge table, and this table's column pointing at it.
    EXECUTE format('SELECT ($1).%I', TG_ARGV[1]) INTO owner_id USING OLD;
    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I WHERE id = $1)', TG_ARGV[0])
        INTO owner_exists
        USING owner_id;
    IF NOT owner_exists THEN
        RETURN NULL; -- the knowledge row itself was deleted; its sources went with it
    END IF;
    EXECUTE format('SELECT count(*) FROM %I WHERE %I = $1', TG_TABLE_NAME, TG_ARGV[1])
        INTO source_count
        USING owner_id;
    IF source_count = 0 THEN
        RAISE EXCEPTION 'row %.% would be left without provenance', TG_ARGV[0], owner_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER fact_sources_keep_provenance
    AFTER DELETE OR UPDATE ON fact_sources DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION taggu_keep_provenance('facts', 'fact_id');

CREATE CONSTRAINT TRIGGER event_sources_keep_provenance
    AFTER DELETE OR UPDATE ON event_sources DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION taggu_keep_provenance('events', 'event_id');

CREATE CONSTRAINT TRIGGER action_item_sources_keep_provenance
    AFTER DELETE OR UPDATE ON action_item_sources DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION taggu_keep_provenance('action_items', 'action_item_id');

CREATE CONSTRAINT TRIGGER decision_sources_keep_provenance
    AFTER DELETE OR UPDATE ON decision_sources DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION taggu_keep_provenance('decisions', 'decision_id');

-- ---------------------------------------------------------------------------
-- 3. V2's insert check ignores rows that were deleted again before commit
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION taggu_require_provenance() RETURNS TRIGGER AS $$
DECLARE
    still_exists BOOLEAN;
    source_count INTEGER;
BEGIN
    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I WHERE id = $1)', TG_TABLE_NAME)
        INTO still_exists
        USING NEW.id;
    IF NOT still_exists THEN
        RETURN NULL;
    END IF;
    EXECUTE format('SELECT count(*) FROM %I WHERE %I = $1', TG_ARGV[0], TG_ARGV[1])
        INTO source_count
        USING NEW.id;
    IF source_count = 0 THEN
        RAISE EXCEPTION 'row %.% was stored without provenance', TG_TABLE_NAME, NEW.id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
