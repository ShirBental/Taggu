# Taggu

Taggu turns exported conversations into structured, searchable personal knowledge: tags, entities,
facts, events, action items and decisions, each one pointing back at the messages it came from.

It runs on your machine and keeps your conversations there. Nothing is sent anywhere.

WhatsApp `.txt` exports are the first source, and only the first: the extraction and knowledge layers
never learn which product a message came from, so Gmail, SMS, Slack or Telegram become new adapters
rather than a rewrite.

This is Phase 1. The pipeline works end to end with a deliberately small rule-based extractor. The
extraction interface and the knowledge models are the real thing; the rules behind them are not.

## Quick start

### Prerequisites

Java 21, Maven and a PostgreSQL you can write to. On macOS, with Homebrew:

```bash
brew install openjdk@21 maven postgresql@16

# Homebrew keeps both of these off the default paths, so point macOS and the shell at them
sudo ln -sfn $(brew --prefix)/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 21)' >> ~/.zshrc
echo "export PATH=\"$(brew --prefix)/opt/postgresql@16/bin:\$PATH\"" >> ~/.zshrc
exec zsh
```

Check with `java -version`, `mvn -v` and `psql --version`.

### Build

```bash
mvn clean install
```

That runs the tests too, and they need neither a database nor a network, so a green build tells you
the code is sound before PostgreSQL is involved at all.

### Database

```bash
brew services start postgresql@16          # or however your PostgreSQL starts

createdb taggu
psql -d postgres -c "CREATE ROLE taggu LOGIN PASSWORD 'taggu'" \
                 -c "ALTER DATABASE taggu OWNER TO taggu"
```

`psql -d postgres` matters: with no database named, `psql` connects to one named after your account,
which a fresh install usually does not have.

You create no tables. Flyway builds the schema on first start.

### Configure and run

```bash
cp config/application.yml.example config/application.yml
$EDITOR config/application.yml

java -jar taggu-app/target/taggu-app-0.1.0-SNAPSHOT.jar
```

Spring Boot reads `./config/application.yml` from the working directory and it overrides the packaged
defaults. The file is gitignored, since it describes one machine and holds a database password.

The app listens on `127.0.0.1:8080` — the loopback interface, not the network — and runs in the
foreground, so Ctrl-C stops it. A healthy start applies two Flyway migrations and, if sample data is
enabled, logs one line per imported conversation.

Then:

```bash
curl localhost:8080/api/conversations
curl "localhost:8080/api/search?q=quoted"
curl localhost:8080/api/conversations/<id>/knowledge
```

To import an export of your own:

```bash
curl -F file=@"WhatsApp Chat with Mom.txt" \
     -F title="Mom" \
     -F zone="America/New_York" \
     localhost:8080/api/import/whatsapp
```

`zone` matters. Chat exports record wall-clock time with no offset, so the zone is something you tell
the importer rather than something it can read. Left out, it falls back to `taggu.default-zone`.
Importing the same file twice is safe: it stores nothing the second time.

### Configuration

Settings come from three places, each overriding the one above it: the packaged defaults, your
`config/application.yml`, and the environment. The file is the right place for a workstation; the
environment variables are there for a container or a CI job, where a file would be awkward.

| Setting in `config/application.yml` | Environment variable | Default | What it does |
| --- | --- | --- | --- |
| `spring.datasource.url` | `TAGGU_DB_URL` | `jdbc:postgresql://localhost:5432/taggu` | Database to use |
| `spring.datasource.username` / `.password` | `TAGGU_DB_USER` / `TAGGU_DB_PASSWORD` | `taggu` / `taggu` | Database credentials |
| `server.port` | `TAGGU_PORT` | `8080` | HTTP port |
| `server.address` | `TAGGU_BIND_ADDRESS` | `127.0.0.1` | Interface to bind to |
| `taggu.default-zone` | `TAGGU_DEFAULT_ZONE` | `UTC` | Zone used to read export timestamps |
| `taggu.sample-data.enabled` | `TAGGU_LOAD_SAMPLE_DATA` | `false` | Import `sample-data/` on startup |
| `taggu.extraction.enabled` | — | `true` | Run extraction after an import |
| `taggu.extraction.focus-window-size` | — | `8` | Messages one extraction window owns |
| `taggu.extraction.lead-in-size` | — | `4` | Earlier messages a window may read |

## Architecture

```
WhatsApp export (.txt)
        |
        v
  Source adapter                  taggu-ingest-whatsapp   <-- the only code that knows the format
        |
        v
  Canonical message               taggu-core
        |
        v
  Conversation window             taggu-core
        |
        v
  Knowledge extractor             taggu-extraction        <-- sees canonical messages only
        |
        v
  Persistence + provenance        taggu-persistence
        |
        v
  REST API                        taggu-app
```

Five Maven modules, and the dependency arrows only point inward:

| Module | Contains | Depends on |
| --- | --- | --- |
| `taggu-core` | Canonical model, knowledge model, provenance, extraction and source interfaces | nothing (plain Java 21, no Spring) |
| `taggu-ingest-whatsapp` | The WhatsApp `.txt` adapter | `taggu-core` |
| `taggu-extraction` | `RuleBasedKnowledgeExtractor` | `taggu-core` |
| `taggu-persistence` | Flyway migrations, JDBC stores, read models | `taggu-core` |
| `taggu-app` | Import orchestration, REST API, configuration | all of the above |

`taggu-core` has no Spring in it at all, which is not tidiness for its own sake: it means the domain
model and the extraction contract can be used from a batch job, a CLI or a test without a container.

### How the source stays out of extraction

`MessageSourceAdapter` is the boundary. An adapter reads a format and returns a `ParsedConversation`
of `CanonicalMessage` records. Above it, `SourceType` is a label used to namespace identifiers, and
nothing branches on it. `KnowledgeExtractor` receives an `ExtractionContext` — a window of canonical
messages — and could not tell WhatsApp from Slack if it tried.

### Why windows, not messages

Messages rarely mean anything alone:

```
Mom:  Want to come Sunday?
Yael: Sure
Mom:  Around 4?
Yael: Perfect
```

That is one event, spread over four messages. So an extractor is never handed a single message. An
`ExtractionContext` has a `leadIn` (earlier messages, readable for interpretation) and a `focus`
(messages this window is responsible for). Focus blocks do not overlap, so overlapping context cannot
produce the same item twice. The rule-based extractor turns the exchange above into one event at
Sunday 16:00, citing three of the four messages.

### Provenance

Every fact, event, action item and decision knows which messages support it. This is enforced at
every layer, deliberately:

1. `Provenance` cannot be constructed empty, so knowledge without evidence is not a representable
   value in the domain model.
2. `ExtractionValidator` checks what an extractor returns against the window it was shown, before
   anything is stored. An item citing a message the extractor never saw, or resting on lead-in
   messages alone, is dropped and counted (`rejectedKnowledgeItems` in the import response). The
   rule-based extractor never trips this; it is there for the model-backed one, which can cite a
   message that does not exist.
3. `KnowledgeStore` writes the `*_sources` rows in the same transaction as the item, and refuses
   otherwise.
4. PostgreSQL has a deferred constraint trigger per knowledge table (migration `V2`) that rejects any
   row reaching commit without a source row, whatever wrote it.
5. PostgreSQL also keeps the evidence there afterwards (migration `V3`). A message that is cited
   cannot be deleted on its own, and the last source row of a knowledge row cannot be deleted or
   re-pointed. Deleting a whole conversation still removes its messages and knowledge together. If
   knowledge in one conversation cites a message in another, the cited conversation cannot be
   deleted until the citing knowledge (or its conversation) is gone; the delete is refused rather
   than stripping the evidence. Tags, entities and participants shared between conversations
   survive the deletion of one of them.

The API returns `sourceMessageIds` on every knowledge item, because a caller who cannot see the
evidence cannot check the claim.

## Database model

Flyway migrations live in `taggu-persistence/src/main/resources/db/migration`.

```
conversations ──< conversation_participants >── participants
      │                                              │
      └──< messages >────────────────────────────────┘
             │  │
             │  └──< message_metadata
             │
             ├──< message_tags >── tags
             ├──< entity_mentions >── entities
             ├──< fact_sources >── facts
             ├──< event_sources >── events
             ├──< action_item_sources >── action_items
             └──< decision_sources >── decisions

import_runs   (counts and a file name; never message content)
```

Three deviations from the tables listed in the brief, each on purpose:

- **`participants` are keyed by source identity, not by conversation.** A participant row is
  `(source, source_identifier)`, joined to conversations through `conversation_participants`. The same
  person seen in two threads is one row, which is what will let knowledge be joined across threads
  later without a migration.
- **`message_metadata` is a table, not a JSON column.** Adapters can record whatever they need as
  rows, and no reader has to understand a document format to query it.
- **`entity_mentions` was added.** The brief lists `entities` but no link to messages. Without it an
  entity floats free of the conversation it was mentioned in, and provenance is the point of this
  system.

Identifiers are derived, not random: a message id is a hash of `(source, conversation, message
identity)`, and a WhatsApp message's identity is its timestamp, sender and text, since exports carry
no ids. Re-importing the same export is therefore a no-op, and provenance recorded earlier keeps
pointing at the right messages. The timestamp used is the wall-clock time written in the file, not
the instant it resolves to, so importing the same export under a different `zone` is a no-op too.

Messages that share a timestamp, which is most of a quick exchange in an export that records time to
the minute, are kept in the order their source gave them (`messages.source_order`, migration `V5`).

Migrations: `V1` is the schema and `V5` adds the source order; both are SQL that PostgreSQL and H2
accept, and the fast test suite runs exactly these. `V2` to `V4` are PostgreSQL only: the provenance
triggers, the protection of stored evidence, and full-text search.

Upgrading an existing database needs nothing but starting the new version: Flyway applies `V3` to
`V5` in order. They add constraints, triggers, columns and indexes and rewrite no stored text. `V4`
and `V5` each rewrite the `messages` table once to add a column, which takes a moment on a large
database. `V3` will fail, and leave the database at `V2`, only if `fact_sources` or its siblings
somehow already point at messages that do not exist.

## API

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/api/import/whatsapp` | multipart `file`, plus optional `title`, `conversationId`, `zone`, `localeHint`, `includeRawLines` |
| `GET` | `/api/conversations` | all conversations with participants and message counts |
| `GET` | `/api/conversations/{id}` | one conversation |
| `GET` | `/api/conversations/{id}/messages` | paged with `limit` and `offset`, tags included |
| `GET` | `/api/conversations/{id}/knowledge` | entities, facts, events, action items, decisions, each with `sourceMessageIds` |
| `GET` | `/api/search?q=` | full-text search over messages and extracted knowledge, optionally scoped with `conversationId`, capped with `limit` |

Search is PostgreSQL full-text search (`V4`: a generated `tsvector` column and a GIN index on
messages and on each knowledge table). `q` is read like a search box: every word must match, in any
order; `"quoted words"` must appear together; `or` gives alternatives; a leading `-` excludes.
Nothing typed into it is a syntax error. Results come best match first.

Each result has a `kind` (`MESSAGE`, `FACT`, `EVENT`, `ACTION_ITEM`, `DECISION`), the `itemId` of
what matched, a `rank`, and `sourceMessageIds`: the message itself, or the evidence behind the
knowledge. The fields a result always had (`messageId`, `conversationId`, `conversationTitle`,
`senderName`, `sentAt`, `text`) are still there for every kind; for knowledge they describe its
earliest evidence, and `senderName` is `null`.

Matching is on whole words with the `simple` configuration: no stemming and no stop words. That makes
it behave the same in every language. What that means in practice, each pinned by a test in
`SearchStorePostgresTest`:

| Typed | Finds | Does not find |
| --- | --- | --- |
| `contractor` | "The **Contractor** called" (case is ignored) | |
| `contract` | | "contractor" — no partial or prefix matching |
| `quote` | | "quoted", "quotes" — no stemming |
| `1,550` or `550` | "$1,550" | `1550` does not |
| `follow` or `follow-up` | "a follow-up call" | `followup` does not |
| `let` or `let's` | "Let's go" | `lets` does not |
| `example.com` | a link to that host | words inside the link's path |
| `café` | "café" | `cafe` does not — accents are significant |

There is no typo tolerance and there are no synonyms. If a search comes back empty, try the exact
form of the word as it was written.

**Ranking** is PostgreSQL's `ts_rank` with its defaults. A result ranks higher the more often the
words occur in it and the closer together they are. Length is not taken into account: a one-word
message and a paragraph that each mention the word once rank the same. Ties go to the more recent.
Messages and knowledge are ranked on one scale and interleaved, so a fact often appears next to the
message it was extracted from. `rank` is only meaningful next to other results of the same query.

Errors are `{status, error, message, timestamp}` and never quote the request or the file. A request
that cannot be understood is a `400`, an unknown conversation a `404`, an oversized upload a `413`,
and a file with no messages in it a `422` that stores nothing.

## The WhatsApp parser

`WhatsAppTextExportAdapter` handles what real exports actually contain:

- Android and iOS line shapes, 12-hour and 24-hour clocks, `dd/mm/yy` and `yyyy-mm-dd` dates, and
  times written with dots (`21.05`) as well as colons.
- The invisible characters iOS inserts — left-to-right marks, narrow no-break spaces — which make an
  otherwise ordinary line unparseable.
- Multiline messages: a line with no timestamp header belongs to the message above it.
- System notices (`Dana added Ron`, encryption notices) as `SYSTEM` with no sender, told apart from
  what people wrote by the absence of a `Sender: ` prefix. iOS gives notices a sender, often the
  group's name; those are recognised by the invisible mark iOS puts before platform-written text
  together with the wording of the notice, so the group name does not become a participant. The
  wording is matched in English only.
- WhatsApp's own annotations: `<This message was edited>` is removed from the text and kept as
  `edited` metadata, and the `~` before the name of someone not in the contacts is dropped, so
  `~ Noa` and `Noa` are one participant.
- Media, attachment, location, contact and deletion placeholders, classified rather than stored as
  something a participant said.
- Malformed lines, which become a `ParseIssue` while the rest of the file still imports.

**Day/month order is inferred from the file, not assumed.** A date component above 12 can only be a
day, so one such date anywhere settles the order for the whole export. Only when a file offers no
evidence at all does a locale hint, and then a day-first default, decide — and that assumption is
reported as a `ParseIssue`, never made silently.

A different export format means a new `MessageSourceAdapter`; nothing in the parser is shared
machinery that a second format would have to fight.

## Extraction

`RuleBasedKnowledgeExtractor` is regular expressions and nothing else. It exists to prove the pipeline
carries knowledge with provenance from end to end, and to be replaced. It finds keyword tags,
organisations named with a legal suffix, places after "at", products after "bought", money amounts as
facts, day-plus-time plans as events, commitments and requests as action items, and explicit choices
as decisions.

It is tuned for precision over recall, and it will still be wrong about plenty. That is expected: a
model-backed `KnowledgeExtractor` drops in behind the same interface and returns the same
`ExtractionResult`, and nothing downstream changes.

## Privacy

- Local-first. The app binds to loopback and makes no outbound calls with imported data.
- **No message content is logged at any level.** Imports log counts; parse issues log a line number
  and a reason through `ParseIssue.summary()`, never the line.
- Unexpected errors return a fixed sentence rather than an exception message, because an exception
  raised mid-parse can easily carry a fragment of what someone wrote.
- Unreadable lines are echoed back by the import endpoint only when `includeRawLines=true` is asked
  for.
- `import_runs` records counts and a file name, never content.
- `.gitignore` ignores every `*.txt` and re-admits only `sample-data/` and test fixtures, so a real
  export dropped into the repository stays out of Git.
- All sample data and all test data is invented.

## Tests

```bash
mvn verify
```

There are two suites in one build.

**The fast suite needs no Docker, no PostgreSQL and no network** (72 tests). Parser, extraction and
core tests need nothing at all. The store and API tests run on in-memory H2 in PostgreSQL mode,
applying the project's own portable migrations (`V1` and `V5`) rather than a schema generated from
the code.

**The PostgreSQL suite** (68 more tests, classes named `*PostgresTest`) runs on a real PostgreSQL
built by Flyway with every migration. H2 cannot run plpgsql, deferred constraints or full-text
search, so this is where the database's own guarantees are asserted:

- every store and API test of the fast suite, run a second time on PostgreSQL;
- the `V2` triggers: knowledge without a source row fails at commit, for all four kinds;
- the `V3` protection: cited messages and last source rows cannot be removed, conversations can,
  including when knowledge, tags, entities or participants are shared between conversations;
- full-text search, at the store and through the endpoint, including what it ranks first and what
  it deliberately does not match;
- `V5` applied to a database that already holds messages.

It starts a throwaway container with Testcontainers when Docker is available. Without Docker, point
it at a dedicated database:

```bash
createdb taggu_test
TAGGU_TEST_DB_URL=jdbc:postgresql://localhost:5432/taggu_test mvn verify
```

`TAGGU_TEST_DB_USER` and `TAGGU_TEST_DB_PASSWORD` are optional; the user defaults to your OS account.
**Every test wipes that database**, so a URL whose database name does not contain `test` is refused.
With neither Docker nor the variable, the PostgreSQL suite is skipped, not failed, and the build
still passes on the fast suite alone. Read a green build with skipped tests as "the PostgreSQL-only
behaviour was not checked".

| Module | Covers |
| --- | --- |
| `taggu-core` | provenance cannot be empty, id determinism, window slicing, extraction validation |
| `taggu-ingest-whatsapp` | 24 parser tests over synthetic exports |
| `taggu-extraction` | tags, entities, facts, events, actions, decisions, that every item cites evidence from its own window, and that nothing it returns is rejected by the validator |
| `taggu-persistence` | stores and stores idempotently, knowledge round-trips with evidence; on PostgreSQL also triggers, evidence protection and search |
| `taggu-app` | the whole pipeline through the HTTP API, request validation, and an extractor that cites evidence it was never shown |

Parser tests cover normal messages, multiline messages, malformed lines, system messages (Android
and iOS), multiple participants, punctuation and URLs, context-dependent exchanges, the iOS shape,
month-first dates, dotted times, edited messages, unsaved-contact names, a byte-order mark with
Windows line endings, ids that do not depend on the import zone, and distinct messages (and exact
repeats) sharing one second or one minute.

All test data is invented. No real export is read by any test.

## Assumptions

- A line with no timestamp header is a continuation of the message above it. That is the only correct
  reading of the format, and it means a corrupted line in the middle of a conversation is absorbed
  into the previous message rather than reported.
- A two-digit year is in the 2000s.
- A bare hour in a social context ("around 4") means the afternoon, recorded as `APPROXIMATE`.
- A weekday resolves to the next occurrence on or after the message's own date.
- A body with no `Sender: ` prefix was written by the platform.
- Exports are read fully into memory. They are single conversations, and the two-pass date inference
  needs to see the whole file.

## Known limits

- **Search needs PostgreSQL.** `GET /api/search` is PostgreSQL full-text search and does not run on
  H2. H2 is a test stand-in only; the application is not meant to be run on it.
- **Messages imported before ids ignored the zone.** Exports imported under the default `UTC` zone
  keep the ids they had. Messages imported earlier under any other zone keep their old ids, so
  re-importing such an export stores those messages once more.
- **A re-import does not correct timestamps.** Importing an export again under a different zone is
  now recognised as the same messages and skipped, which also means the stored `sentAt` values stay
  as first imported.
- **Edited messages and `~` names imported earlier.** Their text or sender is now read differently,
  so their ids differ, and re-importing such an export adds them again beside the old rows.
- **Notice wording is English.** An iOS export in another language has its sender-attributed notices
  stored as ordinary text.
- **Order across two imports of one chat.** `source_order` is a position within one import. Two
  messages with the same timestamp that arrive in different imports are ordered by those positions,
  which is right when the second export continues the first and can be wrong if it starts mid-minute.
- **Two exports of one chat from different phones can differ.** iOS writes seconds and Android does
  not, so the same message exported from each gets two ids.
- **Extraction sees only the uploaded file.** A second, later export is extracted on its own, without
  the stored messages before it as context.
- **Unexpected failures are logged with a stack trace at ERROR.** Exception messages from the
  database can quote a row. Nothing is returned to the caller, but the local log is not content-free
  in that case.
- **No authentication.** The application binds to loopback and relies on that.

## Phase 2

In rough order of what would pay off soonest:

1. **A model-backed `KnowledgeExtractor`** behind the existing interface, with the rule-based one kept
   as a fallback and as a test oracle. `ExtractionResult` already records which extractor produced
   each item, and `ExtractionValidator` already stands between any extractor and the store.
2. **Re-extraction as a job**: extraction currently runs inline with import, over the uploaded file
   only. A model-backed extractor needs it to run over stored conversations, incrementally, with a
   record of which windows have been processed by which extractor.
3. **Entity resolution.** "Mom", "Mum" and a phone number are one person; `participants` is already
   keyed by source identity, ready for a merge table.
4. **Semantic retrieval** beside full-text search, behind `SearchStore`.
5. **A second source adapter** — Gmail or Telegram — which is the real test of whether the boundary
   holds.
6. **Action item and event lifecycle**: marking things done, spotting a later message that cancels an
   earlier plan.
7. **Language-aware search**: a per-language text search configuration beside `simple`, for stemming.
