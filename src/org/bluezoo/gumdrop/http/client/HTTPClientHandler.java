/*
 * HTTPClientHandler.java
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

import org.bluezoo.gumdrop.ClientHandler;

/**
 * Handler interface for HTTP client connection lifecycle events.
 *
 * <p>This interface extends {@link ClientHandler} to receive TCP connection
 * lifecycle events for HTTP clients. Unlike stateful protocols such as SMTP,
 * HTTP is stateless and does not require protocol-specific session callbacks.
 *
 * <p>Once a connection is established (via {@link #onConnected}), the client
 * can immediately begin making HTTP requests through the {@link HTTPClient}
 * request factory methods.
 *
 * <h4>Basic Usage</h4>
 * <pre>{@code
 * HTTPClient client = new HTTPClient("api.example.com", 443);
 * client.setSecure(true);
 * client.connect(new HTTPClientHandler() {
 *     public void onConnected(ConnectionInfo info) {
 *         // Connection ready - can now make requests
 *         client.get("/users").send(responseHandler);
 *     }
 *
 *     public void onTLSStarted(TLSInfo info) {
 *         // TLS handshake complete
 *     }
 *
 *     public void onError(Exception cause) {
 *         cause.printStackTrace();
 *     }
 *
 *     public void onDisconnected() {
 *         System.out.println("Connection closed");
 *     }
 * });
 * }</pre>
 *
 * <h4>HTTP/2 ALPN Negotiation</h4>
 *
 * <p>For secure connections, the HTTP version is negotiated during the TLS
 * handshake via ALPN. The {@link #onTLSStarted} callback indicates when this
 * negotiation is complete. After this callback, {@link HTTPClient#getVersion()}
 * returns the negotiated version (HTTP/1.1 or HTTP/2).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPClient
 * @see ClientHandler
 */
public interface HTTPClientHandler extends ClientHandler {

    // No additional methods required.
    // HTTP is stateless - once connected, requests are made via HTTPClient methods.

}

