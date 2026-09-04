# NowServing — backend

Spring Boot API, Flyway migrations, and the integration test suite.

```bash
docker compose up -d      # Postgres :5433, Redis :6380
./gradlew bootRun         # API on :8080
./gradlew test            # integration tests (Testcontainers)
```

Optional keys (TomTom, VAPID) go in `config/application.properties` — copy
`config/application-example.properties`, which is gitignored. Nothing here is
required to run the core queue flow.

See the [root README](../README.md) for the full picture and
[docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) for how it's put together.
