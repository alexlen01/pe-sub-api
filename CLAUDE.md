# pe-sub-api — Development Rules

Spring Boot 3.5 / Java 21 REST API. Runs at `http://localhost:3001`.

---

## Before Touching Any File

1. **Grep before Read.** Use Grep or Glob to locate the exact file before opening it.
2. **Read only what you need.** Use `offset`/`limit` on large files. Do not speculatively read whole files.
3. **Keep layers separate.** Controllers → Services → Repositories. Never put DB logic in a `@RestController`.
4. **Never expose JPA entities directly.** Always convert to a Java record DTO before returning a response.

---

## Change Workflow

```
Grep/Glob → Read relevant section → Understand layer → Edit → Verify
```

- **Edit**: make the minimal change. Do not refactor surrounding code.
- **Verify**: confirm the endpoint responds correctly at `localhost:3001` after any change.
- If a fix fails twice, re-read the service/entity chain from scratch and state the root cause before trying again.

---

## Test Coverage Requirements

- Every endpoint must have an integration test hitting a real in-memory or Testcontainers PostgreSQL instance — no mocked repositories.
- Any field added to an entity must have a corresponding assertion that it round-trips through the API (POST → GET → field present).
- Hardcoded values in seed/test data must be clearly marked `// TEST ONLY` and must not appear in production code paths.

---

## Documentation Requirements

After any significant change (new endpoint, schema migration, changed response shape):

1. Update the relevant section of `README.md` — new field, new endpoint, or changed response shape.
2. Update `pe-sub-docs/openapi.yaml`.
3. Do not create separate documentation files.

---

## Java 21 & Spring Boot 3.5 Rules

1. Adhere to Clean Code and SOLID principles. Prioritize modularity, single responsibility, and dependency injection over quick fixes.
2. Use Java Records for DTOs and immutable data containers.
3. Use Pattern Matching for switch blocks and `instanceof` checks.
4. Use Sequenced Collections (`.getFirst()`, `.getLast()`) instead of manual indexing.
5. Use text blocks (`"""`) for multi-line strings and SQL queries.
6. **Virtual-thread safe:** assume `spring.threads.virtual.enabled=true`. Write clean blocking imperative code. Use `ReentrantLock`, never `synchronized`, to avoid thread pinning.
7. Use modern Spring Boot 3.5 standards (`@RestControllerAdvice`, `ProblemDetail`, etc.).
8. Use standard Spring Data JPA interfaces. Always return `Optional` for searches that may return empty. Keep all DB operations inside `@Service` classes.

---

## RESTful API Rules

1. Use nouns for endpoints (`/facilities`, `/lps`). Never use verb paths like `/getFacilities`.
2. Use HTTP methods correctly: GET to read, POST to create, PUT to replace, PATCH to partially update, DELETE to remove.
3. Return proper status codes: `200 OK`, `201 Created`, `400 Bad Request`, `404 Not Found`, `500 Internal Server Error`.
4. Always accept and return JSON. Use camelCase consistently.
5. Use Java Records for all request/response DTOs. Never use Lombok or plain classes for API bodies.
6. Handle errors globally via `@RestControllerAdvice`. Return Spring's `ProblemDetail` structure.
7. Write imperative, virtual-thread-safe code. No reactive (WebFlux) code.
