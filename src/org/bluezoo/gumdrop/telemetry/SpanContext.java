/*
 * SpanContext.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.telemetry;

/**
 * Immutable context identifying a span within a trace.
 * This contains the trace ID, span ID, and trace flags for context propagation.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SpanContext {

    /**
     * Trace ID size in bytes (128-bit).
     */
    public static final int TRACE_ID_LENGTH = 16;

    /**
     * Span ID size in bytes (64-bit).
     */
    public static final int SPAN_ID_LENGTH = 8;

    /**
     * Trace flag indicating the trace is sampled.
     */
    public static final int FLAG_SAMPLED = 0x01;

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private final byte[] traceId;
    private final byte[] spanId;
    private final int traceFlags;

    /**
     * Creates a span context with the given identifiers.
     *
     * @param traceId the 16-byte trace ID
     * @param spanId the 8-byte span ID
     * @param sampled whether this trace is sampled
     */
    public SpanContext(byte[] traceId, byte[] spanId, boolean sampled) {
        if (traceId == null || traceId.length != TRACE_ID_LENGTH) {
            throw new IllegalArgumentException("traceId must be 16 bytes");
        }
        if (spanId == null || spanId.length != SPAN_ID_LENGTH) {
            throw new IllegalArgumentException("spanId must be 8 bytes");
        }
        this.traceId = copyBytes(traceId);
        this.spanId = copyBytes(spanId);
        this.traceFlags = sampled ? FLAG_SAMPLED : 0;
    }

    /**
     * Creates a span context with the given identifiers and flags.
     *
     * @param traceId the 16-byte trace ID
     * @param spanId the 8-byte span ID
     * @param traceFlags the trace flags
     */
    public SpanContext(byte[] traceId, byte[] spanId, int traceFlags) {
        if (traceId == null || traceId.length != TRACE_ID_LENGTH) {
            throw new IllegalArgumentException("traceId must be 16 bytes");
        }
        if (spanId == null || spanId.length != SPAN_ID_LENGTH) {
            throw new IllegalArgumentException("spanId must be 8 bytes");
        }
        this.traceId = copyBytes(traceId);
        this.spanId = copyBytes(spanId);
        this.traceFlags = traceFlags;
    }

    /**
     * Returns a copy of the trace ID bytes.
     */
    public byte[] getTraceId() {
        return copyBytes(traceId);
    }

    /**
     * Returns a copy of the span ID bytes.
     */
    public byte[] getSpanId() {
        return copyBytes(spanId);
    }

    /**
     * Returns the trace flags.
     */
    public int getTraceFlags() {
        return traceFlags;
    }

    /**
     * Returns true if the sampled flag is set.
     */
    public boolean isSampled() {
        return (traceFlags & FLAG_SAMPLED) != 0;
    }

    /**
     * Returns the trace ID as a lowercase hexadecimal string.
     */
    public String getTraceIdHex() {
        return bytesToHex(traceId);
    }

    /**
     * Returns the span ID as a lowercase hexadecimal string.
     */
    public String getSpanIdHex() {
        return bytesToHex(spanId);
    }

    /**
     * Returns the W3C traceparent header value.
     * Format: 00-{traceId}-{spanId}-{flags}
     */
    public String toTraceparent() {
        StringBuilder sb = new StringBuilder(55);
        sb.append("00-");
        sb.append(getTraceIdHex());
        sb.append('-');
        sb.append(getSpanIdHex());
        sb.append('-');
        sb.append(HEX_CHARS[(traceFlags >> 4) & 0x0F]);
        sb.append(HEX_CHARS[traceFlags & 0x0F]);
        return sb.toString();
    }

    /**
     * Parses a W3C traceparent header value.
     *
     * @param traceparent the header value
     * @return the parsed span context, or null if invalid
     */
    public static SpanContext fromTraceparent(String traceparent) {
        if (traceparent == null || traceparent.length() < 55) {
            return null;
        }
        // Format: 00-<32 hex traceId>-<16 hex spanId>-<2 hex flags>
        if (traceparent.charAt(2) != '-' || traceparent.charAt(35) != '-' || traceparent.charAt(52) != '-') {
            return null;
        }
        String version = traceparent.substring(0, 2);
        if (!"00".equals(version)) {
            return null;
        }
        try {
            byte[] traceId = hexToBytes(traceparent.substring(3, 35));
            byte[] spanId = hexToBytes(traceparent.substring(36, 52));
            int flags = Integer.parseInt(traceparent.substring(53, 55), 16);
            return new SpanContext(traceId, spanId, flags);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] copyBytes(byte[] source) {
        byte[] copy = new byte[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private static String bytesToHex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            result[i * 2] = HEX_CHARS[v >>> 4];
            result[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(result);
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Invalid hex string length");
        }
        byte[] result = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int high = Character.digit(hex.charAt(i), 16);
            int low = Character.digit(hex.charAt(i + 1), 16);
            if (high == -1 || low == -1) {
                throw new IllegalArgumentException("Invalid hex character");
            }
            result[i / 2] = (byte) ((high << 4) + low);
        }
        return result;
    }

    @Override
    public String toString() {
        return "SpanContext[traceId=" + getTraceIdHex() + ", spanId=" + getSpanIdHex() +
               ", sampled=" + isSampled() + "]";
    }

}

