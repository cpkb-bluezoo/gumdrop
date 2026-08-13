# syntax=docker/dockerfile:1
#
# Minimal Eclipse Mosquitto instance for gumdrop's MQTT client
# integration tests.
#
# Mosquitto is a mature, independent MQTT implementation -- same
# rationale as the other test containers in this directory.
#
# Username/password required (mqttuser/mqttpass) on both the plaintext
# port (1883) and a TLS port (8883, self-signed cert, one-way), so both
# paths can be exercised against the same instance.
#
# Build: podman build -t gumdrop-mqtt-test -f test/integration/docker/mqtt/Dockerfile test/integration/docker/mqtt
# Run:   podman run -d --name mqtt-test -p 11883:1883 -p 18883:8883 gumdrop-mqtt-test
# Probe: podman exec mqtt-test mosquitto_pub -h localhost -p 1883 -u mqttuser -P mqttpass -t test -m hello

FROM eclipse-mosquitto:2

USER root

RUN apk add --no-cache openssl \
    && mkdir -p /mosquitto/tls \
    && openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
       -keyout /mosquitto/tls/key.pem -out /mosquitto/tls/cert.pem \
       -subj "/CN=mqtt-test" \
       -addext "subjectAltName=DNS:mqtt-test,DNS:localhost,IP:127.0.0.1" \
    && chown mosquitto:mosquitto /mosquitto/tls/key.pem /mosquitto/tls/cert.pem

COPY mosquitto.conf /mosquitto/config/mosquitto.conf

# mosquitto_passwd hashes the password itself -- a plaintext password
# file is rejected at startup, so this can't just be a COPYed static
# file the way the other test containers' credentials are.
RUN mosquitto_passwd -b -c /mosquitto/config/passwd mqttuser mqttpass \
    && chown mosquitto:mosquitto /mosquitto/config/passwd \
    && chmod 600 /mosquitto/config/passwd

USER mosquitto

EXPOSE 1883 8883

ENTRYPOINT ["/usr/sbin/mosquitto", "-c", "/mosquitto/config/mosquitto.conf"]
