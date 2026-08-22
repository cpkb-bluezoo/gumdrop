/*
 * CapsuleParser.java
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

package org.bluezoo.gumdrop.http;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.quic.packet.VarInt;

/**
 * Incremental Capsule Protocol parser (RFC 9297 section 3.2).
 *
 * <p>Feeds bytes from an HTTP data stream (HTTP/1.1 body / HTTP/2–HTTP/3
 * DATA). Unknown capsule types are surfaced so the caller can skip or
 * forward them.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Capsule
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9297#section-3.2">RFC 9297 section 3.2</a>
 */
public final class CapsuleParser {

    private byte[] buf = new byte[0];

    /**
     * Pushes inbound bytes and returns every complete capsule.
     *
     * @param data a chunk of the HTTP data stream; copied
     * @return the complete capsules in this chunk (possibly empty)
     * @throws CapsuleException if a capsule length is unrepresentable
     */
    public List<Capsule> push(ByteBuffer data) throws CapsuleException {
        if (data != null && data.hasRemaining()) {
            byte[] extra = new byte[data.remaining()];
            data.get(extra);
            byte[] combined = new byte[buf.length + extra.length];
            System.arraycopy(buf, 0, combined, 0, buf.length);
            System.arraycopy(extra, 0, combined, buf.length, extra.length);
            buf = combined;
        }
        List<Capsule> out = new ArrayList<Capsule>();
        int offset = 0;
        while (true) {
            ParseOne parsed = tryParseOne(buf, offset);
            if (parsed == null) {
                break;
            }
            out.add(parsed.capsule);
            offset += parsed.consumed;
        }
        if (offset == 0) {
            return out;
        }
        if (offset == buf.length) {
            buf = new byte[0];
        } else {
            byte[] rest = new byte[buf.length - offset];
            System.arraycopy(buf, offset, rest, 0, rest.length);
            buf = rest;
        }
        return out;
    }

    /**
     * Signals that the receive side ended cleanly. RFC 9297 section 3.3:
     * a truncated capsule is a stream error.
     *
     * @return true if no partial capsule remains
     */
    public boolean finish() {
        return buf.length == 0;
    }

    private static ParseOne tryParseOne(byte[] buf, int offset) throws CapsuleException {
        int remaining = buf.length - offset;
        if (remaining < 1) {
            return null;
        }
        ByteBuffer view = ByteBuffer.wrap(buf, offset, remaining);
        int typeLen = VarInt.peekEncodedLength(view, view.position());
        if (remaining < typeLen) {
            return null;
        }
        long type = VarInt.decode(view);
        remaining = view.remaining();
        if (remaining < 1) {
            return null;
        }
        int lengthLen = VarInt.peekEncodedLength(view, view.position());
        if (remaining < lengthLen) {
            return null;
        }
        long valueLength = VarInt.decode(view);
        if (valueLength > Integer.MAX_VALUE) {
            throw new CapsuleException("capsule value length exceeds Integer.MAX_VALUE");
        }
        int len = (int) valueLength;
        if (view.remaining() < len) {
            return null;
        }
        byte[] value = new byte[len];
        view.get(value);
        int consumed = view.position() - offset;
        return new ParseOne(new Capsule(type, value), consumed);
    }

    private static final class ParseOne {
        final Capsule capsule;
        final int consumed;

        ParseOne(Capsule capsule, int consumed) {
            this.capsule = capsule;
            this.consumed = consumed;
        }
    }

    /**
     * A Capsule Protocol encoding error (RFC 9297 section 3.3).
     */
    public static final class CapsuleException extends Exception {
        private static final long serialVersionUID = 1L;

        CapsuleException(String message) {
            super(message);
        }
    }
}
