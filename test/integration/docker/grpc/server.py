"""Minimal gRPC Echo server for gumdrop's gRPC client integration tests.

Deliberately plain grpcio (Python's C-core gRPC implementation) -- a
wholly independent implementation from gumdrop's own HTTP/2-based gRPC
client, same rationale as every other container in this directory.
Listens insecure (h2c, no TLS) so the client can use prior-knowledge
cleartext HTTP/2.
"""

import grpc
from concurrent import futures

import echo_pb2
import echo_pb2_grpc


class EchoServicer(echo_pb2_grpc.EchoServicer):
    def SayEcho(self, request, context):
        repeat = request.repeat_count if request.repeat_count > 0 else 1
        message = request.message * repeat
        return echo_pb2.EchoResponse(message=message, length=len(message))

    def AlwaysFail(self, request, context):
        context.abort(grpc.StatusCode.INVALID_ARGUMENT, "always fails: " + request.reason)


def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
    echo_pb2_grpc.add_EchoServicer_to_server(EchoServicer(), server)
    server.add_insecure_port("[::]:50051")
    server.start()
    server.wait_for_termination()


if __name__ == "__main__":
    serve()
