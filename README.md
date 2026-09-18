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

Every fact, event, action item and decision knows which messages support it. This is enforced three
times over, deliberately:

1. `Provenance` cannot be constructed empty, so knowledge without evidence is not a representable
   value in the domain model.
2. `KnowledgeStore` writes the `*_sources` rows in the same transaction as the item, and refuses
   otherwise.
3. PostgreSQL has a deferred constraint trigger per knowledge table (migration `V2`) that rejects any
   row reaching commit without a source row, whatever wrote it.

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
pointing at the right messages.

## API

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/api/import/whatsapp` | multipart `file`, plus optional `title`, `conversationId`, `zone`, `localeHint`, `includeRawLines` |
| `GET` | `/api/conversations` | all conversations with participants and message counts |
| `GET` | `/api/conversations/{id}` | one conversation |
| `GET` | `/api/conversations/{id}/messages` | paged with `limit` and `offset`, tags included |
| `GET` | `/api/conversations/{id}/knowledge` | entities, facts, events, action items, decisions, each with `sourceMessageIds` |
| `GET` | `/api/search?q=` | message text search, optionally scoped with `conversationId` |

Search is a case-insensitive substring match with wildcards escaped. It is portable, needs no
extension and no extra column, and it does not pretend to rank. Replacing it with PostgreSQL
full-text search means changing one query in `ConversationStore`.

## The WhatsApp parser

`WhatsAppTextExportAdapter` handles what real exports actually contain:

- Android and iOS line shapes, 12-hour and 24-hour clocks, `dd/mm/yy` and `yyyy-mm-dd` dates.
- The invisible characters iOS inserts — left-to-right marks, narrow no-break spaces — which make an
  otherwise ordinary line unparseable.
- Multiline messages: a line with no timestamp header belongs to the message above it.
- System notices (`Dana added Ron`, encryption notices) as `SYSTEM` with no sender, told apart from
  what people wrote by the absence of a `Sender: ` prefix.
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
mvn test
```

48 tests, no Docker and no network.

| Module | Covers |
| --- | --- |
| `taggu-core` | provenance cannot be empty, id determinism, window slicing |
| `taggu-ingest-whatsapp` | 13 parser tests over 9 synthetic fixtures |
| `taggu-extraction` | tags, entities, facts, events, actions, decisions, and that every item cites evidence from its own window |
| `taggu-persistence` | stores and stores idempotently, search behaviour, knowledge round-trips with evidence |
| `taggu-app` | the whole pipeline through the HTTP API |

Parser fixtures cover normal messages, multiline messages, malformed lines, system messages, multiple
participants, punctuation and URLs, context-dependent exchanges, the iOS shape and month-first dates.

### Testing persistence without Docker

Tests run against in-memory H2 in PostgreSQL mode and apply the project's **own `V1` migration**,
rather than a schema generated from the code, so the migration cannot drift from what the stores
expect. `V1` is written in SQL that both databases accept; `V2` holds the plpgsql provenance triggers
and is skipped, since H2 cannot run them.

That is the honest limit of this setup, and it is where Testcontainers goes when it is wanted: a
`@Testcontainers` PostgreSQL in `taggu-persistence` would run `V1` and `V2` together and let the
trigger behaviour be asserted — an insert of a fact with no sources should fail at commit. The stores
need no change for it; only `PersistenceTestBase` would gain a container-backed variant.

(For the record, the `V2` triggers were verified by hand against PostgreSQL 16 during development:
inserting a fact with no `fact_sources` row is rejected with `row facts.<id> was stored without
provenance`.)

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

## Phase 2

In rough order of what would pay off soonest:

1. **A model-backed `KnowledgeExtractor`** behind the existing interface, with the rule-based one kept
   as a fallback and as a test oracle. `ExtractionResult` already records which extractor produced
   each item, so results can be re-attributed when it lands.
2. **PostgreSQL full-text search**: a `tsvector` column, a GIN index, and ranked results — one query
   in `ConversationStore`.
3. **Testcontainers** for the migrations and the `V2` triggers, as described above.
4. **Entity resolution.** "Mom", "Mum" and a phone number are one person; `participants` is already
   keyed by source identity, ready for a merge table.
5. **A second source adapter** — Gmail or Telegram — which is the real test of whether the boundary
   holds.
6. **Re-extraction as a job**: extraction currently runs inline with import, which is fine for one
   file and not for a backfill of ten years.
7. **Action item and event lifecycle**: marking things done, spotting a later message that cancels an
   earlier plan.
