"""Minimal gRPC Echo server for gumdrop's gRPC client integration tests.

Deliberately plain grpcio (Python's C-core gRPC implementation) -- a
wholly independent implementation from gumdrop's own HTTP/2-based gRPC
client, same rationale as every other container in this directory.
Listens insecure (h2c, no TLS) so the client can use prior-knowledge
cleartext HTTP/2.
"""

import time

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


    def Watch(self, request, context):
        if request.message == "fail":
            yield echo_pb2.EchoResponse(message="first", length=5)
            context.abort(grpc.StatusCode.NOT_FOUND, "gone")
        repeat = request.repeat_count if request.repeat_count > 0 else 1
        for i in range(1, repeat + 1):
            text = request.message + str(i)
            yield echo_pb2.EchoResponse(message=text, length=len(text))

    def Collect(self, request_iterator, context):
        message = "".join(request.message for request in request_iterator)
        return echo_pb2.EchoResponse(message=message, length=len(message))

    def Chat(self, request_iterator, context):
        for request in request_iterator:
            text = "re " + request.message
            yield echo_pb2.EchoResponse(message=text, length=len(text))

    def Metadata(self, request, context):
        received = dict(context.invocation_metadata())
        value = received.get("x-test", "")
        context.send_initial_metadata((
            ("x-echo", value),
            ("x-echo-bin", received.get("x-test-bin", b"")),
        ))
        context.set_trailing_metadata((("x-trailer", "done"),))
        return echo_pb2.EchoResponse(message=value, length=len(value))

    def Slow(self, request, context):
        deadline = time.time() + 3
        while time.time() < deadline and context.is_active():
            time.sleep(0.05)
        return echo_pb2.EchoResponse(message="slow", length=4)


def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
    echo_pb2_grpc.add_EchoServicer_to_server(EchoServicer(), server)
    server.add_insecure_port("[::]:50051")
    server.start()
    server.wait_for_termination()


if __name__ == "__main__":
    serve()
