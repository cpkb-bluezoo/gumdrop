# syntax=docker/dockerfile:1
#
# Multi-stage build for a container-friendly Gumdrop image.
#
# HTTP/3 (QUIC) is a pure-Java implementation included in the build; a
# secure listener in conf/server.xml (secure="true") gets one automatically
# alongside its HTTP/2 listener -- see docs/CONTAINER-DEPLOYMENT.md.
#
# Build:   docker build -t gumdrop:latest .
# Run:     docker run --rm -p 8080:8080 gumdrop:latest

# ---- Build stage -----------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends ant \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY . .

# Build the lib/ distribution (primary) and legacy fat jar.
RUN ant container-zip

# ---- Runtime stage ---------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

# Run as an unprivileged user on a (mostly) read-only-friendly layout.
RUN groupadd --system gumdrop \
    && useradd --system --gid gumdrop --home-dir /opt/gumdrop --shell /usr/sbin/nologin gumdrop

WORKDIR /opt/gumdrop

COPY --from=build /src/dist/container-home/ ./

RUN chmod +x ./bin/gumdrop.sh && chown -R gumdrop:gumdrop /opt/gumdrop

USER gumdrop

ENV GUMDROP_HOME=/opt/gumdrop \
    GUMDROP_DRAIN_TIMEOUT_MS=30000 \
    MAX_RAM_PERCENTAGE=75.0

# Default servlet container HTTP port (conf/server.xml); map as needed at run time.
EXPOSE 8080

ENTRYPOINT ["./bin/gumdrop.sh"]
