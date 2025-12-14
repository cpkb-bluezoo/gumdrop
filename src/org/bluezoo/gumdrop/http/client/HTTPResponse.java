/*
 * HTTPResponse.java
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

package org.bluezoo.gumdrop.http.client;

import org.bluezoo.gumdrop.http.HTTPStatus;

/**
 * Represents an HTTP response status delivered to an {@link HTTPResponseHandler}.
 *
 * <p>This class provides only the essential status information. Individual headers
 * are delivered separately via the {@link HTTPResponseHandler#header(String, String)}
 * callback, which allows for streaming processing and proper handling of trailer
 * headers.
 *
 * <p>For redirect responses, this class provides access to the redirect location
 * and the chain of previous responses (for redirect following).
 *
 * <p><strong>Thread Safety:</strong> This class is immutable and thread-safe.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPResponseHandler
 * @see HTTPStatus
 */
public final class HTTPResponse {

    private final HTTPStatus status;
    private final String redirectLocation;
    private final HTTPResponse previousResponse;

    /**
     * Creates an HTTP response with the specified status.
     *
     * @param status the HTTP status
     */
    public HTTPResponse(HTTPStatus status) {
        this(status, null, null);
    }

    /**
     * Creates an HTTP response with the specified status and redirect location.
     *
     * @param status the HTTP status
     * @param redirectLocation the redirect location (for 3xx responses)
     */
    public HTTPResponse(HTTPStatus status, String redirectLocation) {
        this(status, redirectLocation, null);
    }

    /**
     * Creates an HTTP response with the specified status, redirect location,
     * and previous response in a redirect chain.
     *
     * @param status the HTTP status
     * @param redirectLocation the redirect location (for 3xx responses)
     * @param previousResponse the previous response in a redirect chain
     */
    public HTTPResponse(HTTPStatus status, String redirectLocation, HTTPResponse previousResponse) {
        if (status == null) {
            throw new NullPointerException("status");
        }
        this.status = status;
        this.redirectLocation = redirectLocation;
        this.previousResponse = previousResponse;
    }

    /**
     * Returns the HTTP status.
     *
     * @return the status (never null)
     */
    public HTTPStatus getStatus() {
        return status;
    }

    /**
     * Returns the redirect location for redirect responses (3xx).
     *
     * <p>This is the value of the Location header for redirect responses,
     * or null if this is not a redirect response or no Location was provided.
     *
     * @return the redirect location, or null
     */
    public String getRedirectLocation() {
        return redirectLocation;
    }

    /**
     * Returns the previous response in a redirect chain.
     *
     * <p>When the client follows redirects automatically, this provides access
     * to the chain of redirect responses. Returns null if this is the first
     * response (no redirects occurred) or redirect following is disabled.
     *
     * @return the previous response, or null
     */
    public HTTPResponse getPreviousResponse() {
        return previousResponse;
    }

    /**
     * Returns the number of redirects that occurred before this response.
     *
     * @return the redirect count (0 if no redirects)
     */
    public int getRedirectCount() {
        int count = 0;
        HTTPResponse prev = previousResponse;
        while (prev != null) {
            count++;
            prev = prev.previousResponse;
        }
        return count;
    }

    /**
     * Returns true if this is a success response (2xx status).
     *
     * @return true for 2xx statuses
     */
    public boolean isSuccess() {
        return status.isSuccess();
    }

    /**
     * Returns true if this is a redirect response (3xx status).
     *
     * @return true for 3xx statuses
     */
    public boolean isRedirection() {
        return status.isRedirection();
    }

    /**
     * Returns true if this is an error response (4xx or 5xx status).
     *
     * @return true for 4xx or 5xx statuses
     */
    public boolean isError() {
        return status.isError();
    }

    @Override
    public String toString() {
        if (redirectLocation != null) {
            return status + " -> " + redirectLocation;
        }
        return status.toString();
    }
}
