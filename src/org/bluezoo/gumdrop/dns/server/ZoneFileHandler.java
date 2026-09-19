/*
 * ZoneFileHandler.java
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

package org.bluezoo.gumdrop.dns.server;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Semantic callbacks for {@link ZoneFileParser}.
 *
 * <p>The parser lexes bytes once and invokes these methods feedforward as
 * each token is recognised. There is no line assembly or whitespace resplit.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ZoneFileParser
 */
public interface ZoneFileHandler {

    void origin(String origin) throws IOException;

    void defaultTtl(int ttl) throws IOException;

    void include(String filename, String originOverride) throws IOException;

    void unknownDirective(String name) throws IOException;

    /**
     * Starts a {@code $GENERATE} entry: {@code range} and owner-name template.
     */
    void beginGenerate(String rangeSpec, String ownerTemplate) throws IOException;

    /**
     * Starts a normal zone record with the owner-name token.
     */
    void beginRecord(String ownerToken) throws IOException;

    /**
     * One field token after the fixed header portion: optional TTL, optional
     * class, type, or rdata (including quoted strings and parenthesis tokens).
     */
    void appendField(String token) throws IOException;

    /**
     * Completes the current {@code $GENERATE} entry at end-of-line.
     */
    void endGenerate() throws IOException;

    /**
     * Completes the current record at end-of-line.
     */
    void endRecord() throws IOException;

    void endFile(Path file) throws IOException;
}
