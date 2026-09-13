/*
 * NotFoundHttpRequestHandler.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
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
 * @see HttpRequestHandlers#notFound()
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
