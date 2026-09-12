# Jakarta Servlet 6.1 — implementation plan

Branch: **`servlet6`** (long-lived; merge to `main` when complete).

**Baseline after #443:** SCI discovery/startup, recursive `WEB-INF/classes` scan,
programmatic registration from `onStartup`, `@HandlesTypes` when
`metadata-complete=false`.

**Target:** compile and run against **`jakarta.servlet-api:6.1.0`**, with spec
6.0/6.1 API and behaviour gaps closed.

---

## Phase 1 — Jakarta namespace and build (foundation) ✓

1. **Dependencies** ✓
   - `boms/versions.properties`: `jakarta.servlet.api.version=6.1.0`
   - `build.xml`: fetch `jakarta.servlet-api-6.1.0.jar`; update `servlet.jar` property
   - Maven POMs: `pom.xml`, `central/gumdrop-*.xml`, `boms/gumdrop-j2ee-bom`, `boms/gumdrop-external-deps`
   - Container `lib/` layout and `Container-Dependencies` manifest entries

2. **JPMS** ✓
   - `ant/modules/servlet/module-info.java`: `requires jakarta.servlet`
   - `ant/modules/core/module-info.java` / `gumdrop/module-info.java`: export `jakarta.servlet.jsp` (not `javax`)

3. **In-tree JSP API stubs** ✓
   - Move `src/javax/servlet/jsp/` → `src/jakarta/servlet/jsp/`
   - Update `ant/compile-modules.xml` `javax/**` includes → `jakarta/**` for stub compilation

4. **Source rename (mechanical)** ✓
   - `import javax.servlet.*` → `jakarta.servlet.*` (all `src/`, `test/`, `examples/`, `web/`)
   - Request/response attribute prefixes: `javax.servlet.*` → `jakarta.servlet.*`
   - SCI service path: `jakarta.servlet.ServletContainerInitializer`
   - JSP generator imports in `JSPCodeGenerator.java`, `JSPPage.java`

5. **Verify** ✓ — `ant clean build junit-test` green. Minimal compile fixes for APIs removed in
   6.0/6.1 (`ServletConnection`, `sendRedirect(loc,sc,clearBody)`, `SessionCookieConfig.getAttributes`,
   etc.); full behaviour wired in Phase 2.

---

## Phase 2 — Servlet 6.0 / 6.1 API gap-fill ✓

Implement on `Request`, `Response`, stream wrappers, `ErrorRequest`, etc.

| Version | Item | Status |
| --- | --- | --- |
| 5.0 | `getRequestId()`, `getProtocolRequestId()`; `ServletConnection` on upgrade | ✓ Wired from `HTTPResponseState` / `Stream` |
| 6.0 | `read`/`write(ByteBuffer)` on servlet streams; `setCharacterEncoding(Charset)` on `Response`; `sendRedirect(location, sc, clearBody)`; error dispatch `jakarta.servlet.error.method` + `query_string`; deprecate `PushBuilder` (keep working) | ✓ |
| 6.1 | `jakarta.servlet.request.secure_protocol` attribute; status 425/507/510 via `HTTPConstants` + `sendError`; `HttpSession.getAccessor()` for use outside active request | ✓ |

Notes:
- `jakarta.servlet-api:6.1.0` has no `getSecureProtocol()` method or `SC_TOO_EARLY`/`SC_INSUFFICIENT_STORAGE`/`SC_NOT_EXTENDED` constants; secure protocol is exposed via the **`jakarta.servlet.request.secure_protocol`** request attribute per the 6.1 spec.
- `getReader(Charset)` is not a separate method on the 6.1.0 API surface; charset selection uses `setCharacterEncoding(Charset)` (default on `ServletRequest`) plus `getReader()`.

Tests: `Servlet61ApiTest`, `SessionTest.testGetAccessorAllowsAccessOutsideRequest`.

---

## Phase 3 — Deployment descriptors and docs ✓

- Validate `web-app` version 6.1 parsing (existing major/minor parser) — `DeploymentDescriptorParserTest.testParseJakartaWebApp61`
- Update `web/servlet.html`, `package-info.java`, hero copy: Servlet **6.1** not 4.0
- Update examples under `examples/servlet-*` (READMEs for server push and trailer fields)
- Optional: spec-section checklist / TCK-oriented tracking issue (deferred)

---

## Phase 4 — Coupled follow-ups (same branch or follow-on PRs)

- JSP runtime alignment with Jakarta JSP 4.0 (EE 11) if claiming full web stack
- Cookie `Partitioned` / 6.1 cookie attrs audit on `CookieConfig`
- `javax.annotation` / `javax.persistence` scan annotations → Jakarta equivalents where still used in `Context.scanClass`

---

## Suggested commit slices on `servlet6`

1. Build + JPMS + dependency coordinates (no source rename yet) — *or* combine with slice 2
2. Jakarta rename + compile fix
3. 6.0/6.1 API methods + tests
4. Docs + examples

---

## Out of scope on this branch

Role-agnostic refactor (Workstream C), telemetry spin-offs, TLS backlog (#445–447).
