# Examples integration webapp

This webapp packages **servlet examples** from the repository `examples/` tree
for end-to-end testing inside the Gumdrop servlet container. Example `.java`
files stay in `examples/`; Ant compiles them into `WEB-INF/classes/` here
(see `integration-webapp-examples-build` in `build.xml`).

## Context path

Mounted at **`/examples`** by `ExamplesWebappIntegrationTest` (and future
integration tests). URLs look like:

- `/examples/index.html`
- `/examples/trailer-fields?demo=basic`
- `/examples/hello.jsp`

## Adding an example servlet

1. Keep (or add) the servlet under `examples/<name>/` with a normal Java package.
2. Register it in `WEB-INF/web.xml`.
3. Add the source directory to `integration-webapp-examples-build` in `build.xml`.
4. Extend `ExamplesWebappIntegrationTest` with an HTTP assertion.

JSP and static assets live in this directory only. Do not rely on flat
`examples-compile` for servlet runtime; the container needs this layout.

## Build

```bash
ant integration-webapp-examples-build
ant integration-test-servlet
```
