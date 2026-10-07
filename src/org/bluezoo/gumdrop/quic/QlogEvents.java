/*
 * QlogEvents.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.quic;

/**
 * The names of qlog events and of the fields of their data, taken verbatim
 * from draft-ietf-quic-qlog-quic-events-13, so the transport has one
 * vocabulary and it is the draft's. The qlog exporter writes the fields as
 * they are, so a name here is also what a consumer of the telemetry records
 * sees.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class QlogEvents {

    private QlogEvents() {
    }

    // events
    static final String CONNECTION_STARTED = "quic:connection_started";
    static final String CONNECTION_CLOSED = "quic:connection_closed";
    static final String VERSION_INFORMATION = "quic:version_information";
    static final String PARAMETERS_SET = "quic:parameters_set";
    static final String KEY_DISCARDED = "quic:key_discarded";
    static final String RECOVERY_METRICS_UPDATED = "quic:recovery_metrics_updated";
    static final String PACKET_LOST = "quic:packet_lost";

    // connection_started
    static final String IP_VERSION = "ip_version";
    static final String SRC_IP = "src_ip";
    static final String DST_IP = "dst_ip";
    static final String SRC_PORT = "src_port";
    static final String DST_PORT = "dst_port";
    static final String SRC_CID = "src_cid";
    static final String DST_CID = "dst_cid";

    // connection_closed
    static final String OWNER = "owner";
    static final String CONNECTION_CODE = "connection_code";
    static final String APPLICATION_CODE = "application_code";
    static final String REASON = "reason";
    static final String TRIGGER = "trigger";

    // version_information
    static final String SERVER_VERSIONS = "server_versions";
    static final String CLIENT_VERSIONS = "client_versions";
    static final String CHOSEN_VERSION = "chosen_version";

    // parameters_set
    static final String ORIGINAL_DESTINATION_CONNECTION_ID = "original_destination_connection_id";
    static final String INITIAL_SOURCE_CONNECTION_ID = "initial_source_connection_id";
    static final String RETRY_SOURCE_CONNECTION_ID = "retry_source_connection_id";
    static final String STATELESS_RESET_TOKEN = "stateless_reset_token";
    static final String MAX_IDLE_TIMEOUT = "max_idle_timeout";
    static final String MAX_UDP_PAYLOAD_SIZE = "max_udp_payload_size";
    static final String ACK_DELAY_EXPONENT = "ack_delay_exponent";
    static final String MAX_ACK_DELAY = "max_ack_delay";
    static final String MIN_ACK_DELAY = "min_ack_delay";
    static final String INITIAL_MAX_DATA = "initial_max_data";
    static final String INITIAL_MAX_STREAM_DATA_BIDI_LOCAL = "initial_max_stream_data_bidi_local";
    static final String INITIAL_MAX_STREAM_DATA_BIDI_REMOTE = "initial_max_stream_data_bidi_remote";
    static final String INITIAL_MAX_STREAM_DATA_UNI = "initial_max_stream_data_uni";
    static final String INITIAL_MAX_STREAMS_BIDI = "initial_max_streams_bidi";
    static final String INITIAL_MAX_STREAMS_UNI = "initial_max_streams_uni";
    static final String MAX_DATAGRAM_FRAME_SIZE = "max_datagram_frame_size";

    // key_discarded
    static final String KEY_TYPE = "key_type";

    // recovery_metrics_updated
    static final String MIN_RTT = "min_rtt";
    static final String SMOOTHED_RTT = "smoothed_rtt";
    static final String LATEST_RTT = "latest_rtt";
    static final String RTT_VARIANCE = "rtt_variance";
    static final String CONGESTION_WINDOW = "congestion_window";
    static final String BYTES_IN_FLIGHT = "bytes_in_flight";
    static final String SSTHRESH = "ssthresh";

    // packet_lost
    static final String HEADER = "header";
    static final String PACKET_TYPE = "packet_type";
    static final String PACKET_NUMBER = "packet_number";

    // values
    static final String OWNER_LOCAL = "local";
    static final String OWNER_REMOTE = "remote";
    static final String PACKET_TYPE_INITIAL = "initial";
    static final String PACKET_TYPE_HANDSHAKE = "handshake";
    static final String PACKET_TYPE_1RTT = "1RTT";
    static final String TRIGGER_CLEAN = "clean";
    static final String TRIGGER_ERROR = "error";
    static final String TRIGGER_APPLICATION = "application";
    static final String TRIGGER_STATELESS_RESET = "stateless_reset";
    static final String TRIGGER_VERSION_MISMATCH = "version_mismatch";
    static final String TRIGGER_TIME_THRESHOLD = "time_threshold";
}
