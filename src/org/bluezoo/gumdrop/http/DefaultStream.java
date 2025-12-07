/*
 * DefaultStream.java
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

package org.bluezoo.gumdrop.http;

import java.net.ProtocolException;

/**
 * Default stream implementation that sends 404 responses to all requests.
 * This provides the default behavior when no specific handler is configured.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class DefaultStream extends Stream {

    protected DefaultStream(HTTPConnection connection, int streamId) {
        super(connection, streamId);
    }

    @Override
    protected void endHeaders(Headers headers) {
        // Send 404 Not Found for all requests
        try {
            if (HTTPConnection.LOGGER.isLoggable(java.util.logging.Level.FINEST)) {
                HTTPConnection.LOGGER.finest("DefaultStream.endHeaders() called, sending 404");
            }
            sendError(404);
            if (HTTPConnection.LOGGER.isLoggable(java.util.logging.Level.FINEST)) {
                HTTPConnection.LOGGER.finest("DefaultStream.endHeaders() sent 404 response");
            }
        } catch (ProtocolException e) {
            // Log error but don't propagate - connection will handle cleanup
            HTTPConnection.LOGGER.warning("Failed to send 404 response: " + e.getMessage());
        }
    }

    @Override
    protected void receiveRequestBody(byte[] buf) {
        // Ignore request body data - we're sending 404 anyway
    }

    @Override
    protected void endRequest() {
        // Nothing to do - response already sent in endHeaders
    }
}
