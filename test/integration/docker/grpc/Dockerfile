# syntax=docker/dockerfile:1
#
# Minimal Python (grpcio) gRPC server for gumdrop's gRPC client
# integration tests.
#
# Python's grpcio is a real, independent gRPC/HTTP2/protobuf
# implementation (C-core) -- same rationale as every other container in
# this directory. Listens insecure (h2c prior-knowledge cleartext, no
# TLS) on port 50051, implementing the Echo service defined in
# echo.proto.
#
# Build: podman build -t gumdrop-grpc-test -f test/integration/docker/grpc/Dockerfile test/integration/docker/grpc
# Run:   podman run -d --name grpc-test -p 15051:50051 gumdrop-grpc-test
# Probe: podman exec grpc-test python3 -c "import grpc; print('ok')"

FROM python:3.12-slim

RUN pip install --no-cache-dir grpcio==1.66.2 grpcio-tools==1.66.2

WORKDIR /app
COPY echo.proto .
RUN python -m grpc_tools.protoc -I. --python_out=. --grpc_python_out=. echo.proto

COPY server.py .

EXPOSE 50051

CMD ["python3", "server.py"]
