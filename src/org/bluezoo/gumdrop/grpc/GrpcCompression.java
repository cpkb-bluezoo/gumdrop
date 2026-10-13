/*
 * GrpcCompression.java
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

package org.bluezoo.gumdrop.grpc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bluezoo.gumdrop.http.ContentEncoding;
import org.bluezoo.gumdrop.http.ContentEncoding.Coding;
import org.bluezoo.gumdrop.http.ContentEncoding.ContentEncodingException;
import org.bluezoo.gumdrop.http.ContentEncoding.Decoder;
import org.bluezoo.gumdrop.http.ContentEncoding.SizeLimitException;

/**
 * Message compression for gRPC, as the gRPC HTTP/2 protocol specification
 * has it: each message is compressed on its own, with the algorithm that the
 * {@code grpc-encoding} header names, and the {@code grpc-accept-encoding}
 * header lists the algorithms a peer can decode. {@code gzip} and
 * {@code deflate} are the specification's; {@code br} (Brotli) is an extension
 * that works only between peers that both have it, so it is never used unless
 * it is asked for.
 *
 * <p>The algorithms are those of {@link Coding}, and the coders are
 * {@link ContentEncoding}'s; {@code identity}, no compression, is always
 * allowed and is not a {@code Coding}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class GrpcCompression {

    /** The {@code grpc-encoding} token for no compression. */
    public static final String IDENTITY = "identity";

    /** What a gRPC peer is assumed to decode unless it is told otherwise. */
    public static final Coding[] DEFAULT_ENCODINGS = {Coding.GZIP, Coding.DEFLATE};

    private GrpcCompression() {
    }

    /**
     * Returns the algorithm a {@code grpc-encoding} token names.
     *
     * @param token the token, such as {@code gzip}
     * @return the algorithm, or {@code null} for {@code identity} and for a
     *         token that is not one this class knows
     */
    public static Coding coding(String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim().toLowerCase(Locale.ROOT);
        for (Coding c : Coding.values()) {
            if (c.token().equals(t)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Returns whether a {@code grpc-encoding} token means no compression.
     *
     * @param token the token
     * @return true for {@code identity}, or no token
     */
    public static boolean isIdentity(String token) {
        return token == null || token.trim().isEmpty()
                || IDENTITY.equalsIgnoreCase(token.trim());
    }

    /**
     * Returns the value of {@code grpc-accept-encoding} for the algorithms a
     * peer can decode: {@code identity} first, then each of them.
     *
     * @param encodings the algorithms
     * @return the header value
     */
    public static String acceptEncoding(Coding... encodings) {
        StringBuilder out = new StringBuilder(IDENTITY);
        for (int i = 0; i < encodings.length; i++) {
            out.append(',').append(encodings[i].token());
        }
        return out.toString();
    }

    /**
     * Returns the algorithms a {@code grpc-accept-encoding} value lists that
     * this class knows, in the order listed.
     *
     * @param header the header value, or {@code null}
     * @return the algorithms, empty if there are none
     */
    public static List<Coding> parseAcceptEncoding(String header) {
        List<Coding> out = new ArrayList<Coding>();
        if (header == null) {
            return out;
        }
        int start = 0;
        while (start <= header.length()) {
            int end = header.indexOf(',', start);
            if (end < 0) {
                end = header.length();
            }
            Coding c = coding(header.substring(start, end));
            if (c != null && !out.contains(c)) {
                out.add(c);
            }
            start = end + 1;
        }
        return out;
    }

    /**
     * Chooses the algorithm to compress with: the first of those on offer that
     * the peer lists in its {@code grpc-accept-encoding}.
     *
     * @param offered the algorithms that can be used, most wanted first
     * @param acceptEncoding the peer's {@code grpc-accept-encoding} value, or
     *        {@code null}
     * @return the algorithm, or {@code null} if there is none in common
     */
    public static Coding select(Coding[] offered, String acceptEncoding) {
        List<Coding> accepted = parseAcceptEncoding(acceptEncoding);
        for (int i = 0; i < offered.length; i++) {
            if (accepted.contains(offered[i])) {
                return offered[i];
            }
        }
        return null;
    }

    /**
     * Compresses a message.
     *
     * @param coding the algorithm
     * @param message the serialised message
     * @return the compressed message, or {@code null} if it would not be
     *         smaller, in which case it is better sent as it is
     * @throws GrpcException with {@code INTERNAL} if compression fails
     */
    public static byte[] compress(Coding coding, ByteBuffer message) {
        byte[] plain = new byte[message.remaining()];
        message.duplicate().get(plain);
        byte[] packed;
        try {
            packed = ContentEncoding.compressFully(coding, plain);
        } catch (ContentEncodingException e) {
            throw new GrpcException(GrpcStatus.INTERNAL, "Compression failed", e);
        }
        return packed.length < plain.length ? packed : null;
    }

    /**
     * Creates a decoder for one compressed message. Its output is limited to
     * the largest message size, so that a small message cannot expand without
     * bound.
     *
     * @param coding the algorithm
     * @param maxMessageSize the largest message size; 0 for no limit
     * @return the decoder; close it when the message is done
     */
    public static Decoder newDecoder(Coding coding, long maxMessageSize) {
        int limit = maxMessageSize > 0 && maxMessageSize < Integer.MAX_VALUE
                ? (int) maxMessageSize : Integer.MAX_VALUE;
        return ContentEncoding.createDecoder(coding, limit);
    }

    /**
     * Returns the gRPC failure that a decoding failure is: a message that
     * expands beyond the limit is {@code RESOURCE_EXHAUSTED} (8), a corrupt
     * or truncated one {@code INTERNAL} (13).
     *
     * @param e the failure
     * @return the failure as a gRPC status
     */
    public static GrpcException failure(ContentEncodingException e) {
        if (e instanceof SizeLimitException) {
            return new GrpcException(GrpcStatus.RESOURCE_EXHAUSTED,
                    "Decompressed message exceeds the limit");
        }
        return new GrpcException(GrpcStatus.INTERNAL, "Corrupt compressed message");
    }
}
