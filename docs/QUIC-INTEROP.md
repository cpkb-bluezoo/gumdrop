# QUIC interoperability testing

Gumdrop's QUIC and HTTP/3 stack is tested against other implementations with
the [QUIC Interop Runner](https://github.com/quic-interop/quic-interop-runner),
the harness behind the public matrix at <https://interop.seemann.io/>. The
runner puts a client and a server from two implementations on either side of
a network simulator that delays, drops, corrupts and rewrites packets, runs
about twenty test cases (handshake, transfer, resumption, 0-RTT, Retry, key
update, QUIC v2, connection migration, rebinding, loss and corruption, and so
on), and checks both the downloaded files and the packet captures.

This page is for anyone who wants to run those tests against Gumdrop: the
maintainers checking a change, or the authors of another QUIC implementation
checking theirs against it. There are four ways to do it, from the least to
the most setup.

| I want to... | Use |
|---|---|
| Try Gumdrop against my implementation with the official runner | [The published image](#1-the-published-image-with-the-official-runner) |
| Test a Gumdrop change against many implementations | [The GitHub Actions workflow](#2-the-github-actions-workflow) |
| Check one case against one peer quickly, on a laptop | [The local podman script](#3-the-local-podman-script) |
| Change the endpoint itself | [The endpoint's own README](../test/quic-interop-runner/README.md) |

## 1. The published image, with the official runner

Each Gumdrop release publishes a container image of its interop endpoint to
the GitHub Container Registry, for `linux/amd64` and `linux/arm64`:

```
ghcr.io/cpkb-bluezoo/gumdrop-interop:latest     newest release
ghcr.io/cpkb-bluezoo/gumdrop-interop:3.1.2      that release (use the number you want)
ghcr.io/cpkb-bluezoo/gumdrop-interop:edge       a manual test build, not a release
```

The image plays either role. `ROLE=server` serves the runner's `/www`
directory on UDP port 443; `ROLE=client` fetches the URLs in `REQUESTS` into
`/downloads`. A test case it does not support makes it exit with status 127,
which the runner reports as "unsupported".

You need Linux (or a Linux VM) with Docker and Docker Compose, Python 3.10 or
newer, and Wireshark 4.5 or newer (`tshark`, `editcap`). On Linux, the kernel
module `ip6table_filter` must be loaded for the simulator's IPv6 to work
(`sudo modprobe ip6table_filter`).

```bash
git clone https://github.com/quic-interop/quic-interop-runner
cd quic-interop-runner
pip3 install -r requirements.txt

# Register Gumdrop as a local implementation. The runner reads this file for
# the names it accepts with -s and -c; nothing is sent anywhere.
jq '. + {"gumdrop": {
      "image": "ghcr.io/cpkb-bluezoo/gumdrop-interop:latest",
      "url": "https://github.com/cpkb-bluezoo/gumdrop",
      "role": "both"}}' implementations_quic.json > tmp.json \
  && mv tmp.json implementations_quic.json

# Gumdrop as the server, quic-go as the client
python3 run.py -s gumdrop -c quic-go -t handshake,transfer,http3

# Gumdrop as the client, quic-go as the server
python3 run.py -s quic-go -c gumdrop -t handshake,transfer,http3
```

To test your own implementation, register its image the same way (an entry
with `image`, `url` and `role`, as in the runner's `implementations_quic.json`)
and give its name to `-s` or `-c`. Leave `-t` out to run every test case.

The runner writes its verdict to the console and its logs to
`logs/<server>_<client>/<testcase>/`. See [Reading the results](#reading-the-results).

To build the image from source instead (for a change that is not released),
from the root of this repository:

```bash
docker build -f test/quic-interop-runner/Dockerfile -t gumdrop-interop .
```

and use `gumdrop-interop` as the `image` above. On Apple silicon add
`--platform linux/amd64`; the public runner needs that platform.

## 2. The GitHub Actions workflow

The repository's **QUIC interop** workflow
(`.github/workflows/quic-interop.yml`) builds the endpoint from a commit and
runs it against other implementations on GitHub's runners, in both
directions. It is how Gumdrop is checked against many peers without setting
up the runner by hand. Start it from the repository's **Actions** tab (**QUIC
interop**, **Run workflow**) or with the GitHub CLI:

```bash
gh workflow run quic-interop.yml -f peers=quic-go,ngtcp2 -f tests=handshake,transfer
```

| Input | Meaning |
|---|---|
| `peers` | Comma-separated implementations from the runner's list. Use `all` for every one. The weekly scheduled run uses a default set. |
| `tests` | Comma-separated test cases. Empty runs all of them. |
| `debug` | Bakes verbose endpoint logging into the image. Worth it when something fails; the logs are much larger. |
| `ref` | A commit, branch or tag of Gumdrop to build. Empty builds the commit the workflow runs from. This is how to test a branch without merging it. |

Each peer is a separate job. The job's summary lists, for each direction, the
cases that passed (`✓`), were unsupported (`?`) or failed (`✕`), and a final
step fails the job if any case failed. The full logs of every case are in the
job's `quic-interop-<peer>` artifact.

## 3. The local podman script

`test/quic-interop-runner/local.sh` runs the endpoint against one peer, in
podman, on the runner's addresses but with a plain router instead of the
simulator. It needs podman and nothing else, and a case takes about a minute.
It covers the protocol cases; it cannot drop or corrupt packets, so loss and
corruption need the real runner. It can rebind the client's port or address
(`rebind-port`, `rebind-addr`), which is the one simulator behaviour that
matters most for debugging a peer.

```bash
# usage: local.sh [-b] PEER ROLE TESTCASE   (ROLE is the part Gumdrop plays)
test/quic-interop-runner/local.sh picoquic server v2
test/quic-interop-runner/local.sh nginx client rebind-port
```

Peers it knows by name are `ngtcp2`, `picoquic`, `quic-go`, `quiche`,
`xquic`, `nginx` and `haproxy`; any other image name works too. `-b` rebuilds
the endpoint image first. It prints `PASS` or `FAIL` and keeps the logs under
`~/.cache/gumdrop-interop-local/`. The script's header lists its limits, and
the [endpoint's README](../test/quic-interop-runner/README.md) has the
details.

## Reading the results

The runner's logs are laid out as `logs/<server>_<client>/<testcase>/`:

| Path | Contents |
|---|---|
| `output.txt` | The runner's verdict and, on failure, the reason, with the packet it was looking at |
| `server/`, `client/` | Each endpoint's console output, TLS keys (`keys.log`) and, for Gumdrop, qlog files |
| `sim/` | The simulator's packet captures, which open in Wireshark with the key logs |

Things worth knowing when a case fails:

- **A failure is not always the implementation under test.** The runner
  decrypts captures with Wireshark and applies fixed rules, so some failures
  come from the runner or the other peer. For example, a client that uses
  zero-length connection IDs defeats the check that the first packet on a
  rebound path carries a PATH_CHALLENGE ("Unknown QUIC connection"), and a
  server may ignore a client's migration. Read `output.txt` before assuming
  a bug.
- **Loss and corruption cases are statistical.** A handshake can legitimately
  fail once in a while under the simulator's loss. Repeat a failing
  `handshakeloss` or `handshakecorruption` before investigating.
- **Unsupported is not a failure.** Exit status 127 is reported as `?`.
- **Gumdrop's qlog** files (`*.sqlog`) are in `server/qlog/` and `client/qlog/`
  and list every packet and frame in JSON-SEQ form. They are usually the
  fastest way to see what Gumdrop did; the HTTP/3 client does not write one.

## Where things are

| Path | What it is |
|---|---|
| `test/quic-interop-runner/` | The endpoint: Dockerfile, entrypoint, the `InteropServer` and `InteropClient` mains, `local.sh`, helper scripts. Its README describes every test case and what Gumdrop does for it. |
| `.github/workflows/quic-interop.yml` | The cross-implementation workflow described above |
| `.github/workflows/publish-interop-image.yml` | Builds and publishes the image to `ghcr.io` on each release |
