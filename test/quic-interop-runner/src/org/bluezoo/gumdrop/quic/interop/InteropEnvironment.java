/*
 * InteropEnvironment.java
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

package org.bluezoo.gumdrop.quic.interop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.tls.KeyLog;

/**
 * The contract between the quic-interop-runner and an endpoint container,
 * read from the environment: the role, the test case, the files to fetch,
 * and the mounted directories ({@code /www}, {@code /downloads},
 * {@code /certs}).
 *
 * <p>Every path and the port can be overridden with {@code INTEROP_*}
 * variables so that the same mains run outside Docker, against a local
 * directory of files and a local certificate pair.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class InteropEnvironment {

    private final String role;
    private final String testCase;
    private final List<String> requests;
    private final Path www;
    private final Path downloads;
    private final Path certs;
    private final int port;

    private InteropEnvironment(String role, String testCase, List<String> requests,
            Path www, Path downloads, Path certs, int port) {
        this.role = role;
        this.testCase = testCase;
        this.requests = requests;
        this.www = www;
        this.downloads = downloads;
        this.certs = certs;
        this.port = port;
    }

    static InteropEnvironment fromSystem() {
        List<String> requests = new ArrayList<String>();
        String raw = System.getenv("REQUESTS");
        if (raw != null) {
            String[] tokens = raw.trim().split("\\s+");
            for (int i = 0; i < tokens.length; i++) {
                if (tokens[i].length() > 0) {
                    requests.add(tokens[i]);
                }
            }
        }
        return new InteropEnvironment(
                System.getenv("ROLE"),
                System.getenv("TESTCASE"),
                requests,
                Path.of(getenv("INTEROP_WWW", "/www")),
                Path.of(getenv("INTEROP_DOWNLOADS", "/downloads")),
                Path.of(getenv("INTEROP_CERTS", "/certs")),
                Integer.parseInt(getenv("INTEROP_PORT", "443")));
    }

    private static String getenv(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value == null || value.length() == 0) ? defaultValue : value;
    }

    String role() {
        return role;
    }

    String testCase() {
        return testCase;
    }

    List<String> requests() {
        return requests;
    }

    Path www() {
        return www;
    }

    Path downloads() {
        return downloads;
    }

    Path certFile() {
        return certs.resolve("cert.pem");
    }

    Path keyFile() {
        return certs.resolve("priv.key");
    }

    Path caFile() {
        return certs.resolve("ca.pem");
    }

    /** The server's UDP port; the runner expects 443. */
    int port() {
        return port;
    }

    /**
     * Writes every connection's TLS secrets to the file the runner names
     * in {@code SSLKEYLOGFILE}, which it needs to decrypt the traces for
     * several of its checks. Nothing is written when the variable is
     * unset.
     */
    static void enableKeyLog() {
        Logger logger = Logger.getLogger(InteropEnvironment.class.getName());
        try {
            KeyLog keyLog = KeyLog.fromEnvironment();
            if (keyLog != null) {
                KeyLog.setDefault(keyLog);
                logger.info("writing TLS secrets to " + System.getenv(KeyLog.ENVIRONMENT_VARIABLE));
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "cannot open " + KeyLog.ENVIRONMENT_VARIABLE + " file", e);
        }
    }

}
