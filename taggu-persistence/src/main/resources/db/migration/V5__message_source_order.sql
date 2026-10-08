-- Keeps messages that share a timestamp in the order their source gave them.
--
-- Android exports record time to the minute, so a quick exchange is several messages with one
-- timestamp. Nothing stored said which came first: reads broke the tie on source_message_id, which for
-- WhatsApp is a hash, so "Want to come Sunday?" / "Sure" could come back as "Sure" / "Want to come
-- Sunday?".
--
-- source_order is the message's position in the source it was imported from. It only ever breaks ties
-- between equal timestamps. Written in SQL both PostgreSQL and H2 accept, like V1; adds a column and
-- an index and changes no other data.

ALTER TABLE messages ADD COLUMN source_order BIGINT DEFAULT 0 NOT NULL;

-- Messages imported before this migration get their position from the line number the adapter
-- recorded as metadata. A message with no such record keeps 0 and falls back to the old tie-break.
UPDATE messages
   SET source_order = COALESCE(
           (SELECT CAST(mm.metadata_value AS BIGINT)
              FROM message_metadata mm
             WHERE mm.message_id = messages.id
               AND mm.metadata_key = 'lineNumber'),
           0);

CREATE INDEX idx_messages_conversation_order ON messages (conversation_id, sent_at, source_order);
