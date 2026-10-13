"""gRPC client driver for gumdrop's gRPC server integration tests.

Plain grpcio (Python's C-core gRPC implementation), wholly independent of
gumdrop's own HTTP/2 and protobuf code. Calls every RPC of the Echo
service of echo.proto on the gumdrop server at the address given as the
one argument, and prints "ok <name>" or "FAIL <name>: <reason>" for each
scenario. Exits 0 if they all passed.
"""

import sys

import grpc

import echo_pb2
import echo_pb2_grpc


def metadata_value(metadata, key):
    for k, v in metadata or ():
        if k == key:
            return v
    return None


def unary(stub):
    response = stub.SayEcho(echo_pb2.EchoRequest(message="ab", repeat_count=3))
    assert response.message == "ababab", response.message
    assert response.length == 6, response.length


def server_streaming(stub):
    messages = [r.message for r in stub.Watch(echo_pb2.EchoRequest(message="x", repeat_count=3))]
    assert messages == ["x1", "x2", "x3"], messages


def server_streaming_error(stub):
    call = stub.Watch(echo_pb2.EchoRequest(message="fail"))
    messages = []
    try:
        for r in call:
            messages.append(r.message)
        raise AssertionError("the stream ended without an error")
    except grpc.RpcError as e:
        assert e.code() == grpc.StatusCode.NOT_FOUND, e.code()
        assert e.details() == "gone", e.details()
    assert messages == ["first"], messages


def client_streaming(stub):
    requests = (echo_pb2.EchoRequest(message=m) for m in ["a", "b", "c"])
    response = stub.Collect(requests)
    assert response.message == "abc", response.message


def client_streaming_empty(stub):
    response = stub.Collect(iter(()))
    assert response.message == "", response.message


def bidirectional(stub):
    requests = (echo_pb2.EchoRequest(message=m) for m in ["a", "b", "c"])
    messages = [r.message for r in stub.Chat(requests)]
    assert messages == ["re a", "re b", "re c"], messages


def metadata(stub):
    call = stub.Metadata.with_call(
        echo_pb2.EchoRequest(message="m"),
        metadata=(("x-test", "hello"), ("x-test-bin", b"\x00\x01\x02")),
    )
    response, rpc = call
    assert response.message == "hello", response.message
    initial = rpc.initial_metadata()
    assert metadata_value(initial, "x-echo") == "hello", initial
    assert metadata_value(initial, "x-echo-bin") == b"\x00\x01\x02", initial
    assert metadata_value(rpc.trailing_metadata(), "x-trailer") == "done", rpc.trailing_metadata()


def deadline(stub):
    try:
        stub.Slow(echo_pb2.EchoRequest(message="s"), timeout=0.5)
        raise AssertionError("the call was not cut short")
    except grpc.RpcError as e:
        assert e.code() == grpc.StatusCode.DEADLINE_EXCEEDED, e.code()


def cancellation(stub):
    call = stub.Slow.future(echo_pb2.EchoRequest(message="s"))
    call.cancel()
    assert call.cancelled()


SCENARIOS = [
    ("unary", unary),
    ("server-streaming", server_streaming),
    ("server-streaming-error", server_streaming_error),
    ("client-streaming", client_streaming),
    ("client-streaming-empty", client_streaming_empty),
    ("bidirectional", bidirectional),
    ("metadata", metadata),
    ("deadline", deadline),
    ("cancellation", cancellation),
]


def main():
    target = sys.argv[1]
    failed = 0
    with grpc.insecure_channel(target) as channel:
        stub = echo_pb2_grpc.EchoStub(channel)
        for name, scenario in SCENARIOS:
            try:
                scenario(stub)
                print("ok " + name, flush=True)
            except Exception as e:  # reported, not raised, so every scenario runs
                failed += 1
                print("FAIL %s: %r" % (name, e), flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
