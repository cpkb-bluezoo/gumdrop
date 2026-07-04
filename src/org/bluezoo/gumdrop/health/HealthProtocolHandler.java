/*
 * HealthProtocolHandler.java
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

package org.bluezoo.gumdrop.health;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;

/**
 * Minimal HTTP/1.1 handler that answers liveness and readiness probes.
 *
 * <p>This is intentionally tiny and dependency-free (it does not reuse the
 * full HTTP stack) so it stays available even while the main protocols are
 * mid-startup or draining. It reports the server lifecycle state observed
 * from {@link Gumdrop}:
 *
 * <ul>
 * <li><b>Liveness</b> ({@code /livez}, {@code /healthz}, {@code /health}) —
 *     always {@code 200} while the process is up, so orchestrators do not
 *     kill a server that is merely draining.</li>
 * <li><b>Readiness</b> ({@code /readyz} or any other path) — {@code 200
 *     ready} once all listeners are bound, {@code 503 starting} before that,
 *     and {@code 503 draining} during graceful shutdown, so load balancers
 *     stop routing new work.</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HealthListener
 */
final class HealthProtocolHandler implements ProtocolHandler {

    /** Bound on the buffered request so a stalled client cannot grow it. */
    private static final int MAX_REQUEST_BYTES = 8192;

    private Endpoint endpoint;
    private final StringBuilder requestLine = new StringBuilder();
    private boolean responded;

    @Override
    public void connected(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public void receive(ByteBuffer data) {
        if (responded) {
            return;
        }
        while (data.hasRemaining()) {
            char c = (char) (data.get() & 0xFF);
            if (c == '\n') {
                respondForRequestLine(requestLine.toString());
                return;
            }
            if (c != '\r' && requestLine.length() < MAX_REQUEST_BYTES) {
                requestLine.append(c);
            }
            if (requestLine.length() >= MAX_REQUEST_BYTES) {
                respond(400, "Bad Request", "request line too long\n");
                return;
            }
        }
    }

    private void respondForRequestLine(String line) {
        // Request line: METHOD SP PATH SP VERSION
        String path = "/";
        int firstSpace = line.indexOf(' ');
        if (firstSpace >= 0) {
            int secondSpace = line.indexOf(' ', firstSpace + 1);
            if (secondSpace > firstSpace) {
                path = line.substring(firstSpace + 1, secondSpace);
            } else if (firstSpace + 1 < line.length()) {
                path = line.substring(firstSpace + 1);
            }
        }
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }

        Gumdrop gumdrop = Gumdrop.getInstance();
        String state = stateOf(gumdrop);

        if (isLivenessPath(path)) {
            // Alive whenever the process is serving this request.
            respond(200, "OK", state + "\n");
        } else {
            boolean ready = gumdrop != null && gumdrop.isReady();
            if (ready) {
                respond(200, "OK", state + "\n");
            } else {
                respond(503, "Service Unavailable", state + "\n");
            }
        }
    }

    private static String stateOf(Gumdrop gumdrop) {
        if (gumdrop == null || !gumdrop.isStarted()) {
            return "starting";
        }
        if (gumdrop.isDraining()) {
            return "draining";
        }
        return gumdrop.isReady() ? "ready" : "starting";
    }

    private static boolean isLivenessPath(String path) {
        return "/livez".equals(path)
                || "/healthz".equals(path)
                || "/health".equals(path);
    }

    private void respond(int status, String reason, String body) {
        responded = true;
        if (endpoint == null) {
            return;
        }
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        byte[] headerBytes = headers.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf =
                ByteBuffer.allocate(headerBytes.length + bodyBytes.length);
        buf.put(headerBytes);
        buf.put(bodyBytes);
        buf.flip();
        endpoint.send(buf);
        endpoint.close();
    }

    @Override
    public void disconnected() {
        // Nothing to clean up.
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        // Health endpoint is plaintext; not expected.
    }

    @Override
    public void error(Exception cause) {
        // Best-effort: nothing to do, the endpoint will be closed.
    }
}
