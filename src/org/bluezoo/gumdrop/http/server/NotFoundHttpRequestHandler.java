/*
 * NotFoundHttpRequestHandler.java
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

package org.bluezoo.gumdrop.http.server;

import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpStatus;

/**
 * Default HTTP application handler — responds with {@code 404 Not Found}.
 *
 * <p>Used when an {@link org.bluezoo.gumdrop.http.HttpServer} is composed
 * without an explicit handler. The protocol stack still accepts standard
 * methods; unknown methods receive {@code 501} from the HTTP layer.
 *
 * @see HttpStreamHandler
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class NotFoundHttpRequestHandler extends DefaultHttpRequestHandler {

    /** Shared stateless instance. */
    public static final NotFoundHttpRequestHandler INSTANCE =
            new NotFoundHttpRequestHandler();

    private NotFoundHttpRequestHandler() {
    }

    @Override
    public void headers(HttpResponseState state, Headers headers) {
        Headers response = new Headers();
        response.status(HttpStatus.NOT_FOUND);
        state.headers(response);
        state.complete();
    }

}
