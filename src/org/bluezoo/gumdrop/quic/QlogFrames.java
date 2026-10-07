/*
 * QlogFrames.java
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
 * Builds the {@code frames} array of a qlog {@code packet_sent} or
 * {@code packet_received} event, one frame at a time as the packet is
 * written or read. The frame types and their fields are those of
 * draft-ietf-quic-qlog-quic-events-13 section 5; a frame the draft does not
 * define (such as the ACK frequency frames) is written as an unknown frame
 * with its type code.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class QlogFrames {

    private final StringBuilder sb = new StringBuilder(160);
    private int count;

    private StringBuilder open(String type) {
        sb.append(count++ == 0 ? "[" : ",");
        sb.append("{\"frame_type\":\"").append(type).append('"');
        return sb;
    }

    private void close() {
        sb.append('}');
    }

    int size() {
        return count;
    }

    /** Returns the JSON array; empty if no frame was added. */
    String array() {
        return count == 0 ? "[]" : sb.toString() + "]";
    }

    QlogFrames padding(int length) {
        open("padding").append(",\"length\":").append(length);
        close();
        return this;
    }

    QlogFrames ping() {
        open("ping");
        close();
        return this;
    }

    /**
     * An ACK frame.
     *
     * @param ranges the acknowledged ranges as {low, high} pairs
     * @param delayMicros the ACK Delay in microseconds
     */
    QlogFrames ack(long[][] ranges, long delayMicros) {
        open("ack");
        sb.append(",\"ack_delay\":").append(milliseconds(delayMicros));
        sb.append(",\"acked_ranges\":[");
        for (int i = 0; i < ranges.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(ranges[i][0]).append(',').append(ranges[i][1]).append(']');
        }
        sb.append(']');
        close();
        return this;
    }

    QlogFrames resetStream(long streamId, long errorCode, long finalSize) {
        open("reset_stream").append(",\"stream_id\":").append(streamId);
        sb.append(",\"error_code\":").append(errorCode).append(",\"final_size\":").append(finalSize);
        close();
        return this;
    }

    QlogFrames stopSending(long streamId, long errorCode) {
        open("stop_sending").append(",\"stream_id\":").append(streamId);
        sb.append(",\"error_code\":").append(errorCode);
        close();
        return this;
    }

    QlogFrames crypto(long offset, int length) {
        open("crypto").append(",\"offset\":").append(offset).append(",\"length\":").append(length);
        close();
        return this;
    }

    QlogFrames newToken(int length) {
        open("new_token").append(",\"token\":{\"length\":").append(length).append('}');
        close();
        return this;
    }

    QlogFrames stream(long streamId, long offset, int length, boolean fin) {
        open("stream").append(",\"stream_id\":").append(streamId);
        sb.append(",\"offset\":").append(offset).append(",\"length\":").append(length);
        if (fin) {
            sb.append(",\"fin\":true");
        }
        close();
        return this;
    }

    QlogFrames maxData(long maximum) {
        open("max_data").append(",\"maximum\":").append(maximum);
        close();
        return this;
    }

    QlogFrames maxStreamData(long streamId, long maximum) {
        open("max_stream_data").append(",\"stream_id\":").append(streamId);
        sb.append(",\"maximum\":").append(maximum);
        close();
        return this;
    }

    QlogFrames maxStreams(boolean bidirectional, long maximum) {
        open("max_streams").append(",\"stream_type\":\"").append(bidirectional ? "bidirectional" : "unidirectional");
        sb.append("\",\"maximum\":").append(maximum);
        close();
        return this;
    }

    QlogFrames dataBlocked(long limit) {
        open("data_blocked").append(",\"limit\":").append(limit);
        close();
        return this;
    }

    QlogFrames streamDataBlocked(long streamId, long limit) {
        open("stream_data_blocked").append(",\"stream_id\":").append(streamId);
        sb.append(",\"limit\":").append(limit);
        close();
        return this;
    }

    QlogFrames streamsBlocked(boolean bidirectional, long limit) {
        open("streams_blocked").append(",\"stream_type\":\"").append(bidirectional ? "bidirectional" : "unidirectional");
        sb.append("\",\"limit\":").append(limit);
        close();
        return this;
    }

    QlogFrames newConnectionId(long sequenceNumber, long retirePriorTo, byte[] connectionId, byte[] resetToken) {
        open("new_connection_id").append(",\"sequence_number\":").append(sequenceNumber);
        sb.append(",\"retire_prior_to\":").append(retirePriorTo);
        sb.append(",\"connection_id_length\":").append(connectionId.length);
        sb.append(",\"connection_id\":\"").append(QlogJson.hex(connectionId)).append('"');
        if (resetToken != null) {
            sb.append(",\"stateless_reset_token\":\"").append(QlogJson.hex(resetToken)).append('"');
        }
        close();
        return this;
    }

    QlogFrames retireConnectionId(long sequenceNumber) {
        open("retire_connection_id").append(",\"sequence_number\":").append(sequenceNumber);
        close();
        return this;
    }

    QlogFrames pathChallenge(byte[] data) {
        open("path_challenge").append(",\"data\":\"").append(QlogJson.hex(data)).append('"');
        close();
        return this;
    }

    QlogFrames pathResponse(byte[] data) {
        open("path_response").append(",\"data\":\"").append(QlogJson.hex(data)).append('"');
        close();
        return this;
    }

    QlogFrames connectionClose(boolean application, long errorCode, String reason) {
        open("connection_close").append(",\"error_space\":\"").append(application ? "application" : "transport");
        sb.append("\",\"error_code\":").append(errorCode);
        if (reason != null && !reason.isEmpty()) {
            sb.append(",\"reason\":");
            QlogJson.appendString(sb, reason);
        }
        close();
        return this;
    }

    QlogFrames handshakeDone() {
        open("handshake_done");
        close();
        return this;
    }

    QlogFrames datagram(int length) {
        open("datagram").append(",\"length\":").append(length);
        close();
        return this;
    }

    /** A frame the qlog drafts have no name for. */
    QlogFrames unknown(long frameType) {
        open("unknown").append(",\"raw_frame_type\":").append(frameType);
        close();
        return this;
    }

    private static String milliseconds(long micros) {
        long fraction = micros % 1000L;
        StringBuilder out = new StringBuilder(16);
        out.append(micros / 1000L).append('.');
        if (fraction < 100) {
            out.append('0');
        }
        if (fraction < 10) {
            out.append('0');
        }
        out.append(fraction);
        return out.toString();
    }
}
