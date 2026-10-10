# syntax=docker/dockerfile:1
#
# gumdrop endpoint for the quic-interop-runner
# (https://github.com/quic-interop/quic-interop-runner). Build from the
# repository root, so that the sources are in the context:
#
#   docker build -f test/quic-interop-runner/Dockerfile -t gumdrop-interop .
#
# The public runner needs linux/amd64; on Apple silicon add
# --platform linux/amd64, or use buildx for a multi-platform image.

# ---- Build: jars and the endpoint classes --------------------------------
FROM eclipse-temurin:25-jdk AS build

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
# EXPERIMENT (msquic stall): client does not send ACK_FREQUENCY; remove once diagnosed
ENV ACK_FREQUENCY=0
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /gumdrop
COPY --from=build /endpoint/lib ./lib
COPY --from=build /endpoint/classes ./classes
COPY test/quic-interop-runner/run_endpoint.sh \
     test/quic-interop-runner/logging.properties \
     test/quic-interop-runner/logging-debug.properties ./
RUN chmod +x run_endpoint.sh

ENTRYPOINT ["./run_endpoint.sh"]
