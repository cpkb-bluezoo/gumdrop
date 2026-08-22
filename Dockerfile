# syntax=docker/dockerfile:1
#
# Multi-stage build for a container-friendly Gumdrop image.
#
# HTTP/3 (QUIC) is a pure-Java implementation included in the build; just
# uncomment the HTTP/3 listener in the config to enable it.
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

# Build the lib/ distribution (primary) and legacy fat jar.
RUN ant container-zip

# ---- Runtime stage ---------------------------------------------------------
FROM eclipse-temurin:17-jre AS runtime

# Run as an unprivileged user on a (mostly) read-only-friendly layout.
RUN groupadd --system gumdrop \
    && useradd --system --gid gumdrop --home-dir /opt/gumdrop --shell /usr/sbin/nologin gumdrop

WORKDIR /opt/gumdrop

COPY --from=build /src/dist/container-home/ ./

RUN chmod +x ./bin/gumdrop.sh && chown -R gumdrop:gumdrop /opt/gumdrop

USER gumdrop

ENV GUMDROP_HOME=/opt/gumdrop \
    GUMDROP_CONFIG=/opt/gumdrop/conf/gumdroprc.xml.example \
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

ENTRYPOINT ["./bin/gumdrop.sh"]
