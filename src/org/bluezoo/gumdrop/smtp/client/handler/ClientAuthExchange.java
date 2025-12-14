/*
 * ClientAuthExchange.java
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

package org.bluezoo.gumdrop.smtp.client.handler;

/**
 * Operations during SASL authentication exchange.
 * 
 * <p>This interface is provided to the handler in
 * {@link ServerAuthReplyHandler#handleChallenge} when the server sends a
 * SASL challenge (334 response). The handler computes the appropriate
 * response based on the SASL mechanism and continues the exchange.
 * 
 * <p>The connection handles base64 encoding/decoding automatically - the
 * handler works with raw bytes.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerAuthReplyHandler#handleChallenge
 */
public interface ClientAuthExchange {

    /**
     * Sends a response to the server's SASL challenge.
     * 
     * <p>The response bytes will be base64 encoded by the connection before
     * sending to the server. The callback receives the server's next response,
     * which may be another challenge (334), success (235), or failure (535).
     * 
     * @param response the response bytes (will be base64 encoded)
     * @param callback receives the server's next response
     */
    void respond(byte[] response, ServerAuthReplyHandler callback);

    /**
     * Aborts the authentication exchange.
     * 
     * <p>Sends "*" to the server to cancel the SASL exchange. Use this if
     * the handler cannot compute a valid response or wants to try a different
     * authentication approach.
     * 
     * @param callback receives the abort confirmation
     */
    void abort(ServerAuthAbortHandler callback);

}

