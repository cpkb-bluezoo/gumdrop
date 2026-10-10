#!/usr/bin/env python3
"""
Writes a decoded packet-by-packet view of each quic-interop-runner test case,
to see exactly what each side sent and acknowledged when a transfer stalls
(issue #550).

Usage:
    dump_frames.py {server|client} LOG_DIR

LOG_DIR is a runner --log-dir, laid out as ack_stats.py describes. For each
test case this writes <case>/frames.tsv next to the traces, so it is uploaded
with the logs. Each row is one QUIC packet seen on gumdrop's side of the
simulator: relative time, source address, header form, packet number, frame
types, and the ACK and STREAM fields that explain what was acknowledged and
what was sent.

Only tshark is needed (the workflow installs it). The script never fails the
job it reports on: an unreadable trace becomes a note in the output file.
"""

import os
import sys
import tempfile

import ack_stats

FIELDS = [
    "frame.time_relative", "ip.src", "ipv6.src", "quic.header_form", "quic.packet_number",
    "quic.frame_type", "quic.ack.largest_acknowledged", "quic.ack.first_ack_range",
    "quic.ack.ack_range_count", "quic.ack.gap", "quic.ack.ack_range", "quic.ack.ack_delay",
    "quic.stream.stream_id", "quic.stream.offset", "quic.stream.length",
    "quic.md.maximum_data", "quic.msd.maximum_stream_data",
]


def run_tshark(pcap, keylog):
    """The decoded rows for the QUIC datagrams in a capture."""
    command = ["tshark", "-r", pcap, "-n",
               "-o", "tls.keylog_file:" + keylog,
               "-d", "udp.port==443,quic",
               "-Y", "quic", "-T", "fields"]
    for field in FIELDS:
        command += ["-e", field]
    command += ["-E", "separator=/t", "-E", "occurrence=a", "-E", "aggregator=,",
                "-E", "header=y"]
    result = ack_stats.subprocess.run(command, stdout=ack_stats.subprocess.PIPE,
                                      stderr=ack_stats.subprocess.PIPE,
                                      universal_newlines=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip().splitlines()[-1] if result.stderr.strip()
                           else "tshark exited %d" % result.returncode)
    return result.stdout


def dump(role, log_dir):
    pcap_name = "trace_node_right.pcap" if role == "server" else "trace_node_left.pcap"
    scratch = tempfile.mkdtemp(prefix="dump_frames_")
    for label, case_dir in ack_stats.find_cases(log_dir):
        out_path = os.path.join(case_dir, "frames.tsv")
        pcap = os.path.join(case_dir, "sim", pcap_name)
        keylog = ack_stats.combined_keylog(case_dir, scratch)
        with open(out_path, "w") as out:
            if not os.path.isfile(pcap):
                out.write("no %s\n" % pcap_name)
            elif keylog is None:
                out.write("no key log, packets cannot be decrypted\n")
            else:
                try:
                    out.write(run_tshark(pcap, keylog))
                except (OSError, RuntimeError) as e:
                    out.write("tshark failed: %s\n" % e)
        print("wrote %s" % out_path)


def main(argv):
    if len(argv) != 3 or argv[1] not in ("server", "client"):
        sys.stderr.write(__doc__)
        return 2
    dump(argv[1], argv[2])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
