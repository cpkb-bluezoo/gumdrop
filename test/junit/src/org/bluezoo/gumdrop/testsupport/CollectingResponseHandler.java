/*
 * CollectingResponseHandler.java
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

package org.bluezoo.gumdrop.testsupport;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.client.DefaultHttpResponseHandler;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;

/**
 * A response handler for tests that want a response as a few coarse
 * callbacks rather than field-by-field events: {@link #ok} or {@link #error}
 * with the status, {@link #header} for each field (leading, then trailing),
 * {@link #startResponseBody}, {@link #responseBodyContent} and
 * {@link #endResponseBody} around the body, and {@link #close} at the end of
 * the message.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class CollectingResponseHandler extends DefaultHttpResponseHandler {

    private boolean headersEnded;
    private boolean bodyStarted;
    private boolean bodyEnded;

    /** A 2xx status. */
    public void ok(HttpStatus status) {
    }

    /** Any other status. */
    public void error(HttpStatus status) {
    }

    /** A field, as a string. */
    public void header(String name, String value) {
    }

    /** The first piece of the body is about to arrive. */
    public void startResponseBody() {
    }

    /** A piece of the body. */
    public void responseBodyContent(ByteBuffer data) {
    }

    /** The body has ended. */
    public void endResponseBody() {
    }

    /** The end of the response. */
    public void close() {
    }

    private static String text(ByteBuffer b) {
        byte[] octets = new byte[b.remaining()];
        b.duplicate().get(octets);
        return new String(octets, StandardCharsets.ISO_8859_1);
    }

    private void field(String name, String value) {
        if (headersEnded) {
            endBody();
        }
        header(name, value);
    }

    @Override
    public void status(int code) {
        HttpStatus status = HttpStatus.fromCode(code);
        if (status.isSuccess()) {
            ok(status);
        } else {
            error(status);
        }
    }

    @Override
    public void contentType(ContentType contentType) {
        field("content-type", contentType.toHeaderValue());
    }

    @Override
    public void contentDisposition(ContentDisposition contentDisposition) {
        field("content-disposition", contentDisposition.toHeaderValue());
    }

    @Override
    public void longHeader(String name, long value) {
        field(name, Long.toString(value));
    }

    @Override
    public void header(String name, ByteBuffer value) {
        field(name, text(value));
    }

    @Override
    public void endHeaders() {
        headersEnded = true;
    }

    @Override
    public void bodyContent(ByteBuffer data) {
        if (!bodyStarted) {
            bodyStarted = true;
            startResponseBody();
        }
        responseBodyContent(data);
    }

    private void endBody() {
        if (bodyStarted && !bodyEnded) {
            bodyEnded = true;
            endResponseBody();
        }
    }

    @Override
    public void endMessage() {
        endBody();
        close();
    }
}
