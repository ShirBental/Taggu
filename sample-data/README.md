# Sample data

Every conversation in this directory is invented. No real chat export belongs here, or anywhere else
in the repository: `.gitignore` is set up to keep real exports out, and this directory is the
exception that is explicitly allowed in.

Load them with:

```
TAGGU_LOAD_SAMPLE_DATA=true mvn -pl taggu-app spring-boot:run
```

or import one at a time through `POST /api/import/whatsapp`.
