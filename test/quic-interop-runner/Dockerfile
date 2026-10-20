# syntax=docker/dockerfile:1
#
# gumdrop endpoint for the quic-interop-runner
# (https://github.com/quic-interop/quic-interop-runner). Build from the
# repository root, so that the sources are in the context:
#
#   docker build -f test/quic-interop-runner/Dockerfile -t gumdrop-interop .
#
# The public runner needs linux/amd64; on Apple silicon add
# --platform linux/amd64, or use buildx for a multi-platform image
# (.github/workflows/publish-interop-image.yml does).

# ---- Build: jars and the endpoint classes --------------------------------
# Always on the build host's own platform: the jars and classes are the same
# for every target, so a multi-platform build compiles once, not under
# emulation.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends ant \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY . .

RUN ant jar quic-interop-build
RUN mkdir -p /endpoint/lib \
    && cp dist/gumdrop.jar lib/*.jar /endpoint/lib/ \
    && cp -r test/quic-interop-runner/classes /endpoint/classes

# ---- Runtime: the simulator's endpoint base plus a JRE -------------------
FROM eclipse-temurin:25-jre AS jre

FROM martenseemann/quic-network-simulator-endpoint:latest

COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# The runner does not pass extra environment into the container, so verbose
# endpoint logging is baked in: --build-arg INTEROP_DEBUG=1
ARG INTEROP_DEBUG=
ENV INTEROP_DEBUG=${INTEROP_DEBUG}

WORKDIR /gumdrop
COPY --from=build /endpoint/lib ./lib
COPY --from=build /endpoint/classes ./classes
COPY test/quic-interop-runner/run_endpoint.sh \
     test/quic-interop-runner/logging.properties \
     test/quic-interop-runner/logging-debug.properties ./
RUN chmod +x run_endpoint.sh

ENTRYPOINT ["./run_endpoint.sh"]
