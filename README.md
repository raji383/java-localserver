# Java Local Server

Minimal Java server scaffold with routing, CGI hook points, config loading, and basic error responses.

## Layout

- `src/Main.java` — entry point
- `src/Server.java` — server lifecycle
- `src/Router.java` — request routing
- `src/CGIHandler.java` — CGI execution stub
- `src/ConfigLoader.java` — JSON config reader
- `src/error.java` — HTTP error helpers
- `src/utils/Session.java` — session helper
- `src/utils/Cookie.java` — cookie helper

## Run

```bash
mkdir -p out
javac -d out src/*.java src/utils/*.java
java -cp out Main
```

You can pass a custom config file path:

```bash
java -cp out Main config.json
```

## Notes

- The current implementation is a scaffold, not a full web server.
- `CGIHandler` and routing are ready for extension.
