# quic-interop-runner endpoint

Gumdrop's endpoint for the
[quic-interop-runner](https://github.com/quic-interop/quic-interop-runner),
the test harness behind the public QUIC interop matrix at
[interop.seemann.io](https://interop.seemann.io/). The runner puts a
client and a server from two implementations on either side of an ns-3
network simulator, drives each through environment variables, and checks
the downloaded files and the packet captures. This directory holds
everything needed to be one of those implementations:

| Path | Purpose |
|------|---------|
| `src/org/bluezoo/gumdrop/quic/interop/` | `InteropServer` and `InteropClient` mains and their helpers |
| `Dockerfile` | Endpoint image: gumdrop jars plus a JRE on the simulator's endpoint base image |
| `run_endpoint.sh` | Container entrypoint: simulator routing, then the right main for `ROLE` |
| `logging.properties`, `logging-debug.properties` | Console logging for the endpoint JVM (`INTEROP_DEBUG=1` selects the verbose one) |
| `classes/`, `results/`, `logs/` | Build output and runner logs; ignored by git |

The matrix is run by the `QUIC interop` GitHub Actions workflow
(`.github/workflows/quic-interop.yml`), on demand and weekly. It is not
part of `ant test`.

## The runner's contract

The runner knows nothing about gumdrop; it only runs the image.

- `ROLE` is `server` or `client`; `TESTCASE` names the test case.
- **Server:** serve the files in `/www` on UDP port 443, with
  `/certs/priv.key` and `/certs/cert.pem`.
- **Client:** download every URL in `REQUESTS` (space separated, such as
  `https://server4:443/xyz`) into `/downloads`, verifying the server
  against `/certs/ca.pem`, then exit 0 (or 1 on failure).
- Exit **127** for a `TESTCASE` the endpoint does not implement. The
  runner probes each image with a random case name first and refuses
  images that do not answer 127.
- Every case except `http3` uses HTTP/0.9 over ALPN `hq-interop`:
  `GET /path\r\n` on a client stream, the raw file bytes back, FIN.
- `SSLKEYLOGFILE` names a file the endpoint writes TLS secrets to in NSS
  key log format (`KeyLog.fromEnvironment()` installed as the process
  default). Several checks (resumption, multiplexing, amplification
  limit, rebinding, key update, ECN, migration) are only evaluated when
  the runner can decrypt the traces with it.
- `QLOGDIR` names a directory the endpoint writes qlog files to, one
  `{original destination connection ID}_{client|server}.sqlog` per
  connection (JSON-SEQ, draft-ietf-quic-qlog-main-schema), so the runner
  keeps gumdrop's view of each case next to the trace and the key log. The
  hq endpoints and the HTTP/3 server write them; the HTTP/3 client case
  builds its transport inside `HttpClient` and writes none.

## Test case coverage

| `TESTCASE` | Server | Client | Notes |
|------------|--------|--------|-------|
| `handshake`, `transfer`, `multiconnect`, `ipv6` | yes | yes | `transfer` also serves the runner's blackhole, loss, corruption, rebinding, amplification and goodput cases |
| `http3` | yes | yes | `Http3Listener` and `HttpClient` |
| `retry` | yes | yes | Server requires Retry address validation |
| `chacha20` | yes | yes | Only `TLS_CHACHA20_POLY1305_SHA256` offered and accepted |
| `resumption` | yes | yes | Second connection resumes with the first one's session ticket |
| `zerortt` | yes | yes | Second connection sends its requests in 0-RTT |
| `v2` | yes | yes | Compatible version negotiation to QUIC v2 (RFC 9368/9369): the server prefers v2 and switches any client that offers it; the client opens in v1 and lists v2 first |
| `keyupdate` | - | yes | Client-only case: the client initiates a key update after the first 100 KiB; servers follow a peer's update in every case |
| `connectionmigration` | yes | - | Server-only case: the server offers its own addresses on port 4434 (`INTEROP_PREFERRED_*`) as `preferred_address`; gumdrop clients migrate to a server's preferred address in every case |
| `ecn` | 127 | 127 | Needs the ECN codepoint of received datagrams, which `DatagramChannel` cannot deliver |

## Building the image

From the repository root (the Dockerfile needs the sources):

```bash
docker build -f test/quic-interop-runner/Dockerfile -t gumdrop-interop .
```

Podman works the same way (`podman build ...`). The public runner needs a
`linux/amd64` image; on Apple silicon add `--platform linux/amd64`, or
build both with `docker buildx build --platform linux/amd64,linux/arm64`.

## Running the runner locally

The runner needs Linux-style Docker networking (static dual-stack
addresses, `NET_ADMIN` for the simulator), Python 3.10 or newer, Docker
Compose and Wireshark 4.5 or newer (`tshark`, `editcap`). A Linux host is
the dependable choice; the GitHub Actions workflow is the reference setup.

```bash
git clone https://github.com/quic-interop/quic-interop-runner
cd quic-interop-runner
pip3 install -r requirements.txt
# register the local image under the name "gumdrop" (role: both)
jq '. + {"gumdrop": {"image": "gumdrop-interop", "url": "https://github.com/cpkb-bluezoo/gumdrop", "role": "both"}}' \
    implementations_quic.json > tmp.json && mv tmp.json implementations_quic.json
python3 run.py -s gumdrop -c quic-go -t handshake,transfer,http3
python3 run.py -s quic-go -c gumdrop -t handshake,transfer,http3
```

Logs land in `logs/<server>_<client>/<testcase>/` with the runner's
verdict in `output.txt`, the endpoint consoles under `server/` and
`client/`, and the simulator's pcaps under `sim/`.

## Running the endpoint without Docker

The mains read `INTEROP_WWW`, `INTEROP_DOWNLOADS`, `INTEROP_CERTS` and
`INTEROP_PORT` in place of the fixed container paths and port, so a
loopback check needs only a certificate whose subject alternative names
include the host you connect to:

```bash
ant quic-interop-build
CP="test/quic-interop-runner/classes:$(ls -d build/*/ | tr '\n' ':')lib/*"
ROLE=server TESTCASE=handshake INTEROP_PORT=4433 INTEROP_WWW=/tmp/www INTEROP_CERTS=/tmp/certs \
    java -cp "$CP" org.bluezoo.gumdrop.quic.interop.InteropServer &
ROLE=client TESTCASE=handshake INTEROP_DOWNLOADS=/tmp/downloads INTEROP_CERTS=/tmp/certs \
    REQUESTS="https://localhost:4433/file1 https://localhost:4433/file2" \
    java -cp "$CP" org.bluezoo.gumdrop.quic.interop.InteropClient
```

`ant quic-interop-test` runs the endpoint's own unit tests (the HTTP/0.9
request parser).

## The workflow

`.github/workflows/quic-interop.yml` builds the image once, then for each
peer implementation runs the whole suite twice, with gumdrop as server
and as client, and appends the runner's result matrices to the job
summary. Logs and the runner's JSON results are uploaded as artifacts
named `quic-interop-<peer>`. Run it from the Actions tab; the `peers` and
`tests` inputs narrow it down, and `ref` builds the endpoint from another
gumdrop commit, branch or tag (a full commit SHA, not `sha^`) while the
workflow itself stays at the commit it was dispatched from.

## Decoded packet traces

`dump_frames.py` writes `frames.tsv` into every test case directory: one row
per QUIC packet on gumdrop's side of the simulator, with the time, source,
packet number, frame types, the ACK ranges and delay, and the STREAM and flow
control fields. The workflow runs it after each direction, so the file is in
the uploaded logs, and it is what shows what a peer really received when a
transfer stalls. It needs `tshark` and the same key logs as `ack_stats.py`.

## Counting ACK-only packets

`ack_stats.py` reads a runner `--log-dir` and, from the pcap taken on
gumdrop's own side of the simulator, counts per case how many 1-RTT packets
gumdrop sent, how many of those carried only ACK frames, how many data packets
the peer sent, and how many `ACK_FREQUENCY`, `IMMEDIATE_ACK` and
`CONNECTION_CLOSE` frames each side sent. It needs `tshark` and the key logs
the endpoints write, and `ack_stats.py --self-test` checks its counting on
sample `tshark` output. The workflow runs it after each direction and adds
the table to the job summary, so comparing two revisions (issue #550: the
last commit before ACK scheduling against the one that completed it) is two
dispatches and a look at the summaries:

```bash
gh workflow run quic-interop.yml -f ref=feb3fc5217ae741e6276319ede30ed3159b5249f \
    -f tests=transfer,goodput -f peers=quic-go,ngtcp2,quiche,picoquic,msquic
gh workflow run quic-interop.yml -f ref=b482d4babbe0ab234ff7357741a954bd090c559e \
    -f tests=transfer,goodput -f peers=quic-go,ngtcp2,quiche,picoquic,msquic
```

To switch gumdrop's sending side off without a rebuild if a peer rejects its
frames, use `QuicTransportFactory.setAckFrequencyEnabled(false)`.
