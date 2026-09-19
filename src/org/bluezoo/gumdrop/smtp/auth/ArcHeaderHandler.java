/*
 * ArcHeaderHandler.java
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

package org.bluezoo.gumdrop.smtp.auth;

/**
 * Callback interface for ARC header fields as they are captured from the
 * message (RFC 8617 section 4).
 *
 * <p>Registered on an {@link ArcHeaderParser} and driven incrementally while
 * {@link DkimMessageParser} receives header lines. No grouped result object
 * is returned synchronously from the parser; the handler accumulates state
 * and reacts to {@link #arcHeadersEnd()} when the header block finishes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ArcHeaderParser
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8617">RFC 8617 - ARC</a>
 */
public interface ArcHeaderHandler {

    /**
     * Delivers one {@code ARC-Authentication-Results} header line.
     *
     * @param instance the {@code i=} instance number
     * @param rawLine the full header line as received (including CRLF)
     */
    void arcAuthenticationResults(int instance, String rawLine);

    /**
     * Delivers one {@code ARC-Message-Signature} header line.
     *
     * @param instance the {@code i=} instance number
     * @param rawLine the full header line as received
     * @param parsed parsed DKIM-style tags from the header value
     */
    void arcMessageSignature(int instance, String rawLine,
                             DkimSignature parsed);

    /**
     * Delivers one {@code ARC-Seal} header line.
     *
     * @param instance the {@code i=} instance number
     * @param rawLine the full header line as received
     * @param parsed parsed DKIM-style tags from the header value
     * @param sealCv the {@code cv=} tag from this seal (syntactic only)
     */
    void arcSeal(int instance, String rawLine, DkimSignature parsed,
                 ArcCvResult sealCv);

    /**
     * Signals that the message header section has ended (empty line).
     *
     * <p>Implementations should finalize structural checks (contiguous
     * instances {@code i=1..N}, complete triplets) and prepare for
     * cryptographic validation via {@link ArcValidator}.
     */
    void arcHeadersEnd();

}
