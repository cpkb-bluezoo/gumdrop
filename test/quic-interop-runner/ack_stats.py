#!/usr/bin/env python3
"""
Counts the ACK-only packets gumdrop sends in quic-interop-runner runs, to
compare ACK scheduling between gumdrop revisions and across peers (issue
#550).

Usage:
    ack_stats.py {server|client} LOG_DIR      markdown table on stdout
    ack_stats.py --self-test

LOG_DIR is a runner --log-dir. For each test case it holds
<server>_<client>/<case>/[<n>/]sim/trace_node_{left,right}.pcap and, because
the interop endpoints log their TLS secrets, <case>/{client,server}/keys.log,
which is what lets tshark decrypt the 1-RTT packets. The first argument says
which role gumdrop played in the run, so that the pcap taken on gumdrop's own
side of the simulator is read (left is the client's side, right the server's):
that capture shows exactly what gumdrop sent, where the far side's would
miss packets the simulated link dropped.

Only tshark is needed (the workflow installs it). The script never fails the
job it reports on: an unreadable trace becomes a note in the table.
"""

import os
import subprocess
import sys
import tempfile

# the runner's fixed addresses (quic-interop-runner trace.py)
IP4_CLIENT = "193.167.0.100"
IP4_SERVER = "193.167.100.100"
IP6_CLIENT = "fd00:cafe:cafe:0::100"
IP6_SERVER = "fd00:cafe:cafe:100::100"

FRAME_PADDING = 0x00
FRAME_ACK = 0x02
FRAME_ACK_ECN = 0x03
FRAME_STREAM_FIRST = 0x08
FRAME_STREAM_LAST = 0x0F
FRAME_CONNECTION_CLOSE = (0x1C, 0x1D)
FRAME_IMMEDIATE_ACK = 0x1F
FRAME_ACK_FREQUENCY = 0xAF


def parse_frame_types(field):
    """The frame types in a tshark field value: comma separated, decimal or 0x hex."""
    types = []
    for part in field.split(","):
        part = part.strip()
        if part:
            types.append(int(part, 0))
    return types


class Counts(object):
    def __init__(self):
        self.sent = 0
        self.sent_ack_only = 0
        self.sent_ack_frequency = 0
        self.sent_immediate_ack = 0
        self.sent_close = 0
        self.received = 0
        self.received_data = 0
        self.received_ack_frequency = 0
        self.received_immediate_ack = 0
        self.received_close = 0

    def ack_only_per_data_packet(self):
        if self.received_data == 0:
            return None
        return float(self.sent_ack_only) / self.received_data


def is_ack_only(types):
    """True for a packet whose frames are ACK (and padding) only."""
    has_ack = False
    for t in types:
        if t in (FRAME_ACK, FRAME_ACK_ECN):
            has_ack = True
        elif t != FRAME_PADDING:
            return False
    return has_ack


def is_data(types):
    for t in types:
        if FRAME_STREAM_FIRST <= t <= FRAME_STREAM_LAST:
            return True
    return False


def analyse(lines, gumdrop_addresses):
    """
    Counts short-header (1-RTT) packets from tshark -T fields output with the
    tab separated columns: ip.src, ipv6.src, quic.header_form, quic.frame_type.
    A datagram that also carries a long-header packet is skipped.
    """
    counts = Counts()
    for line in lines:
        cols = line.rstrip("\r\n").split("\t")
        while len(cols) < 4:
            cols.append("")
        source = cols[0] or cols[1]
        if cols[2].strip() != "0":
            continue
        try:
            types = parse_frame_types(cols[3])
        except ValueError:
            continue
        mine = source in gumdrop_addresses
        if mine:
            counts.sent += 1
            if is_ack_only(types):
                counts.sent_ack_only += 1
            if FRAME_ACK_FREQUENCY in types:
                counts.sent_ack_frequency += 1
            if FRAME_IMMEDIATE_ACK in types:
                counts.sent_immediate_ack += 1
            if any(t in FRAME_CONNECTION_CLOSE for t in types):
                counts.sent_close += 1
        else:
            counts.received += 1
            if is_data(types):
                counts.received_data += 1
            if FRAME_ACK_FREQUENCY in types:
                counts.received_ack_frequency += 1
            if FRAME_IMMEDIATE_ACK in types:
                counts.received_immediate_ack += 1
            if any(t in FRAME_CONNECTION_CLOSE for t in types):
                counts.received_close += 1
    return counts


def run_tshark(pcap, keylog):
    """The tshark field lines for the QUIC datagrams in a capture."""
    command = [
        "tshark", "-r", pcap, "-n",
        "-o", "tls.keylog_file:" + keylog,
        "-d", "udp.port==443,quic",
        "-Y", "quic",
        "-T", "fields",
        "-e", "ip.src", "-e", "ipv6.src", "-e", "quic.header_form", "-e", "quic.frame_type",
        "-E", "separator=/t", "-E", "occurrence=a", "-E", "aggregator=,",
    ]
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            universal_newlines=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip().splitlines()[-1] if result.stderr.strip()
                           else "tshark exited %d" % result.returncode)
    return result.stdout.splitlines()


def combined_keylog(case_dir, scratch):
    """Both sides' key logs in one file: either holds all the secrets, together is safest."""
    path = os.path.join(scratch, "keys.log")
    found = False
    with open(path, "w") as out:
        for side in ("client", "server"):
            candidate = os.path.join(case_dir, side, "keys.log")
            if os.path.isfile(candidate):
                found = True
                with open(candidate) as source:
                    out.write(source.read())
                    out.write("\n")
    return path if found else None


def find_cases(log_dir):
    """(label, case_dir) for every directory holding a sim/ trace."""
    cases = []
    for root, dirs, files in os.walk(log_dir):
        if os.path.basename(root) == "sim" and any(f.startswith("trace_node_") for f in files):
            case_dir = os.path.dirname(root)
            cases.append((os.path.relpath(case_dir, log_dir), case_dir))
    cases.sort()
    return cases


def format_row(label, counts, note=None):
    if note:
        return "| %s | | | | | | | | %s |" % (label, note)
    ratio = counts.ack_only_per_data_packet()
    return "| %s | %d | %d | %d | %s | %d / %d | %d / %d | %d / %d | |" % (
        label, counts.sent, counts.sent_ack_only, counts.received_data,
        "n/a" if ratio is None else "%.3f" % ratio,
        counts.sent_ack_frequency, counts.received_ack_frequency,
        counts.sent_immediate_ack, counts.received_immediate_ack,
        counts.sent_close, counts.received_close)


HEADER = [
    "| Run / case | gumdrop 1-RTT sent | ACK-only sent | peer data packets | ACK-only per data packet "
    "| ACK_FREQUENCY sent / received | IMMEDIATE_ACK sent / received "
    "| CONNECTION_CLOSE sent / received | note |",
    "|---|---|---|---|---|---|---|---|---|",
]


def report(role, log_dir):
    if role == "server":
        pcap_name, addresses = "trace_node_right.pcap", (IP4_SERVER, IP6_SERVER)
    else:
        pcap_name, addresses = "trace_node_left.pcap", (IP4_CLIENT, IP6_CLIENT)
    rows = list(HEADER)
    cases = find_cases(log_dir)
    if not cases:
        rows.append("| (no traces found under %s) | | | | | | | | |" % log_dir)
    scratch = tempfile.mkdtemp(prefix="ack_stats_")
    for label, case_dir in cases:
        pcap = os.path.join(case_dir, "sim", pcap_name)
        keylog = combined_keylog(case_dir, scratch)
        if not os.path.isfile(pcap):
            rows.append(format_row(label, None, "no " + pcap_name))
            continue
        if keylog is None:
            rows.append(format_row(label, None, "no key log, packets cannot be decrypted"))
            continue
        try:
            rows.append(format_row(label, analyse(run_tshark(pcap, keylog), addresses)))
        except (OSError, RuntimeError) as e:
            rows.append(format_row(label, None, "tshark failed: %s" % e))
    return "\n".join(rows)


def self_test():
    ack_only = analyse(["193.167.100.100\t\t0\t2", "193.167.100.100\t\t0\t2,0,0"],
                       (IP4_SERVER, IP6_SERVER))
    assert ack_only.sent == 2 and ack_only.sent_ack_only == 2, vars(ack_only)

    mixed = analyse([
        "193.167.100.100\t\t0\t2,8",            # ACK + STREAM: not ack-only
        "193.167.100.100\t\t0\t0x2",            # hex form, ack-only
        "193.167.100.100\t\t0\t1",              # PING only: not ack-only
        "193.167.100.100\t\t0\t",               # nothing decoded: not ack-only
        "\tfd00:cafe:cafe:100::100\t0\t3",      # IPv6 source, ACK_ECN: ack-only
        "193.167.0.100\t\t0\t8,9",              # from the peer, data
        "193.167.0.100\t\t0\t10",               # from the peer, data
        "193.167.0.100\t\t0\t2",                # from the peer, ack
        "193.167.100.100\t\t1\t2",              # long header: skipped
        "193.167.100.100\t\t1,0\t2",            # coalesced with a long header: skipped
        "193.167.100.100\t\t0\t175,2",          # ACK_FREQUENCY sent
        "193.167.0.100\t\t0\t175",              # ACK_FREQUENCY received
        "193.167.100.100\t\t0\t31",             # IMMEDIATE_ACK sent
        "193.167.0.100\t\t0\t28",               # CONNECTION_CLOSE received
        "193.167.100.100\t\t0\tnotanumber",     # garbled: skipped
    ], (IP4_SERVER, IP6_SERVER))
    assert mixed.sent == 7, vars(mixed)
    assert mixed.sent_ack_only == 2, vars(mixed)       # "0x2" and the IPv6 ACK_ECN; "175,2" carries ACK_FREQUENCY
    assert mixed.sent_ack_frequency == 1 and mixed.sent_immediate_ack == 1, vars(mixed)
    assert mixed.received == 5 and mixed.received_data == 2, vars(mixed)
    assert mixed.received_ack_frequency == 1 and mixed.received_close == 1, vars(mixed)
    assert abs(mixed.ack_only_per_data_packet() - 1.0) < 1e-9, mixed.ack_only_per_data_packet()
    assert Counts().ack_only_per_data_packet() is None

    assert parse_frame_types("2,0x1c, 175") == [2, 28, 175]
    assert is_ack_only([0, 2, 0]) and not is_ack_only([0]) and not is_ack_only([2, 6])
    row = format_row("a/b", mixed)
    assert row.startswith("| a/b | 7 | 2 | 2 | 1.000 |"), row
    assert "no key log" in format_row("x", None, "no key log")
    print("ack_stats self-test passed")


def main(argv):
    if len(argv) == 2 and argv[1] == "--self-test":
        self_test()
        return 0
    if len(argv) != 3 or argv[1] not in ("server", "client"):
        sys.stderr.write(__doc__)
        return 2
    print(report(argv[1], argv[2]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
