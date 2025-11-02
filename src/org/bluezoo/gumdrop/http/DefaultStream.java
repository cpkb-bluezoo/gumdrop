/*
 * DefaultStream.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.http;

import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
    protected void endHeaders(Collection<Header> headers) {
        // Send 404 Not Found for all requests
        try {
            sendError(404);
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
