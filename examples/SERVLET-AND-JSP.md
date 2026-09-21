# Servlet and JSP examples

Servlet and JSP teaching code under `examples/` is **not** run via `ant examples-compile`.
It is packaged and tested as a web application:

- **Tree:** `test/integration/webapp-examples/`
- **Build:** `ant integration-webapp-examples-build`
- **Tests:** `ExamplesWebappIntegrationTest` (via `ant integration-test-servlet`)

Sources stay in `examples/<topic>/`; Ant copies compiled classes into
`WEB-INF/classes/`. Register new servlets in `webapp-examples/WEB-INF/web.xml`
and add the source directory to `integration-webapp-examples-build` in `build.xml`.

See [test/integration/webapp-examples/README.md](../test/integration/webapp-examples/README.md).
