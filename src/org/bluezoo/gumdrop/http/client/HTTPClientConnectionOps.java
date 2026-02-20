/*
 * HTTPClientConnectionOps.java
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

package org.bluezoo.gumdrop.http.client;

import java.nio.ByteBuffer;

/**
 * Operations required by {@link HTTPStream} to send requests.
 *
 * <p>Implemented by {@link HTTPClientProtocolHandler} so that
 * {@link HTTPStream} can delegate I/O operations.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
interface HTTPClientConnectionOps {

    /**
     * Sends a request.
     *
     * @param request the request to send
     * @param hasBody true if the request will have a body
     */
    void sendRequest(HTTPStream request, boolean hasBody);

    /**
     * Sends request body data.
     *
     * @param request the request
     * @param data the body data
     * @return the number of bytes consumed
     */
    int sendRequestBody(HTTPStream request, ByteBuffer data);

    /**
     * Ends the request body.
     *
     * @param request the request
     */
    void endRequestBody(HTTPStream request);

    /**
     * Cancels a request.
     *
     * @param request the request to cancel
     */
    void cancelRequest(HTTPStream request);
}
