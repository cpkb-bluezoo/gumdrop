/*
 * FieldSectionAdapter.java
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

/**
 * Turns one HTTP/2 or HTTP/3 field section into {@link HttpMessageHandler}
 * events. It is the {@link HeaderFieldHandler} that the HPACK and QPACK
 * decoders push their fields into, so decoding a field section and applying
 * the rules for what it may contain are separate steps: the decoder always
 * reads the whole section, keeping its compression table in step with the
 * peer's, and this class decides whether the section is acceptable.
 *
 * <p>The pseudo-headers become the start events ({@code :method} to
 * {@code method}, {@code :path} to {@code target}, {@code :scheme},
 * {@code :authority} and {@code :protocol} likewise, {@code :status} to
 * {@code status}); the other fields go through a {@link FieldDispatcher}, so
 * they produce the same events as they do in HTTP/1.x. A {@code version} event
 * comes first.
 *
 * <p>The rules applied are those of RFC 9113 section 8.2 and 8.3 and
 * RFC 9114 section 4.2 and 4.3: names are lower case; values have no control
 * characters and do not begin or end with white space; pseudo-headers come
 * before the other fields, each at most once, only those defined for the kind
 * of message, and none in trailers; connection-specific fields
 * ({@code Connection}, {@code Keep-Alive}, {@code Proxy-Connection},
 * {@code Transfer-Encoding}, {@code Upgrade}) are forbidden and {@code TE} may
 * say only {@code trailers}; and a request has the pseudo-headers it needs
 * (RFC 9113 section 8.3.1) and a response has {@code :status}.
 *
 * <p>The first violation is remembered and everything after it is ignored. It
 * is reported, as a single {@code error} event, by {@link #finish()}, once the
 * decoder has read the whole section. A message that fails these checks is a
 * stream error in both protocols (RFC 9113 section 8.1.1, RFC 9114 section
 * 4.1.2), not a connection error. Events sent before the violation was seen
 * are provisional, as for any message that has not reached {@code endHeaders}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class FieldSectionAdapter implements HeaderFieldHandler {

    /** Which section of a message this is. */
    public enum Kind {
        /** The header section of a request. */
        REQUEST,
        /** The header section of a response. */
        RESPONSE,
        /** The trailer section that follows a body. */
        TRAILERS
    }

    private final HttpMessageHandler handler;
    private final HttpVersion version;
    private final Kind kind;
    private final FieldDispatcher dispatcher;
    private final HeaderFieldHandler tap;

    private boolean versionSent;
    private boolean sawRegularField;
    private boolean failed;
    private String malformed;

    private HttpMethod method;
    private boolean haveScheme;
    private boolean haveAuthority;
    private boolean havePath;
    private boolean haveProtocol;
    private boolean haveStatus;

    /**
     * Creates an adapter for one field section.
     *
     * @param handler receives the events
     * @param version the protocol the section arrived over
     * @param kind which section of a message it is
     */
    public FieldSectionAdapter(HttpMessageHandler handler, HttpVersion version, Kind kind) {
        this(handler, version, kind, null);
    }

    /**
     * Creates an adapter that also passes every accepted field, as the
     * original octets, to {@code tap}. The typed events do not always keep
     * the exact text of a field ({@code Content-Length: 007} is the number 7),
     * so a receiver that needs the field as it was sent, as the server does for
     * its own bookkeeping, takes it from here.
     *
     * @param handler receives the events
     * @param version the protocol the section arrived over
     * @param kind which section of a message it is
     * @param tap receives each accepted field's name and value octets, or null
     */
    public FieldSectionAdapter(HttpMessageHandler handler, HttpVersion version, Kind kind,
            HeaderFieldHandler tap) {
        this.tap = tap;
        this.handler = handler;
        this.version = version;
        this.kind = kind;
        this.dispatcher = new FieldDispatcher(handler);
    }

    @Override
    public void field(ByteBuffer name, ByteBuffer value) {
        if (failed || malformed != null) {
            return;
        }
        sendVersion();
        int nameLength = name.remaining();
        int n = name.position();
        if (nameLength == 0) {
            malformed = "empty field name";
            return;
        }
        boolean pseudo = name.get(n) == ':';
        for (int i = pseudo ? 1 : 0; i < nameLength; i++) {
            byte b = name.get(n + i);
            if (b >= 'A' && b <= 'Z') {
                malformed = "upper-case field name";
                return;
            }
            if (!HttpUtils.isTokenChar(b)) {
                malformed = "invalid field name";
                return;
            }
        }
        if (pseudo && nameLength == 1) {
            malformed = "invalid field name";
            return;
        }
        if (!validValue(value)) {
            malformed = "invalid value for " + text(name);
            return;
        }
        int namePosition = name.position();
        int valuePosition = value.position();
        if (pseudo) {
            pseudoHeader(text(name), value);
        } else {
            regularField(text(name), value);
        }
        if (tap != null && !failed && malformed == null) {
            // a receiver may have consumed the buffers; give the tap the whole field
            name.position(namePosition);
            value.position(valuePosition);
            tap.field(name, value);
        }
    }

    /**
     * Ends the section: checks that the pseudo-headers a message needs are
     * all there, and reports {@code endHeaders} for a request or response.
     *
     * @return true if the section was accepted; false if it was refused, in
     *     which case the handler has been given an {@code error}
     */
    public boolean finish() {
        if (failed) {
            return false;
        }
        sendVersion();
        if (malformed == null) {
            malformed = missingPseudoHeader();
        }
        if (malformed != null) {
            failed = true;
            handler.error(HttpError.MALFORMED, malformed);
            return false;
        }
        if (kind != Kind.TRAILERS) {
            handler.endHeaders();
        }
        return true;
    }

    private void sendVersion() {
        if (!versionSent && kind != Kind.TRAILERS) {
            versionSent = true;
            handler.version(version);
        }
    }

    private void pseudoHeader(String name, ByteBuffer value) {
        if (kind == Kind.TRAILERS) {
            malformed = "pseudo-header in trailers";
            return;
        }
        if (sawRegularField) {
            malformed = "pseudo-header " + name + " after a regular field";
            return;
        }
        if (kind == Kind.RESPONSE) {
            if (!name.equals(":status") || haveStatus) {
                malformed = "unexpected pseudo-header " + name + " in a response";
                return;
            }
            int code = parseStatus(value);
            if (code < 0) {
                malformed = "invalid :status";
                return;
            }
            haveStatus = true;
            handler.status(code);
            return;
        }
        if (name.equals(":method")) {
            if (method != null) {
                malformed = "duplicate :method";
                return;
            }
            try {
                method = HttpMethod.of(value);
            } catch (IllegalArgumentException e) {
                malformed = "invalid :method";
                return;
            }
            handler.method(method);
        } else if (name.equals(":scheme")) {
            if (haveScheme) {
                malformed = "duplicate :scheme";
                return;
            }
            haveScheme = true;
            handler.scheme(value);
        } else if (name.equals(":authority")) {
            if (haveAuthority) {
                malformed = "duplicate :authority";
                return;
            }
            haveAuthority = true;
            handler.authority(value);
        } else if (name.equals(":path")) {
            if (havePath) {
                malformed = "duplicate :path";
                return;
            }
            if (!value.hasRemaining()) {
                malformed = "empty :path";
                return;
            }
            havePath = true;
            handler.target(value);
        } else if (name.equals(":protocol")) {
            if (haveProtocol) {
                malformed = "duplicate :protocol";
                return;
            }
            haveProtocol = true;
            handler.protocol(value);
        } else {
            malformed = "unknown pseudo-header " + name;
        }
    }

    private void regularField(String name, ByteBuffer value) {
        sawRegularField = true;
        if (name.equals("connection") || name.equals("keep-alive") || name.equals("proxy-connection")
                || name.equals("transfer-encoding") || name.equals("upgrade")) {
            malformed = "connection-specific field " + name;
            return;
        }
        if (name.equals("te") && !isTrailers(value)) {
            malformed = "TE other than trailers";
            return;
        }
        if (kind == Kind.TRAILERS) {
            dispatcher.trailerField(name, value);
        } else if (!dispatcher.field(name, value)) {
            failed = true;
        }
    }

    /** What the pseudo-headers seen say is missing, or null if nothing is. */
    private String missingPseudoHeader() {
        if (kind == Kind.RESPONSE) {
            return haveStatus ? null : "missing :status";
        }
        if (kind != Kind.REQUEST) {
            return null;
        }
        if (method == null) {
            return "missing :method";
        }
        boolean plainConnect = method == HttpMethod.CONNECT && !haveProtocol;
        if (plainConnect) {
            // RFC 9113 section 8.5
            if (!haveAuthority) {
                return "CONNECT without :authority";
            }
            if (haveScheme || havePath) {
                return "CONNECT with :scheme or :path";
            }
            return null;
        }
        if (!haveScheme) {
            return "missing :scheme";
        }
        if (!havePath) {
            return "missing :path";
        }
        return null;
    }

    /** :status = 3DIGIT, 100 to 599; returns -1 otherwise. */
    private static int parseStatus(ByteBuffer value) {
        if (value.remaining() != 3) {
            return -1;
        }
        int code = 0;
        for (int i = value.position(); i < value.limit(); i++) {
            byte b = value.get(i);
            if (b < '0' || b > '9') {
                return -1;
            }
            code = code * 10 + (b - '0');
        }
        return code >= 100 && code <= 599 ? code : -1;
    }

    /** RFC 9113 section 8.2.1: no control characters, and no white space at either end. */
    private static boolean validValue(ByteBuffer value) {
        int from = value.position();
        int to = value.limit();
        for (int i = from; i < to; i++) {
            if (!HttpUtils.isFieldValueOctet(value.get(i))) {
                return false;
            }
        }
        if (to > from) {
            byte first = value.get(from);
            byte last = value.get(to - 1);
            if (first == ' ' || first == '\t' || last == ' ' || last == '\t') {
                return false;
            }
        }
        return true;
    }

    private static boolean isTrailers(ByteBuffer value) {
        String trailers = "trailers";
        if (value.remaining() != trailers.length()) {
            return false;
        }
        for (int i = 0; i < trailers.length(); i++) {
            int b = value.get(value.position() + i);
            if (b >= 'A' && b <= 'Z') {
                b += 32;
            }
            if (b != trailers.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String text(ByteBuffer b) {
        char[] chars = new char[b.remaining()];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) (b.get(b.position() + i) & 0xFF);
        }
        return new String(chars);
    }

}
