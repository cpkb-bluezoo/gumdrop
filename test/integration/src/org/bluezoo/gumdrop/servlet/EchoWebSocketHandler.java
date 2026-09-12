/*
 * EchoWebSocketHandler.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.servlet;

import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Servlet {@link HttpUpgradeHandler} used by integration tests. Echoes
 * UTF-8 text received on the upgraded {@link WebConnection}.
 */
public class EchoWebSocketHandler implements HttpUpgradeHandler {

    private volatile WebConnection webConnection;
    private volatile boolean active;

    @Override
    public void init(WebConnection webConnection) {
        this.webConnection = webConnection;
        this.active = true;
        try {
            echoLoop();
        } catch (IOException e) {
            if (active) {
                // connection closed unexpectedly during echo loop
            }
        } finally {
            active = false;
        }
    }

    @Override
    public void destroy() {
        active = false;
        WebConnection connection = webConnection;
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private void echoLoop() throws IOException {
        WebConnection connection = webConnection;
        if (connection == null) {
            return;
        }

        try (InputStream input = connection.getInputStream();
             OutputStream output = connection.getOutputStream()) {

            byte[] buffer = new byte[4096];
            while (active) {
                int bytesRead = input.read(buffer);
                if (bytesRead == -1) {
                    break;
                }
                if (bytesRead <= 0) {
                    continue;
                }

                String received = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                String response = "Echo: " + received;
                output.write(response.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        }
    }
}
