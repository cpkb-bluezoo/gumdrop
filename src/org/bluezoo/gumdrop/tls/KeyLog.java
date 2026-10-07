/*
 * KeyLog.java
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

package org.bluezoo.gumdrop.tls;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Receives the secrets of each TLS connection as they are derived, in the
 * terms of the NSS key log format that Wireshark and other analysers read
 * ({@code <label> <client random> <secret>}, hex, one line each). TLS 1.3
 * (TCP, DTLS and QUIC alike) reports the handshake, application and
 * exporter secrets, {@code CLIENT_EARLY_TRAFFIC_SECRET} for 0-RTT, and
 * every key update as {@code CLIENT_TRAFFIC_SECRET_n} or
 * {@code SERVER_TRAFFIC_SECRET_n}; TLS 1.2 reports {@code CLIENT_RANDOM}
 * with the master secret.
 *
 * <p>Logging is off unless a connection's handshake configuration names
 * a key log or a process-wide {@linkplain #setDefault default} is set.
 * {@link #fromEnvironment} opens the file named by the
 * {@code SSLKEYLOGFILE} variable, the convention browsers and command
 * line clients follow, but only when the application asks: a key log
 * exposes every session's traffic to whoever can read the file, so no
 * listener or client enables one on its own.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class KeyLog {

    public static final String CLIENT_EARLY_TRAFFIC_SECRET = "CLIENT_EARLY_TRAFFIC_SECRET";
    public static final String CLIENT_HANDSHAKE_TRAFFIC_SECRET = "CLIENT_HANDSHAKE_TRAFFIC_SECRET";
    public static final String SERVER_HANDSHAKE_TRAFFIC_SECRET = "SERVER_HANDSHAKE_TRAFFIC_SECRET";
    public static final String EXPORTER_SECRET = "EXPORTER_SECRET";
    /** TLS 1.2: the secret is the 48-byte master secret. */
    public static final String CLIENT_RANDOM = "CLIENT_RANDOM";

    /** The environment variable {@link #fromEnvironment} reads. */
    public static final String ENVIRONMENT_VARIABLE = "SSLKEYLOGFILE";

    private static volatile KeyLog defaultKeyLog;

    /**
     * The label of the client's application traffic secret of the given
     * generation: 0 for the handshake's, then one more per key update.
     */
    public static String clientTrafficSecret(int generation) {
        return "CLIENT_TRAFFIC_SECRET_" + generation;
    }

    /**
     * The label of the server's application traffic secret of the given
     * generation.
     */
    public static String serverTrafficSecret(int generation) {
        return "SERVER_TRAFFIC_SECRET_" + generation;
    }

    /**
     * Records one secret.
     *
     * @param label the NSS key log label, one of the constants of this
     *        class or a {@link #clientTrafficSecret}/{@link #serverTrafficSecret}
     * @param clientRandom the 32-byte random of the ClientHello on the
     *        wire (the outer one when Encrypted Client Hello is used),
     *        which is how a trace is matched to its secrets
     * @param secret the secret
     */
    public abstract void log(String label, byte[] clientRandom, byte[] secret);

    /**
     * A key log appending NSS format lines to a file, flushed after every
     * line so that a trace can be decrypted while the process still runs.
     * Safe to share between connections and threads.
     *
     * @param path the file; created if absent, appended to otherwise
     */
    public static KeyLog file(Path path) throws IOException {
        return new FileKeyLog(path);
    }

    /**
     * Opens the file named by {@code SSLKEYLOGFILE}.
     *
     * @return the key log, or null if the variable is not set
     */
    public static KeyLog fromEnvironment() throws IOException {
        String path = System.getenv(ENVIRONMENT_VARIABLE);
        if (path == null || path.length() == 0) {
            return null;
        }
        return file(Path.of(path));
    }

    /**
     * Sets the key log used by every handshake configuration that does
     * not name one of its own, or clears it with null.
     */
    public static void setDefault(KeyLog keyLog) {
        defaultKeyLog = keyLog;
    }

    /**
     * Returns the process-wide default key log, or null.
     */
    public static KeyLog getDefault() {
        return defaultKeyLog;
    }

    private static final class FileKeyLog extends KeyLog {

        private static final char[] HEX = "0123456789abcdef".toCharArray();

        private final Path path;
        private final Writer writer;

        FileKeyLog(Path path) throws IOException {
            this.path = path;
            this.writer = Files.newBufferedWriter(path, StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        @Override
        public synchronized void log(String label, byte[] clientRandom, byte[] secret) {
            StringBuilder line = new StringBuilder(label.length() + 2 * (clientRandom.length + secret.length) + 3);
            line.append(label).append(' ');
            appendHex(line, clientRandom);
            line.append(' ');
            appendHex(line, secret);
            line.append('\n');
            try {
                writer.write(line.toString());
                writer.flush();
            } catch (IOException e) {
                // A secret that cannot be recorded is not worth failing the
                // connection over; the analyser simply will not have it.
                java.util.logging.Logger.getLogger(KeyLog.class.getName())
                        .warning("cannot write to key log " + path + ": " + e.getMessage());
            }
        }

        private static void appendHex(StringBuilder sb, byte[] bytes) {
            for (int i = 0; i < bytes.length; i++) {
                sb.append(HEX[(bytes[i] >> 4) & 0xF]).append(HEX[bytes[i] & 0xF]);
            }
        }

    }

}
