# Java Server Framework Deployment Size & Speed Comparison

This document compares deployment size, dependencies, and startup characteristics for building a simple microservice-driven web application across five Java frameworks:

- **Gumdrop** (this project)
- **Netty**
- **Jetty**
- **Tomcat**
- **Spring Boot**

---

## Executive Summary

| Framework | Deployment Model | Total JAR Size | Dependencies | Download & Build Time | Notes |
|-----------|------------------|----------------|--------------|---------------|-------|
| **Gumdrop (HTTPService)** | gumdrop.jar + gonzalez OR jsonparser | ~2.6 MB / ~4.4 MB | 2–3 JARs | Seconds | Minimal async microservice |
| **Gumdrop (Servlet)** | gumdrop-container.jar (fat) | ~5.2 MB | Self-contained | Seconds | Full servlet container |
| **Netty** | netty-codec-http + XML or JSON | ~2.3 MB / ~4.5 MB | 6–8 Netty + aalto or Jackson | ~10–30 sec | No servlet, HTTP handler only |
| **Jetty** | jetty-server + embedded | ~8–12 MB | Jetty + JSP compiler | ~30–60 sec | Servlet container |
| **Tomcat** | tomcat-embed-core | ~6–10 MB | Tomcat + Catalina | ~30–60 sec | Servlet container |
| **Spring Boot** | spring-boot-starter-web | ~50–80 MB | Spring + Tomcat + logging + many | ~2 min | Full stack, many transitive deps |

---

## 1. Gumdrop

### Deployment Options

#### Option A: HTTPService (Microservice / Async API)

For a pure async microservice without servlets:

**Required:**
- `gumdrop.jar` — core framework (2,537,896 bytes)
- `gonzalez-1.1.jar` — XML parsing (1.9 MB) **OR** `jsonparser-1.2.jar` — JSON (23,063 bytes)

**Total:** ~4.4 MB (with gonzalez) or ~2.56 MB (with jsonparser only)

**Dependencies:** Downloaded from GitHub Releases on first build. Ant `resolve-deps` fetches:
- jsonparser: https://github.com/cpkb-bluezoo/jsonparser/releases/download/v1.2/jsonparser-1.2.jar
- gonzalez: https://github.com/cpkb-bluezoo/gonzalez/releases/download/v1.1/gonzalez-1.1.jar

**Build:** `ant dist` — downloads 2 JARs (~2 MB total), compiles, produces `gumdrop.jar`. No Maven/Gradle required for build.

#### Option B: Servlet Web Application (Fat JAR)

**Required:**
- `gumdrop-container.jar` — self-contained fat JAR (5.2 MB)

**Bundled inside fat JAR:**
- gumdrop.jar
- gonzalez-1.1.jar
- jsonparser-1.2.jar
- javax.servlet-api-4.0.1.jar (95 KB)
- javax.mail-1.6.2.jar (659 KB)
- javax.annotation-api-1.3.2.jar (27 KB)
- javax.ejb-api-3.2.2.jar (64 KB)
- javax.persistence-api-2.2.jar (165 KB)
- jaxws-api-2.3.1.jar (57 KB)

**Total:** Single 5.2 MB JAR. No external runtime dependencies.

**Startup:** `java -jar gumdrop-container.jar` with config. Fast startup (minimal DI for config only, no reflection-heavy init).

### Gumdrop Measurements (from this repo)

| Artifact | Size |
|----------|------|
| gumdrop.jar | 2,537,896 bytes |
| gumdrop-container.jar | 5.2 MB |
| gonzalez-1.1.jar | 1.9 MB |
| jsonparser-1.2.jar | 23,063 bytes |
| lib/ total (all deps) | ~3.5 MB |

### Sample gumdroprc Configurations

**HTTPService** (e.g. `gumdroprc.http` — async HTTPService, no servlet container:

```xml
<?xml version='1.0' standalone='yes'?>
<gumdrop>
	<service id="myservice" class="com.example.myservice.MyService">
		<listener class="org.bluezoo.gumdrop.http.HTTPListener">
			<property name="port" value="443"/>
			<property name="secure" value="true"/>
			<property name="keystore-file" path="myserver.p12"/>
			<property name="keystore-pass" value="tlspassword"/>
		</listener>
	</service>
</gumdrop>
```

**Servlet container** (`gumdroprc.servlet`):

```xml
<?xml version='1.0' standalone='yes'?>
<gumdrop>
	<service id="http" class="org.bluezoo.gumdrop.servlet.ServletService">
		<property name="container" ref="#mainContainer"/>
		<property name="hot-deploy" value="true"/>
		<context path="" root="myservice.war" distributable="true"/>
		<listener class="org.bluezoo.gumdrop.http.HTTPListener">
			<property name="port" value="443"/>
			<property name="secure" value="true"/>
			<property name="keystore-file" path="myserver.p12"/>
			<property name="keystore-pass" value="tlspassword"/>
		</listener>
	</service>
</gumdrop>
```

Run with: `./start gumdroprc.http` or `./start gumdroprc.servlet`

---

## 2. Netty

### Deployment Model

Netty is a **low-level NIO framework** — there is no servlet container. You implement HTTP via `ChannelInboundHandler` and decode/encode HTTP with `HttpServerCodec`.

### Minimal HTTP Server Dependencies

```xml
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-codec-http</artifactId>
    <version>4.1.121.Final</version>
</dependency>
```

**Transitive dependencies (compile scope):**
- netty-common
- netty-buffer
- netty-transport
- netty-codec
- netty-handler

**Note:** `netty-all` (4.1.69+) is a BOM-style artifact (~4 KB) that declares dependencies; it does not bundle classes. Use individual modules or `netty-all` to pull in everything.

### Typical Sizes (from Maven Central)

| Artifact | Approx Size |
|----------|-------------|
| netty-common | ~400 KB |
| netty-buffer | ~200 KB |
| netty-transport | ~300 KB |
| netty-codec | ~150 KB |
| netty-codec-http | ~500 KB |
| netty-handler | ~100 KB |
| **Total (minimal HTTP)** | **~1.7 MB** |

**+ netty-codec-xml** (adds aalto-xml 350 KB, gson ~250 KB): **~2.3 MB total** — still fits in 2–3 MB.

**+ Jackson** (jackson-databind 1.6 MB, jackson-core 584 KB, jackson-annotations ~70 KB): **~4.5 MB total** — no longer 2–3 MB.

Optional: `netty-codec-http2` for HTTP/2 adds more. No servlet API, no JSP, no J2EE.

### Built-in XML and JSON Codecs

Netty includes codec modules for both:

**XML** (`netty-codec-xml`):
- `XmlDecoder` — async, non-blocking XML parser based on [Aalto XML](https://github.com/FasterXML/aalto-xml) (FasterXML)
- `XmlFrameDecoder` — frames fragmented XML streams
- Emits `XmlElementStart`, `XmlElementEnd`, `XmlAttribute`, etc. — EventLoop-safe
- Transitive deps: `com.fasterxml:aalto-xml`, `com.google.code.gson:gson`

**JSON** (`netty-codec`, included by `netty-codec-http`):
- `JsonObjectDecoder` — splits byte stream into individual JSON objects/arrays by brace/bracket matching
- Does **not** parse to POJOs; a downstream handler must do that (Jackson, Gson, or Jackson’s `NonBlockingByteBufferJsonParser` for true async on the EventLoop)

### What You Build

- Bootstrap `ServerBootstrap` with `NioEventLoopGroup`
- Add `HttpServerCodec` and custom `SimpleChannelInboundHandler<FullHttpRequest>`
- For XML: add `netty-codec-xml` and use `XmlDecoder` (async on EventLoop)
- For JSON: `JsonObjectDecoder` gives you `ByteBuf` chunks; use Jackson/Gson for POJOs (blocking unless you use Jackson’s non-blocking parser and offload or integrate it)

### Configuration

Netty has **no configuration files**. You write your own hardcoded bootstrap configuration in Java — ports, TLS settings, pipeline handlers, and routing are all defined in code. To change listeners, endpoints, or behaviour you must edit the source and recompile. There is no equivalent to gumdroprc, `application.properties`, or server.xml.

---

## 3. Jetty

### Deployment Model

Embedded Jetty provides a **servlet container**. You can run it as a library inside your app.

### Minimal Embedded Server

```xml
<dependency>
    <groupId>org.eclipse.jetty</groupId>
    <artifactId>jetty-server</artifactId>
    <version>12.0.5</version>
</dependency>
<dependency>
    <groupId>org.eclipse.jetty</groupId>
    <artifactId>jetty-servlet</artifactId>
    <version>12.0.5</version>
</dependency>
```

**Transitive:** Jetty modular structure pulls in jetty-util, jetty-io, jetty-http, etc.

### Typical Sizes

| Component | Approx Size |
|-----------|-------------|
| jetty-server + servlet + transitive | ~4–5 MB |
| With JSP (jetty-jsp) | +1.7 MB |
| **Total (no JSP)** | **~5–6 MB** |
| **Total (with JSP)** | **~8 MB** |

Spring Boot with Jetty (replacing Tomcat) typically produces ~12–24 MB fat JARs depending on JSP inclusion.

---

## 4. Tomcat

### Deployment Model

**Standalone:** Full Tomcat distribution (~12 MB compressed, ~30+ MB expanded).

**Embedded:** `tomcat-embed-core` for in-process servlet container.

### Minimal Embedded

```xml
<dependency>
    <groupId>org.apache.tomcat.embed</groupId>
    <artifactId>tomcat-embed-core</artifactId>
    <version>10.1.24</version>
</dependency>
```

### Typical Sizes

| Component | Approx Size |
|-----------|-------------|
| tomcat-embed-core | ~4–5 MB |
| tomcat-embed-el (expression language) | ~200 KB |
| tomcat-embed-websocket | ~100 KB |
| **Total (minimal)** | **~5–6 MB** |

Spring Boot default uses Tomcat; minimal app JAR ~10–18 MB.

---

## 5. Spring Boot

### Deployment Model

Spring Boot wraps an embedded servlet container (Tomcat by default) and adds:
- Spring Framework (core, web, MVC, context, beans, aop)
- Auto-configuration
- Logging (Logback + SLF4J + bridge)
- Jackson for JSON
- Validation (Hibernate Validator)
- Many transitive dependencies

### Minimal Web App

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <version>3.4.1</version>
</dependency>
```

### Transitive Dependencies (abbreviated)

- spring-boot-starter
- spring-boot-starter-json (Jackson)
- spring-boot-starter-tomcat
- spring-web, spring-webmvc
- spring-boot-autoconfigure
- spring-core, spring-context, spring-beans, spring-aop
- logback-classic, slf4j-api, jul-to-slf4j, log4j-to-slf4j
- jakarta.annotation-api
- snakeyaml
- tomcat-embed-core, tomcat-embed-el
- jackson-databind, jackson-core, jackson-annotations
- ... and more

### Typical Sizes

| Metric | Value |
|--------|-------|
| Maven download (first run) | ~50–100 MB |
| Number of JARs | 50–80+ |
| Fat JAR (minimal REST app) | ~25–35 MB |
| With Actuator, DevTools, etc. | ~40–80 MB |
| Download time (cold) | 1–2+ minutes |

---

## Sample Configuration: Deploying myservice.war

Below are the configuration files required to deploy a web application `myservice.war` on each framework. Gumdrop uses a single gumdroprc file; Jetty and Tomcat use XML; Spring Boot uses properties plus Java code for external WARs.

### Gumdrop

Add one line to the `contexts` list in `gumdroprc.servlet` (or your gumdroprc):

```xml
<context path="/myservice" root="/opt/apps/myservice.war"/>
```

No recompile, no Java code. Change path or root and restart.

### Jetty (standalone distribution)

Place `myservice.war` in `$JETTY_BASE/webapps/`. For a custom context path, add `webapps/myservice.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure_10_0.dtd">
<Configure class="org.eclipse.jetty.webapp.WebAppContext">
  <Set name="contextPath">/myservice</Set>
  <Set name="war"><Property name="jetty.base" default="."/>/webapps/myservice.war</Set>
</Configure>
```

Or simply copy `myservice.war` to `webapps/` — it auto-deploys at `/myservice` (WAR name minus `.war`). Jetty also uses `jetty.xml`, `start.ini`, and module configs in `$JETTY_BASE/` for the server itself.

### Tomcat (standalone distribution)

Place `myservice.war` in `$CATALINA_BASE/webapps/`. Tomcat auto-deploys it at `/myservice`. Minimal `conf/server.xml` (abbreviated; Tomcat ships with a fuller default):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Server port="8005" shutdown="SHUTDOWN">
  <Listener className="org.apache.catalina.startup.VersionLoggerListener"/>
  <Listener className="org.apache.catalina.core.AprLifecycleListener"/>
  <Listener className="org.apache.catalina.core.JreMemoryLeakPreventionListener"/>
  <Listener className="org.apache.catalina.mbeans.GlobalResourcesLifecycleListener"/>
  <GlobalNamingResources>...</GlobalNamingResources>
  <Service name="Catalina">
    <Connector port="8080" protocol="HTTP/1.1" connectionTimeout="20000" redirectPort="8443"/>
    <Engine name="Catalina" defaultHost="localhost">
      <Host name="localhost" appBase="webapps" unpackWARs="true" autoDeploy="true">
        <Valve className="org.apache.catalina.valves.AccessLogValve" directory="logs"
               prefix="localhost_access_log" suffix=".txt" pattern="%h %l %u %t &quot;%r&quot; %s %b"/>
      </Host>
    </Engine>
  </Service>
</Server>
```

For a custom context (e.g. path, JNDI), add `conf/Catalina/localhost/myservice.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Context path="/myservice" docBase="${catalina.base}/webapps/myservice.war"/>
```

### Spring Boot (embedded Tomcat)

Spring Boot typically packages your app as a JAR or WAR. To deploy an **external** `myservice.war` alongside the Boot app, use `application.properties` plus a Java `@Bean`:

**`src/main/resources/application.properties`:**

```properties
server.port=8080
# Optional: external.war.file and external.war.context for WAR path/context
# Optional: server.ssl.* for HTTPS
```

**Java configuration** (required to add external WAR — no config file equivalent):

```java
@Configuration
public class WarDeployConfig {
    @Bean
    public TomcatServletWebServerFactory servletContainer(
            @Value("${external.war.file:/opt/apps/myservice.war}") String warPath,
            @Value("${external.war.context:/myservice}") String contextPath) {
        return new TomcatServletWebServerFactory() {
            @Override
            protected TomcatWebServer getTomcatWebServer(Tomcat tomcat) {
                tomcat.addWebapp(contextPath, warPath);
                return super.getTomcatWebServer(tomcat);
            }
        };
    }
}
```

There is no XML or properties-only way to deploy an external WAR; it requires Java code.

---

## Comparison: Simple Microservice Requirements

| Requirement | Gumdrop | Netty | Jetty | Tomcat | Spring Boot |
|-------------|---------|-------|-------|--------|-------------|
| **HTTP server** | ✓ Built-in | ✓ Codec | ✓ Embedded | ✓ Embedded | ✓ Via Tomcat |
| **Servlet API** | ✓ Optional | ✗ | ✓ | ✓ | ✓ |
| **JSON parsing** | jsonparser (23 KB) | netty-codec (JsonObjectDecoder) + Jackson/Gson for POJOs | Add lib | Add lib | Jackson (included) |
| **XML parsing** | gonzalez (1.9 MB) | netty-codec-xml (Aalto, async) | Add lib | Add lib | Add lib |
| **DI framework** | ✓ (minimal, config only) | ✗ | ✗ | ✗ | ✓ (Spring, full) |
| **Build tool** | Ant (or Maven for deps) | Maven/Gradle | Maven/Gradle | Maven/Gradle | Maven/Gradle |
| **Minimal deploy size** | ~2.56 MB | ~2.3 MB (XML) / ~4.5 MB (+Jackson) | ~6 MB | ~6 MB | ~25 MB |
| **Fat JAR size** | 5.2 MB | N/A | ~8–12 MB | ~10–18 MB | ~25–80 MB |

---

*Last updated: March 2026. Sizes are approximate and vary by version.*
