# syntax=docker/dockerfile:1
#
# Multi-stage build for a container-friendly Gumdrop image.
#
# The QUIC/HTTP-3 native library (libgumdrop) is NOT built here (it needs a
# Rust/quiche toolchain); Gumdrop degrades gracefully and starts without it,
# serving HTTP/1.1 and HTTP/2. To enable HTTP/3, build the native library
# separately and mount/copy it onto the library path, then uncomment the
# HTTP/3 listener in the config.
#
# Build:   docker build -t gumdrop:latest .
# Run:     docker run --rm -p 8080:8080 -p 8081:8081 gumdrop:latest
# Probe:   curl http://localhost:8081/readyz

# ---- Build stage -----------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends ant \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY . .

# Build the runnable container jar and the manager webapp (skip native QUIC).
RUN ant container-jar manager-war

# ---- Runtime stage ---------------------------------------------------------
FROM eclipse-temurin:17-jre AS runtime

# Run as an unprivileged user on a (mostly) read-only-friendly layout.
RUN groupadd --system gumdrop \
    && useradd --system --gid gumdrop --home-dir /opt/gumdrop --shell /usr/sbin/nologin gumdrop

WORKDIR /opt/gumdrop

COPY --from=build /src/dist/gumdrop-container.jar ./dist/gumdrop-container.jar
COPY --from=build /src/dist/manager.war ./dist/manager.war
COPY --from=build /src/start ./start
COPY --from=build /src/logging.properties ./logging.properties
COPY --from=build /src/etc/ ./etc/
COPY --from=build /src/web/ ./web/

RUN chmod +x ./start && chown -R gumdrop:gumdrop /opt/gumdrop

USER gumdrop

# Container defaults; override per deployment.
ENV GUMDROP_CONFIG=/opt/gumdrop/etc/gumdroprc.container \
    GUMDROP_DRAIN_TIMEOUT_MS=30000 \
    HTTP_PORT=8080 \
    GUMDROP_HEALTH_PORT=8081 \
    MAX_RAM_PERCENTAGE=75.0

# Application HTTP port and health/readiness port.
EXPOSE 8080 8081

# Kubernetes/orchestrator probes should target the health endpoint, e.g.:
#   livenessProbe:  httpGet { path: /livez, port: 8081 }
#   readinessProbe: httpGet { path: /readyz, port: 8081 }
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
    CMD ["/bin/sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/${GUMDROP_HEALTH_PORT:-8081}; printf 'GET /readyz HTTP/1.0\\r\\n\\r\\n' >&3; grep -q '200' <&3"]

ENTRYPOINT ["./start"]
