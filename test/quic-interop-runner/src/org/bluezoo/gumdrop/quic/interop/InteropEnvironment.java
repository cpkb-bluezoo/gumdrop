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
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.quic.QuicTransportFactory;
import org.bluezoo.gumdrop.telemetry.QlogExporter;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
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
     * The server's preferred address of one family for the
     * connectionmigration case: {@code INTEROP_PREFERRED_IPV4} or
     * {@code INTEROP_PREFERRED_IPV6} (the host's own addresses, which
     * run_endpoint.sh fills in) on {@code INTEROP_PREFERRED_PORT}.
     *
     * @return the address and port, or null if the variable is unset
     */
    static InetSocketAddress preferredAddress(boolean ipv6) {
        String host = getenv(ipv6 ? "INTEROP_PREFERRED_IPV6" : "INTEROP_PREFERRED_IPV4", null);
        if (host == null) {
            return null;
        }
        int port = Integer.parseInt(getenv("INTEROP_PREFERRED_PORT", "4434"));
        try {
            InetAddress address = InetAddress.getByName(host);
            if ((address instanceof Inet6Address) != ipv6) {
                return null;
            }
            return new InetSocketAddress(address, port);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * Makes connections on the factory report qlog events, written to the
     * directory the runner names in {@code QLOGDIR}, so that the runner
     * stores gumdrop's qlog next to the trace and the key log. Nothing is
     * done when the variable is unset.
     *
     * @param factory the transport factory of the endpoint
     */
    static void enableQlog(QuicTransportFactory factory) {
        TelemetryConfig telemetry = qlogTelemetry(System.getenv("QLOGDIR"));
        if (telemetry != null) {
            factory.setTelemetryConfig(telemetry);
            Logger.getLogger(InteropEnvironment.class.getName())
                    .info("writing qlog to " + System.getenv("QLOGDIR"));
        }
    }

    /**
     * The telemetry configuration that writes qlog files to a directory:
     * the qlog exporter is created directly, so the endpoint does not
     * depend on service discovery in the image's class path. Its shutdown
     * hook flushes the files when the process exits.
     *
     * @param directory the directory, or null or empty for none
     * @return the configuration, or null if there is no directory
     */
    static TelemetryConfig qlogTelemetry(String directory) {
        if (directory == null || directory.isEmpty()) {
            return null;
        }
        Path path = Path.of(directory);
        TelemetryConfig telemetry = new TelemetryConfig();
        telemetry.exporter(new QlogExporter(path, QlogExporter.DEFAULT_QUEUE_SIZE));
        return telemetry;
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
