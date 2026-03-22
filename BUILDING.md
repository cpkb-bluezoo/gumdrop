# Building Gumdrop

This document covers how to build, run, and optionally enable QUIC/HTTP3 support.

## Building

Building Gumdrop is straightforward. You need Java 17+ and [Apache Ant](https://ant.apache.org/) (you can also use [Gantt](https://github.com/cpkb-bluezoo/gantt)).

```bash
ant
```

Or explicitly:

```bash
ant dist
```

This compiles the project, creates the distribution JARs, and optionally builds the QUIC/HTTP3 native library if `QUICHE_DIR` is set. The build downloads external dependencies automatically on first run.

**Build artifacts:** Use `gumdrop-container.jar` (the "fat jar") to run the servlet container with all J2EE dependencies. If you don't need the servlet container and want to develop pure async non-blocking services using the Gumdrop framework, you only need `gumdrop.jar` plus [Gonzalez](https://github.com/cpkb-bluezoo/gonzalez) and [jsonparser](https://github.com/cpkb-bluezoo/jsonparser) if you use those. Artifacts are located in the `dist` subdirectory.

## Running

Start the server with one of the example configurations in `etc/`:

```bash
./start etc/gumdroprc.servlet
```

You should then be able to point a browser at
[http://localhost:8080/](http://localhost:8080/) or
[https://localhost:8443/](https://localhost:8443/) to see the example web
application included, which includes full documentation of the framework.

Other example configurations are available:

| Configuration | Description |
|---|---|
| `etc/gumdroprc.servlet` | Servlet container (HTTP, HTTPS, HTTP/3) |
| `etc/gumdroprc.webdav` | WebDAV file server |
| `etc/gumdroprc.ftp.file.simple` | Simple FTP file server |
| `etc/gumdroprc.ftp.file.anonymous` | Anonymous FTP file server |
| `etc/gumdroprc.ftp.file.rolebased` | Role-based FTP file server |
| `etc/gumdroprc.imap` | IMAP mailbox access |
| `etc/gumdroprc.pop3` | POP3 mailbox access |
| `etc/gumdroprc.smtp.localdelivery` | SMTP local delivery |
| `etc/gumdroprc.smtp.simplerelay` | SMTP relay (authenticated) |
| `etc/gumdroprc.dns` | DNS caching proxy (UDP, DoT, DoQ) |
| `etc/gumdroprc.mqtt` | MQTT broker (plaintext and optional TLS) |
| `etc/gumdroprc.socks` | SOCKS proxy (SOCKS4/4a/5) |

You can configure any of these to serve your own application and run it
immediately.

## QUIC support (native library)

QUIC support requires the [quiche](https://github.com/cloudflare/quiche)
library and a JNI glue library that bridges quiche into the JVM. QUIC is
optional; the core framework and all TCP/UDP-based protocols work without it.

### Why build from source?

Gumdrop's JNI layer calls both quiche functions and BoringSSL functions
directly (for TLS 1.3 configuration, cipher suite selection, and PQC key
exchange groups like X25519MLKEM768). BoringSSL is statically linked into
`libquiche` during the Cargo build, but its headers are not installed
separately. **Building quiche from source is the recommended approach** on
all platforms because it provides:

- the quiche C headers (`quiche.h`)
- the BoringSSL headers (`openssl/ssl.h`, `openssl/err.h`) from the
  vendored copy in the source tree
- a `libquiche` shared library with the BoringSSL symbols the JNI code
  links against

> **Note on system packages:** Debian/Ubuntu ship `libquiche-dev`, but the
> package does not include the BoringSSL headers or guarantee that the
> BoringSSL symbols are exported from the shared library. Since the Gumdrop
> JNI code creates BoringSSL `SSL_CTX` objects and passes them into quiche,
> the headers and symbols must come from the same BoringSSL build. Use the
> source build below.

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Rust toolchain (`rustup` + `cargo`) | stable | Install via [rustup.rs](https://rustup.rs/) |
| C compiler | gcc / clang | Xcode Command Line Tools on macOS |
| CMake | 3.x+ | Required by BoringSSL (built by quiche) |
| JDK | 8+ | `JAVA_HOME` must be set |
| Git | any | To clone the quiche repository |

### Platform matrix

| Platform | Architecture | Status |
|----------|-------------|--------|
| macOS | x86\_64 / aarch64 (Apple Silicon) | Supported |
| Linux | x86\_64 / aarch64 | Supported |
| Windows | x86\_64 | Untested (should work with MSVC) |

### Step 1: Build quiche from source

```bash
# Clone quiche and pin to a known release
git clone --recursive https://github.com/cloudflare/quiche.git
cd quiche
git checkout 0.25.0

# Build the C FFI shared library (includes HTTP/3 h3 module)
cargo build --release --features ffi
```

Make sure to use `--recursive` to get BoringSSL as a submodule.

This produces:

| Artifact | Path (relative to repo root) |
|----------|------------------------------|
| Shared library (macOS) | `target/release/libquiche.dylib` |
| Shared library (Linux) | `target/release/libquiche.so` |
| quiche C headers | `quiche/include/` |
| BoringSSL headers | `quiche/deps/boringssl/src/include/` |

### Step 2: Compile the JNI native library

Set `QUICHE_DIR` to the **root of the cloned quiche repository** (the
directory containing `target/`, `quiche/`, etc.) and run `ant dist`:

```bash
export QUICHE_DIR=/path/to/quiche
ant dist
```

The build automatically detects the platform (macOS/Linux), locates the
JDK headers, and compiles the JNI source files in
`src/org/bluezoo/gumdrop/jni/` into `dist/libgumdrop.dylib`
(macOS) or `dist/libgumdrop.so` (Linux). If `QUICHE_DIR` is not set
or the quiche headers are not found, the native build is skipped and only
the Java artifacts are produced.

The build references three paths under `$QUICHE_DIR`:

| Path under `$QUICHE_DIR` | Provides |
|--------------------------|----------|
| `quiche/include` | quiche C headers (`quiche.h`) |
| `quiche/deps/boringssl/src/include` | BoringSSL headers (`openssl/ssl.h`) |
| `target/release` | Built shared library (`-lquiche`) |

<details>
<summary>Manual compilation (alternative to <code>ant dist</code>)</summary>

If you prefer to compile the JNI library manually:

```bash
export QUICHE_DIR=/path/to/quiche
export JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || echo $JAVA_HOME)

# macOS (Apple Silicon / x86_64)
cc -shared -fPIC -o libgumdrop.dylib \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/darwin" \
  -I"$QUICHE_DIR/quiche/include" \
  -I"$QUICHE_DIR/quiche/deps/boringssl/src/include" \
  -L"$QUICHE_DIR/target/release" \
  -lquiche -lresolv \
  src/org/bluezoo/gumdrop/jni/quiche_jni.c \
  src/org/bluezoo/gumdrop/jni/ssl_ctx_jni.c \
  src/org/bluezoo/gumdrop/jni/h3_jni.c \
  src/org/bluezoo/gumdrop/jni/dns_jni.c

# Linux
cc -shared -fPIC -o libgumdrop.so \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/linux" \
  -I"$QUICHE_DIR/quiche/include" \
  -I"$QUICHE_DIR/quiche/deps/boringssl/src/include" \
  -L"$QUICHE_DIR/target/release" \
  -lquiche -lresolv \
  src/org/bluezoo/gumdrop/jni/quiche_jni.c \
  src/org/bluezoo/gumdrop/jni/ssl_ctx_jni.c \
  src/org/bluezoo/gumdrop/jni/h3_jni.c \
  src/org/bluezoo/gumdrop/jni/dns_jni.c
```

</details>

> **pkg-config alternative:** If you build quiche with
> `cargo build --release --features ffi,pkg-config-meta`, a `quiche.pc`
> file is generated in `target/release/`. You can then replace the
> quiche-specific `-I` and `-L` flags with:
>
> ```bash
> PKG_CONFIG_PATH="$QUICHE_DIR/target/release" pkg-config --cflags --libs quiche
> ```
>
> You still need the separate `-I` flag for the BoringSSL headers, since
> the generated `.pc` file only covers quiche's own include directory.

### Step 3: Runtime library path

Both `libgumdrop` and `libquiche` must be findable by the dynamic
linker at runtime. There are several options:

**Option A: Set the library path (quick, good for development)**

```bash
# macOS
export DYLD_LIBRARY_PATH="$QUICHE_DIR/target/release:$(pwd)"

# Linux
export LD_LIBRARY_PATH="$QUICHE_DIR/target/release:$(pwd)"
```

**Option B: Pass as a JVM argument**

```bash
java -Djava.library.path="$QUICHE_DIR/target/release:$(pwd)" ...
```

**Option C: Install into a standard location (recommended for deployment)**

Copy the built libraries to a directory already on the linker search path:

```bash
# Copy libquiche
sudo cp "$QUICHE_DIR/target/release/libquiche.dylib" /usr/local/lib/   # macOS
sudo cp "$QUICHE_DIR/target/release/libquiche.so" /usr/local/lib/      # Linux

# Copy the JNI library you built in Step 2
sudo cp libgumdrop.dylib /usr/local/lib/   # macOS
sudo cp libgumdrop.so /usr/local/lib/      # Linux

# Linux only: refresh the linker cache
sudo ldconfig
```

With Option C, no environment variables are needed at runtime.

### Verifying the build

A quick smoke test that the native library loads:

```bash
java -Djava.library.path="." -cp build/classes \
  -Dorg.bluezoo.gumdrop.quic.test=true \
  org.bluezoo.gumdrop.GumdropNative
```

If it exits without `UnsatisfiedLinkError`, the JNI bindings are
correctly compiled and linked.
