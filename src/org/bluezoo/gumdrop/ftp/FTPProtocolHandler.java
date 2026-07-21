/*
 * FTPProtocolHandler.java
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

package org.bluezoo.gumdrop.ftp;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.security.cert.Certificate;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.ByteStreamLexer;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.TokenErrorRecovery;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SASLUtils;
import org.bluezoo.gumdrop.quota.Quota;
import org.bluezoo.gumdrop.quota.QuotaManager;
import org.bluezoo.gumdrop.quota.QuotaPolicy;
import org.bluezoo.gumdrop.quota.QuotaSource;
import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * FTP protocol handler using {@link ProtocolHandler}.
 *
 * <p>Implements the FTP protocol with the transport layer fully decoupled:
 * <ul>
 * <li>Transport operations delegate to an {@link Endpoint} reference
 *     received in {@link #connected(Endpoint)}</li>
 * <li>Line parsing uses a streaming {@link FTPServerLexer} (issue #85):
 *     bytes are tokenised as they arrive rather than buffered into whole
 *     lines — see {@link ByteStreamLexer}</li>
 * <li>TLS upgrade uses {@link Endpoint#startTLS()}</li>
 * <li>Security info uses {@link Endpoint#getSecurityInfo()}</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtocolHandler
 * @see FTPServerLexer
 * @see FTPListener
 * @see https://www.rfc-editor.org/rfc/rfc959
 */
public class FTPProtocolHandler
        implements ProtocolHandler, ByteStreamLexer.Handler<FTPServerLexer.Token>,
                   FTPControlConnection {

    private static final Logger LOGGER =
            Logger.getLogger(FTPProtocolHandler.class.getName());
    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.ftp.L10N");

    static final Charset US_ASCII = Charset.forName("US-ASCII");
    static final CharsetDecoder US_ASCII_DECODER = US_ASCII.newDecoder();

    private static final int MAX_LINE_LENGTH = 1024;

    // RFC 959/2228/2428/3659/etc. — recognised command verbs. Resolved
    // once, directly from the KEYWORD token's bytes (issue #85), rather
    // than buffered as a String and re-compared later.
    private enum FTPCommand {
        USER, PASS, ACCT, CWD, CDUP, SMNT, REIN, QUIT,
        PORT, PASV, EPRT, EPSV, TYPE, STRU, MODE,
        RETR, STOR, STOU, APPE, ALLO, REST, RNFR, RNTO, ABOR, DELE,
        RMD, MKD, PWD, LIST, NLST, SITE, SYST, STAT, HELP, NOOP,
        AUTH, PBSZ, PROT, CCC, SIZE, MDTM, MLST, MLSD, OPTS, FEAT,
        UNKNOWN
    }

    private Endpoint endpoint;

    private final FTPListener server;
    private final FTPConnectionHandler handler;
    private final FTPConnectionMetadata metadata;
    private final FTPDataConnectionCoordinator dataCoordinator;

    private String user;
    private String password;
    private String account;
    private String renameFrom;
    private String currentDirectory = "/";
    private boolean authenticated = false;
    private long restartOffset = 0;
    private boolean epsvAllMode = false;
    private boolean pbszSet = false;
    private boolean dataProtection = false;
    private boolean utf8Enabled = false;

    // Streaming lexer (issue #85) and per-line parse state
    private final FTPServerLexer lexer;
    private final TokenErrorRecovery<FTPServerLexer.Token> lexerRecovery =
            new TokenErrorRecovery<FTPServerLexer.Token>(FTPServerLexer.Token.CRLF);
    private FTPCommand pendingCommand = FTPCommand.UNKNOWN;
    private String pendingUnknownText = "";
    private boolean pendingHasSp;
    private final StringBuilder argsBuilder = new StringBuilder();
    private int lineByteCount;
    private String lineErrorMessage;

    private Trace connectionTrace = null;
    private Span sessionSpan = null;
    private Span authenticatedSpan = null;

    /**
     * Creates a new FTP endpoint handler.
     *
     * @param server the FTP server configuration
     * @param handler the connection handler for authentication and file operations
     */
    public FTPProtocolHandler(FTPListener server, FTPConnectionHandler handler) {
        this.server = server;
        this.handler = handler;

        FTPConnectionMetadata tempMetadata;
        try {
            InetSocketAddress clientAddr = null;
            InetSocketAddress serverAddr = null;

            Certificate[] certificates = null;
            String cipherSuite = null;
            String protocol = null;

            tempMetadata = new FTPConnectionMetadata(
                clientAddr,
                serverAddr,
                false,
                certificates,
                cipherSuite,
                protocol,
                System.currentTimeMillis(),
                "ftp"
            );
        } catch (Exception e) {
            tempMetadata = new FTPConnectionMetadata(
                null, null, false, null, null, null,
                System.currentTimeMillis(), "ftp"
            );
        }
        this.metadata = tempMetadata;
        this.dataCoordinator = new FTPDataConnectionCoordinator(this);
        ByteStreamLexer.checkTokenCap(MAX_LINE_LENGTH, server.getMaxNetInSize());
        this.lexer = new FTPServerLexer(this, MAX_LINE_LENGTH);
    }

    // ── ProtocolHandler implementation ──

    @Override
    public void connected(Endpoint ep) {
        this.endpoint = ep;

        if (endpoint.getRemoteAddress() != null) {
            InetSocketAddress clientAddr = (InetSocketAddress) endpoint.getRemoteAddress();
            metadata.setClientAddress(clientAddr);
            // RFC 4217 section 10: store for data connection IP verification
            dataCoordinator.setControlClientAddress(clientAddr.getAddress());
        }
        if (endpoint.getLocalAddress() != null) {
            metadata.setServerAddress((InetSocketAddress) endpoint.getLocalAddress());
        }
        metadata.setSecureConnection(endpoint.isSecure());
        if (endpoint.isSecure()) {
            SecurityInfo info = endpoint.getSecurityInfo();
            if (info != null) {
                metadata.setClientCertificates(info.getPeerCertificates());
                metadata.setCipherSuite(info.getCipherSuite());
                metadata.setProtocolVersion(info.getProtocol());
            }
        }

        initConnectionTrace();
        startSessionSpan();

        try {
            if (handler != null) {
                String customBanner = handler.connected(metadata);
                if (customBanner != null && !customBanner.isEmpty()) {
                    reply(220, customBanner);
                } else {
                    reply(220, L10N.getString("ftp.welcome_banner").substring(4));
                }
            } else {
                reply(220, L10N.getString("ftp.welcome_banner").substring(4));
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send welcome banner", e);
            endpoint.close();
        }
    }

    @Override
    public void receive(ByteBuffer data) {
        lexer.feed(data);
    }

    @Override
    public void disconnected() {
        try {
            endAuthenticatedSpan();
            if (sessionSpan != null && !sessionSpan.isEnded()) {
                endSessionSpanError("Connection lost");
            }
        } finally {
            try {
                if (dataCoordinator != null) {
                    dataCoordinator.cleanup();
                }
            } finally {
                if (handler != null) {
                    handler.disconnected(metadata);
                }
            }
        }
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        metadata.setSecureConnection(true);
        if (info != null) {
            metadata.setClientCertificates(info.getPeerCertificates());
            metadata.setCipherSuite(info.getCipherSuite());
            metadata.setProtocolVersion(info.getProtocol());
        }

        if (!authenticated && endpoint != null) {
            Realm realm = server.getRealm();
            if (realm != null) {
                Realm.CertificateAuthenticationResult result =
                        SASLUtils.authenticateExternal(
                                endpoint, realm, null);
                if (result != null && result.valid) {
                    user = result.username;
                    authenticated = true;
                    metadata.setAuthenticated(true);
                    metadata.setAuthenticatedUser(user);
                    recordAuthenticationSuccess("CERT");
                    try {
                        reply(232,
                            L10N.getString("ftp.login_successful"));
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING,
                                "Failed to send cert auto-login reply",
                                e);
                    }
                }
            }
        }
    }

    @Override
    public void error(Exception cause) {
        LOGGER.log(Level.WARNING, "FTP transport error", cause);
        if (endpoint != null) {
            endpoint.close();
        }
    }

    // ── ByteStreamLexer.Handler implementation (issue #85) ──

    // RFC 959 section 4: KEYWORD [SP TEXT] CRLF. TEXT is delivered in
    // zero-copy chunks by the lexer (see FTPServerLexer / ByteStreamLexer);
    // this dispatcher accumulates only what it needs to retain (the args
    // string, which may itself contain spaces, e.g. pathnames) and
    // enforces the combined line-length budget itself, since free-form
    // text is intentionally exempt from the lexer's own cap.
    @Override
    public boolean token(FTPServerLexer.Token type, ByteBuffer window) {
        if (lexerRecovery.handleToken(type)) {
            // Discarding the remainder of a line already rejected by
            // tokenTooLong(); the error reply was already sent there.
            return false;
        }
        switch (type) {
            case KEYWORD:
                lineByteCount = window.remaining();
                if (lineErrorMessage == null) {
                    // Resolve the command directly from the token's bytes
                    // now, once, rather than buffering a string to
                    // re-compare at CRLF time. A string is decoded only
                    // for the (rare) unrecognised-verb case, where the
                    // exact text is needed for the error reply.
                    pendingCommand = matchCommand(window);
                    if (pendingCommand == FTPCommand.UNKNOWN) {
                        try {
                            pendingUnknownText = decodeAscii(window).toUpperCase();
                        } catch (CharacterCodingException e) {
                            lineErrorMessage = L10N.getString("ftp.err.illegal_characters");
                        }
                    }
                }
                return false;
            case SP:
                pendingHasSp = true;
                lineByteCount += 1;
                return true; // latch text mode for the rest of the line
            case TEXT:
                if (lineErrorMessage == null) {
                    int len = window.remaining();
                    if (lineByteCount + len > MAX_LINE_LENGTH) {
                        lineErrorMessage = L10N.getString("ftp.err.line_too_long");
                    } else {
                        try {
                            argsBuilder.append(decodeAscii(window));
                            lineByteCount += len;
                        } catch (CharacterCodingException e) {
                            lineErrorMessage = L10N.getString("ftp.err.illegal_characters");
                        }
                    }
                }
                return false;
            case CRLF:
                dispatchLine();
                return false;
            default:
                return false;
        }
    }

    @Override
    public void rawBytes(ByteBuffer slice) {
        // FTP's control channel is always line-based (data transfers use a
        // separate connection, handled entirely outside this lexer), so
        // this is structurally unreachable.
        LOGGER.warning("Unexpected rawBytes() call on FTP server lexer");
    }

    @Override
    public void tokenTooLong() {
        lexerRecovery.beginDiscard();
        resetLineState();
        try {
            reply(500, L10N.getString("ftp.err.line_too_long"));
            if (endpoint != null && endpoint.getRemoteAddress() != null) {
                LOGGER.warning("FTP command line too long from "
                        + endpoint.getRemoteAddress());
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error sending line-too-long reply", e);
        }
    }

    private static String decodeAscii(ByteBuffer window) throws CharacterCodingException {
        return US_ASCII_DECODER.decode(window).toString();
    }

    private void resetLineState() {
        pendingCommand = FTPCommand.UNKNOWN;
        pendingUnknownText = "";
        pendingHasSp = false;
        argsBuilder.setLength(0);
        lineByteCount = 0;
        lineErrorMessage = null;
    }

    /**
     * Matches a KEYWORD token's raw bytes against the known FTP verbs
     * (RFC 959 and extensions), case-insensitively, without decoding to a
     * String — the command is resolved once, here, rather than deferred
     * to a string comparison at dispatch time. Each byte is read exactly
     * once and case-folded while packing into a single {@code int};
     * dispatch is then one {@code switch} on that packed value rather
     * than a chain of comparisons. Length-3 and length-4 verbs are packed
     * and switched separately (not padded into a shared int) so a literal
     * NUL byte in a 4-byte token can never collide with a padded 3-byte
     * verb's packed value.
     *
     * @param window the KEYWORD token's bytes
     * @return the matched command, or {@link FTPCommand#UNKNOWN}
     */
    private static FTPCommand matchCommand(ByteBuffer window) {
        int len = window.remaining();
        int base = window.position();
        if (len == 4) {
            switch (pack4(window, base)) {
                case ('U' << 24) | ('S' << 16) | ('E' << 8) | 'R':
                    return FTPCommand.USER;
                case ('P' << 24) | ('A' << 16) | ('S' << 8) | 'S':
                    return FTPCommand.PASS;
                case ('A' << 24) | ('C' << 16) | ('C' << 8) | 'T':
                    return FTPCommand.ACCT;
                case ('C' << 24) | ('D' << 16) | ('U' << 8) | 'P':
                    return FTPCommand.CDUP;
                case ('S' << 24) | ('M' << 16) | ('N' << 8) | 'T':
                    return FTPCommand.SMNT;
                case ('R' << 24) | ('E' << 16) | ('I' << 8) | 'N':
                    return FTPCommand.REIN;
                case ('Q' << 24) | ('U' << 16) | ('I' << 8) | 'T':
                    return FTPCommand.QUIT;
                case ('P' << 24) | ('O' << 16) | ('R' << 8) | 'T':
                    return FTPCommand.PORT;
                case ('P' << 24) | ('A' << 16) | ('S' << 8) | 'V':
                    return FTPCommand.PASV;
                case ('E' << 24) | ('P' << 16) | ('R' << 8) | 'T':
                    return FTPCommand.EPRT;
                case ('E' << 24) | ('P' << 16) | ('S' << 8) | 'V':
                    return FTPCommand.EPSV;
                case ('T' << 24) | ('Y' << 16) | ('P' << 8) | 'E':
                    return FTPCommand.TYPE;
                case ('S' << 24) | ('T' << 16) | ('R' << 8) | 'U':
                    return FTPCommand.STRU;
                case ('M' << 24) | ('O' << 16) | ('D' << 8) | 'E':
                    return FTPCommand.MODE;
                case ('R' << 24) | ('E' << 16) | ('T' << 8) | 'R':
                    return FTPCommand.RETR;
                case ('S' << 24) | ('T' << 16) | ('O' << 8) | 'R':
                    return FTPCommand.STOR;
                case ('S' << 24) | ('T' << 16) | ('O' << 8) | 'U':
                    return FTPCommand.STOU;
                case ('A' << 24) | ('P' << 16) | ('P' << 8) | 'E':
                    return FTPCommand.APPE;
                case ('A' << 24) | ('L' << 16) | ('L' << 8) | 'O':
                    return FTPCommand.ALLO;
                case ('R' << 24) | ('E' << 16) | ('S' << 8) | 'T':
                    return FTPCommand.REST;
                case ('R' << 24) | ('N' << 16) | ('F' << 8) | 'R':
                    return FTPCommand.RNFR;
                case ('R' << 24) | ('N' << 16) | ('T' << 8) | 'O':
                    return FTPCommand.RNTO;
                case ('A' << 24) | ('B' << 16) | ('O' << 8) | 'R':
                    return FTPCommand.ABOR;
                case ('D' << 24) | ('E' << 16) | ('L' << 8) | 'E':
                    return FTPCommand.DELE;
                case ('L' << 24) | ('I' << 16) | ('S' << 8) | 'T':
                    return FTPCommand.LIST;
                case ('N' << 24) | ('L' << 16) | ('S' << 8) | 'T':
                    return FTPCommand.NLST;
                case ('S' << 24) | ('I' << 16) | ('T' << 8) | 'E':
                    return FTPCommand.SITE;
                case ('S' << 24) | ('Y' << 16) | ('S' << 8) | 'T':
                    return FTPCommand.SYST;
                case ('S' << 24) | ('T' << 16) | ('A' << 8) | 'T':
                    return FTPCommand.STAT;
                case ('H' << 24) | ('E' << 16) | ('L' << 8) | 'P':
                    return FTPCommand.HELP;
                case ('N' << 24) | ('O' << 16) | ('O' << 8) | 'P':
                    return FTPCommand.NOOP;
                case ('A' << 24) | ('U' << 16) | ('T' << 8) | 'H':
                    return FTPCommand.AUTH;
                case ('P' << 24) | ('B' << 16) | ('S' << 8) | 'Z':
                    return FTPCommand.PBSZ;
                case ('P' << 24) | ('R' << 16) | ('O' << 8) | 'T':
                    return FTPCommand.PROT;
                case ('S' << 24) | ('I' << 16) | ('Z' << 8) | 'E':
                    return FTPCommand.SIZE;
                case ('M' << 24) | ('D' << 16) | ('T' << 8) | 'M':
                    return FTPCommand.MDTM;
                case ('M' << 24) | ('L' << 16) | ('S' << 8) | 'T':
                    return FTPCommand.MLST;
                case ('M' << 24) | ('L' << 16) | ('S' << 8) | 'D':
                    return FTPCommand.MLSD;
                case ('O' << 24) | ('P' << 16) | ('T' << 8) | 'S':
                    return FTPCommand.OPTS;
                case ('F' << 24) | ('E' << 16) | ('A' << 8) | 'T':
                    return FTPCommand.FEAT;
                default:
                    return FTPCommand.UNKNOWN;
            }
        }
        if (len == 3) {
            switch (pack3(window, base)) {
                case ('C' << 16) | ('W' << 8) | 'D':
                    return FTPCommand.CWD;
                case ('R' << 16) | ('M' << 8) | 'D':
                    return FTPCommand.RMD;
                case ('M' << 16) | ('K' << 8) | 'D':
                    return FTPCommand.MKD;
                case ('P' << 16) | ('W' << 8) | 'D':
                    return FTPCommand.PWD;
                case ('C' << 16) | ('C' << 8) | 'C':
                    return FTPCommand.CCC;
                default:
                    return FTPCommand.UNKNOWN;
            }
        }
        return FTPCommand.UNKNOWN;
    }

    private static int pack4(ByteBuffer window, int base) {
        int packed = 0;
        for (int i = 0; i < 4; i++) {
            packed = (packed << 8) | upperFold(window.get(base + i));
        }
        return packed;
    }

    private static int pack3(ByteBuffer window, int base) {
        int packed = 0;
        for (int i = 0; i < 3; i++) {
            packed = (packed << 8) | upperFold(window.get(base + i));
        }
        return packed;
    }

    private static int upperFold(byte b) {
        if (b >= 'a' && b <= 'z') {
            b -= 32;
        }
        return b & 0xFF;
    }


    // RFC 959 section 4 — a complete command line has been lexed; dispatch
    // it exactly as the pre-streaming lineRead(String) did.
    private void dispatchLine() {
        FTPCommand command = pendingCommand;
        String unknownText = pendingUnknownText;
        String args = pendingHasSp ? argsBuilder.toString() : null;
        String error = lineErrorMessage;
        resetLineState();

        try {
            if (error != null) {
                reply(500, error);
                return;
            }
            dispatchCommand(command, unknownText, args);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error processing FTP command", e);
            try {
                reply(500, L10N.getString("ftp.err.illegal_characters"));
            } catch (IOException e2) {
                LOGGER.log(Level.SEVERE, "Cannot write error reply", e2);
            }
        }
    }

    // ── FTPControlConnection implementation ──

    @Override
    public FTPListener getServer() {
        return server;
    }

    // ── Transport helpers ──

    private void send(ByteBuffer buffer) throws IOException {
        if (endpoint != null) {
            endpoint.send(buffer);
        }
    }

    private void closeEndpoint() {
        if (endpoint != null) {
            endpoint.close();
        }
    }

    private String getRemoteSocketAddress() {
        if (endpoint != null && endpoint.getRemoteAddress() != null) {
            return endpoint.getRemoteAddress().toString();
        }
        return "unknown";
    }

    // ── Protocol helpers ──

    private FTPFileSystem getFileSystem() {
        if (handler != null && authenticated) {
            return handler.getFileSystem(metadata);
        }
        return null;
    }

    /**
     * Runs a blocking storage operation off the SelectorLoop and delivers
     * the outcome back on this control connection's loop thread.
     *
     * <p>Filesystem metadata ({@code stat}/{@code readdir}/{@code open}/…)
     * and {@link java.nio.channels.AsynchronousFileChannel#open} must use
     * this helper — never run them directly on the loop.
     *
     * @param op the blocking work
     * @param callback outcome callback (invoked on the loop)
     */
    private <T> void submitStorage(final Callable<T> op,
            final StorageExecutor.Callback<T> callback) {
        org.bluezoo.gumdrop.Gumdrop gumdrop =
                org.bluezoo.gumdrop.Gumdrop.getInstance();
        StorageExecutor exec =
                (gumdrop != null) ? gumdrop.getStorageExecutor() : null;
        if (exec == null || endpoint == null) {
            T result;
            try {
                result = op.call();
            } catch (Throwable t) {
                callback.failed(t);
                return;
            }
            callback.completed(result);
            return;
        }
        endpoint.pauseRead();
        exec.submit(endpoint, op,
                new StorageExecutor.Callback<T>() {
            @Override
            public void completed(T result) {
                endpoint.resumeRead();
                callback.completed(result);
            }

            @Override
            public void failed(Throwable error) {
                endpoint.resumeRead();
                callback.failed(error);
            }
        });
    }

    /**
     * Sends an FTP reply, swallowing I/O errors. Used from storage-offload
     * callbacks whose continuations cannot propagate a checked
     * {@link IOException}.
     */
    private void replyQuietly(int code, String description) {
        try {
            reply(code, description);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send FTP reply " + code, e);
        }
    }

    /**
     * As {@link #handleFileOperationResult} but swallows I/O errors for use
     * from storage-offload callbacks.
     */
    private void handleFileOperationResultQuietly(FTPFileOperationResult result,
            String path) {
        try {
            handleFileOperationResult(result, path);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send file operation reply", e);
        }
    }

    private void sendLineQuietly(String line) {
        try {
            sendLine(line);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send FTP line", e);
        }
    }

    private boolean checkAuthorization(FTPOperation operation, String path) throws IOException {
        if (handler != null && !handler.isAuthorized(operation, path, metadata)) {
            reply(550, L10N.getString("ftp.err.permission_denied"));
            return false;
        }
        return true;
    }

    private void handleAuthenticationResult(FTPAuthenticationResult result) throws IOException {
        switch (result) {
            case SUCCESS:
                authenticated = true;
                metadata.setAuthenticated(true);
                metadata.setAuthenticatedUser(user);
                recordAuthenticationSuccess("USER/PASS");
                reply(230, L10N.getString("ftp.login_successful"));
                break;
            case NEED_PASSWORD:
                reply(331, L10N.getString("ftp.user_ok_need_password"));
                break;
            case NEED_ACCOUNT:
                reply(332, L10N.getString("ftp.user_ok_need_account"));
                break;
            case INVALID_USER:
                recordAuthenticationFailure("USER/PASS", user);
                reply(530, L10N.getString("ftp.err.invalid_username"));
                break;
            case INVALID_PASSWORD:
                recordAuthenticationFailure("USER/PASS", user);
                reply(530, L10N.getString("ftp.err.invalid_password"));
                break;
            case INVALID_ACCOUNT:
                recordAuthenticationFailure("USER/PASS", user);
                reply(530, L10N.getString("ftp.err.invalid_password"));
                break;
            case ACCOUNT_DISABLED:
                recordAuthenticationFailure("USER/PASS", user);
                reply(530, L10N.getString("ftp.err.account_disabled"));
                break;
            case TOO_MANY_ATTEMPTS:
                recordAuthenticationFailure("USER/PASS", user);
                reply(421, L10N.getString("ftp.err.too_many_attempts"));
                break;
            case ANONYMOUS_NOT_ALLOWED:
                recordAuthenticationFailure("ANONYMOUS", user);
                reply(530, L10N.getString("ftp.err.anonymous_not_allowed"));
                break;
            case USER_LIMIT_EXCEEDED:
                recordAuthenticationFailure("USER/PASS", user);
                reply(421, L10N.getString("ftp.err.user_limit_exceeded"));
                break;
        }
    }

    private void handleFileOperationResult(FTPFileOperationResult result, String path) throws IOException {
        switch (result) {
            case SUCCESS:
                reply(250, L10N.getString("ftp.file_action_complete"));
                break;
            case TRANSFER_STARTING:
                reply(150, L10N.getString("ftp.transfer_starting"));
                break;
            case NOT_FOUND:
                String notFoundMsg = L10N.getString("ftp.err.file_not_found");
                reply(550, MessageFormat.format(notFoundMsg, path));
                break;
            case ACCESS_DENIED:
                String accessDeniedMsg = L10N.getString("ftp.err.access_denied");
                reply(550, MessageFormat.format(accessDeniedMsg, path));
                break;
            case ALREADY_EXISTS:
                String existsMsg = L10N.getString("ftp.err.file_already_exists");
                reply(550, MessageFormat.format(existsMsg, path));
                break;
            case DIRECTORY_NOT_EMPTY:
                String notEmptyMsg = L10N.getString("ftp.err.directory_not_empty");
                reply(550, MessageFormat.format(notEmptyMsg, path));
                break;
            case INSUFFICIENT_SPACE:
                reply(552, L10N.getString("ftp.err.insufficient_space"));
                break;
            case INVALID_NAME:
                String invalidNameMsg = L10N.getString("ftp.err.invalid_file_name");
                reply(553, MessageFormat.format(invalidNameMsg, path));
                break;
            case FILE_SYSTEM_ERROR:
                reply(550, L10N.getString("ftp.err.file_system_error"));
                break;
            case NOT_SUPPORTED:
                reply(502, L10N.getString("ftp.err.operation_not_supported"));
                break;
            case FILE_LOCKED:
                String lockedMsg = L10N.getString("ftp.err.file_locked");
                reply(550, MessageFormat.format(lockedMsg, path));
                break;
            case IS_DIRECTORY:
                String isDirMsg = L10N.getString("ftp.err.is_directory");
                reply(550, MessageFormat.format(isDirMsg, path));
                break;
            case IS_FILE:
                String isFileMsg = L10N.getString("ftp.err.is_file");
                reply(550, MessageFormat.format(isFileMsg, path));
                break;
            case QUOTA_EXCEEDED:
                reply(552, L10N.getString("ftp.err.quota_exceeded"));
                break;
            case RENAME_PENDING:
                reply(350, L10N.getString("ftp.rename_pending"));
                break;
        }
    }

    // RFC 959 section 5.4: "The command codes themselves are not case
    // sensitive."  This dispatch uses exact-match which rejects lower-case
    // commands — a compliance gap.
    // RFC 959 section 4 — dispatch by command. `command` was already
    // resolved from the KEYWORD token's raw bytes (see matchCommand());
    // this is a direct switch on the enum, not a re-parse of a string.
    // Unlike POP3, no FTP command is gated by connection state — each
    // doXxx method checks `authenticated` itself where required — so
    // there is no nested state switch here.
    private void dispatchCommand(FTPCommand command, String unknownText, String args)
            throws IOException {
        switch (command) {
            case USER:
                doUser(args);
                break;
            case PASS:
                doPass(args);
                break;
            case ACCT:
                doAcct(args);
                break;
            case CWD:
                doCwd(args);
                break;
            case CDUP:
                doCdup(args);
                break;
            case SMNT:
                doSmnt(args);
                break;
            case REIN:
                doRein(args);
                break;
            case QUIT:
                doQuit(args);
                break;
            case PORT:
                doPort(args);
                break;
            case PASV:
                doPasv(args);
                break;
            case EPRT:
                doEprt(args);
                break;
            case EPSV:
                doEpsv(args);
                break;
            case TYPE:
                doType(args);
                break;
            case STRU:
                doStru(args);
                break;
            case MODE:
                doMode(args);
                break;
            case RETR:
                doRetr(args);
                break;
            case STOR:
                doStor(args);
                break;
            case STOU:
                doStou(args);
                break;
            case APPE:
                doAppe(args);
                break;
            case ALLO:
                doAllo(args);
                break;
            case REST:
                doRest(args);
                break;
            case RNFR:
                doRnfr(args);
                break;
            case RNTO:
                doRnto(args);
                break;
            case ABOR:
                doAbor(args);
                break;
            case DELE:
                doDele(args);
                break;
            case RMD:
                doRmd(args);
                break;
            case MKD:
                doMkd(args);
                break;
            case PWD:
                doPwd(args);
                break;
            case LIST:
                doList(args);
                break;
            case NLST:
                doNlst(args);
                break;
            case SITE:
                doSite(args);
                break;
            case SYST:
                doSyst(args);
                break;
            case STAT:
                doStat(args);
                break;
            case HELP:
                doHelp(args);
                break;
            case NOOP:
                doNoop(args);
                break;
            case AUTH:
                doAuth(args);
                break;
            case PBSZ:
                doPbsz(args);
                break;
            case PROT:
                doProt(args);
                break;
            case CCC:
                doCcc(args);
                break;
            case SIZE:
                doSize(args);
                break;
            case MDTM:
                doMdtm(args);
                break;
            case MLST:
                doMlst(args);
                break;
            case MLSD:
                doMlsd(args);
                break;
            case OPTS:
                doOpts(args);
                break;
            case FEAT:
                doFeat(args);
                break;
            default:
                String message = L10N.getString("ftp.err.command_unrecognized");
                reply(500, MessageFormat.format(message, unknownText));
        }
    }

    // RFC 959 section 4.2: reply format is "<code> <text>\r\n" for
    // single-line replies.  Multi-line uses "<code>-<text>\r\n" for
    // intermediate lines and "<code> <text>\r\n" for the final line.
    private void reply(int code, String description) throws IOException {
        String message = String.format("%d %s\r\n", code, description);
        // RFC 2640: use UTF-8 encoding when OPTS UTF8 ON has been issued
        String encoding = utf8Enabled ? "UTF-8" : "US-ASCII";
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(encoding));
        send(buffer);
    }

    private void sendLine(String line) throws IOException {
        String message = line + "\r\n";
        String encoding = utf8Enabled ? "UTF-8" : "US-ASCII";
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(encoding));
        send(buffer);
    }

    // ── RFC 959 section 4.1.1 — ACCESS CONTROL COMMANDS ──

    // RFC 959 section 4.1.1: USER <SP> <username> <CRLF>
    private void doUser(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        user = args.trim();
        password = null;
        account = null;
        authenticated = false;

        if (handler != null) {
            FTPAuthenticationResult result = handler.authenticate(user, null, null, metadata);
            handleAuthenticationResult(result);
        } else {
            reply(331, L10N.getString("ftp.user_ok_need_password"));
        }
    }

    // RFC 959 section 4.1.1: PASS <SP> <password> <CRLF>
    private void doPass(String args) throws IOException {
        if (user == null) {
            reply(503, L10N.getString("ftp.err.bad_sequence"));
            return;
        }

        password = args;

        if (handler != null) {
            FTPAuthenticationResult result = handler.authenticate(user, password, account, metadata);
            handleAuthenticationResult(result);
        } else {
            reply(530, L10N.getString("ftp.err.invalid_password"));
        }
    }

    // RFC 959 section 4.1.1: ACCT <SP> <account-information> <CRLF>
    private void doAcct(String args) throws IOException {
        if (user == null) {
            reply(503, L10N.getString("ftp.err.bad_sequence"));
            return;
        }

        account = args;

        if (handler != null) {
            FTPAuthenticationResult result = handler.authenticate(user, password, account, metadata);
            handleAuthenticationResult(result);
        } else {
            reply(202, L10N.getString("ftp.command_ok"));
        }
    }

    // RFC 959 section 4.1.1: CWD <SP> <pathname> <CRLF>
    private void doCwd(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        final String targetPath = args.trim();

        if (!checkAuthorization(FTPOperation.NAVIGATE, targetPath)) {
            return;
        }

        final FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }
        final String cwd = currentDirectory;
        submitStorage(new Callable<FTPFileSystem.DirectoryChangeResult>() {
            @Override
            public FTPFileSystem.DirectoryChangeResult call() {
                return fs.changeDirectory(targetPath, cwd, metadata);
            }
        }, new StorageExecutor.Callback<FTPFileSystem.DirectoryChangeResult>() {
            @Override
            public void completed(FTPFileSystem.DirectoryChangeResult result) {
                if (result.getResult() == FTPFileOperationResult.SUCCESS) {
                    currentDirectory = result.getNewDirectory();
                    metadata.setCurrentDirectory(currentDirectory);
                    replyQuietly(250, L10N.getString("ftp.directory_changed"));
                } else {
                    handleFileOperationResultQuietly(
                            result.getResult(), targetPath);
                }
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.WARNING, "CWD failed for " + targetPath, error);
                replyQuietly(550, L10N.getString("ftp.err.file_system_error"));
            }
        });
    }

    // RFC 959 section 4.1.1: CDUP <CRLF>
    private void doCdup(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (!checkAuthorization(FTPOperation.NAVIGATE, "..")) {
            return;
        }

        final FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }

        final String cwd = currentDirectory;
        submitStorage(new Callable<FTPFileSystem.DirectoryChangeResult>() {
            @Override
            public FTPFileSystem.DirectoryChangeResult call() {
                return fs.changeDirectory("..", cwd, metadata);
            }
        }, new StorageExecutor.Callback<FTPFileSystem.DirectoryChangeResult>() {
            @Override
            public void completed(FTPFileSystem.DirectoryChangeResult result) {
                if (result.getResult() == FTPFileOperationResult.SUCCESS) {
                    currentDirectory = result.getNewDirectory();
                    metadata.setCurrentDirectory(currentDirectory);
                    replyQuietly(250, L10N.getString("ftp.directory_changed"));
                } else {
                    handleFileOperationResultQuietly(result.getResult(), "..");
                }
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.WARNING, "CDUP failed", error);
                replyQuietly(550, L10N.getString("ftp.err.file_system_error"));
            }
        });
    }

    // RFC 959 section 4.1.1: SMNT <SP> <pathname> <CRLF>
    // Optional command; 502 is appropriate for unimplemented.
    private void doSmnt(String args) throws IOException {
        reply(502, L10N.getString("ftp.err.command_not_implemented"));
    }

    // RFC 959 section 4.1.1: REIN <CRLF>
    // MUST flush the session (user, transfer parameters, representations)
    // and reply 120/220.  Current implementation is incomplete: it resets
    // credentials but does not reset transfer parameters, does not close
    // data connections, and does not send a reply.
    private void doRein(String args) throws IOException {
        user = null;
        password = null;
        account = null;
        authenticated = false;
        metadata.setAuthenticated(false);
        metadata.setAuthenticatedUser(null);
        metadata.setTransferType(FTPConnectionMetadata.FTPTransferType.ASCII);
        metadata.setTransferMode(FTPConnectionMetadata.FTPTransferMode.STREAM);
        currentDirectory = "/";
        metadata.setCurrentDirectory("/");
        restartOffset = 0;
        renameFrom = null;
        dataCoordinator.cleanup();
        reply(220, L10N.getString("ftp.welcome_banner").substring(4));
    }

    // RFC 959 section 4.1.1: QUIT <CRLF>
    private void doQuit(String args) throws IOException {
        addSessionEvent("QUIT");
        reply(221, L10N.getString("ftp.goodbye"));

        user = null;
        password = null;
        account = null;
        authenticated = false;

        endAuthenticatedSpan();
        endSessionSpan();

        closeEndpoint();
    }

    // ── RFC 959 section 4.1.2 — TRANSFER PARAMETER COMMANDS ──

    // RFC 959 section 4.1.2: PORT <SP> <host-port> <CRLF>
    // Format: h1,h2,h3,h4,p1,p2 (IPv4 address and port)
    private void doPort(String args) throws IOException {
        if (epsvAllMode) {
            reply(522, L10N.getString("ftp.err.epsv_all_active"));
            return;
        }

        if (args == null || args.length() < 11) {
            reply(501, L10N.getString("ftp.err.invalid_port_arguments"));
            return;
        }

        String[] fields = new String[6];
        int fieldIndex = 0;
        int start = 0;
        int length = args.length();
        while (start <= length && fieldIndex < 6) {
            int end = args.indexOf(',', start);
            if (end < 0) {
                end = length;
            }
            fields[fieldIndex++] = args.substring(start, end);
            start = end + 1;
        }

        try {
            if (fieldIndex != 6 || start <= length) {
                String message = L10N.getString("ftp.err.invalid_port_arguments");
                reply(501, MessageFormat.format(message, args));
                return;
            }
            StringBuilder hostBuf = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                int f = Integer.parseInt(fields[i]);
                if (f < 0 || f > 255) {
                    String message = L10N.getString("ftp.err.invalid_port_arguments");
                    reply(501, MessageFormat.format(message, args));
                    return;
                }
                if (i > 0) {
                    hostBuf.append('.');
                }
                hostBuf.append(f);
            }
            String clientHost = hostBuf.toString();

            int clientPort = 0;
            for (int i = 0; i < 2; i++) {
                int f = Integer.parseInt(fields[i + 4]);
                if (f < 0 || f > 255) {
                    String message = L10N.getString("ftp.err.invalid_port_arguments");
                    reply(501, MessageFormat.format(message, args));
                    return;
                }
                if (i == 0) {
                    f = f << 8;
                }
                clientPort |= f;
            }

            if (!dataCoordinator.setupActiveMode(clientHost, clientPort)) {
                reply(501, L10N.getString("ftp.err.active_mode_address_mismatch"));
                return;
            }
            reply(200, L10N.getString("ftp.command_ok"));
        } catch (NumberFormatException e) {
            String message = L10N.getString("ftp.err.invalid_port_arguments");
            reply(501, MessageFormat.format(message, args));
        }
    }

    // RFC 959 section 4.1.2: PASV <CRLF>
    private void doPasv(String args) throws IOException {
        if (epsvAllMode) {
            reply(522, L10N.getString("ftp.err.epsv_all_active"));
            return;
        }
        try {
            int requestedPort = 0;
            if (args != null && !args.trim().isEmpty()) {
                requestedPort = Integer.parseInt(args.trim());
            }

            int actualPort = dataCoordinator.setupPassiveMode(requestedPort);

            if (endpoint == null) {
                reply(425, "Cannot establish data connection - no network channel");
                return;
            }
            InetSocketAddress localAddr = (InetSocketAddress) endpoint.getLocalAddress();
            if (localAddr == null || localAddr.getAddress() == null) {
                reply(425, L10N.getString("ftp.err.local_error"));
                return;
            }
            String pasvResponse = dataCoordinator.generatePassiveResponse(localAddr.getAddress());

            reply(227, pasvResponse.substring(4));

        } catch (NumberFormatException e) {
            String message = L10N.getString("ftp.err.invalid_pasv_arguments");
            reply(501, MessageFormat.format(message, args != null ? args : ""));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to set up passive mode", e);
            reply(425, L10N.getString("ftp.err.local_error"));
        }
    }

    // RFC 2428 section 2: EPRT <SP> <d><net-prt><d><net-addr><d><tcp-port><d>
    // Supports both IPv4 (net-prt=1) and IPv6 (net-prt=2).
    // The delimiter <d> is chosen by the client (first char of args).
    private void doEprt(String args) throws IOException {
        if (epsvAllMode) {
            reply(522, L10N.getString("ftp.err.epsv_all_active"));
            return;
        }

        if (args == null || args.length() < 7) {
            reply(501, L10N.getString("ftp.err.invalid_eprt_syntax"));
            return;
        }

        try {
            char delimiter = args.charAt(0);

            List<String> partList = new ArrayList<String>();
            int start = 0;
            for (int i = 0; i <= args.length(); i++) {
                if (i == args.length() || args.charAt(i) == delimiter) {
                    partList.add(args.substring(start, i));
                    start = i + 1;
                }
            }
            String[] parts = partList.toArray(new String[0]);

            if (parts.length < 4) {
                reply(501, L10N.getString("ftp.err.invalid_eprt_syntax"));
                return;
            }

            int protocol = Integer.parseInt(parts[1]);
            String addressStr = parts[2];
            int port = Integer.parseInt(parts[3]);

            if (protocol != 1 && protocol != 2) {
                reply(522, L10N.getString("ftp.err.network_protocol_not_supported"));
                return;
            }

            if (port < 1 || port > 65535) {
                reply(501, L10N.getString("ftp.err.invalid_eprt_port"));
                return;
            }

            InetAddress addr;
            try {
                addr = InetAddress.getByName(addressStr);
            } catch (UnknownHostException e) {
                reply(501, L10N.getString("ftp.err.invalid_eprt_address"));
                return;
            }

            if ((protocol == 1 && !(addr instanceof Inet4Address)) ||
                (protocol == 2 && !(addr instanceof Inet6Address))) {
                reply(522, L10N.getString("ftp.err.network_protocol_mismatch"));
                return;
            }

            if (!dataCoordinator.setupActiveMode(addr.getHostAddress(), port)) {
                reply(501, L10N.getString("ftp.err.active_mode_address_mismatch"));
                return;
            }
            reply(200, L10N.getString("ftp.eprt_ok"));

        } catch (NumberFormatException e) {
            reply(501, L10N.getString("ftp.err.invalid_eprt_syntax"));
        }
    }

    // RFC 2428 section 3: EPSV [<SP> <net-prt> | ALL]
    // Response: 229 Entering Extended Passive Mode (|||<port>|)
    // EPSV ALL tells the server to reject all PORT/PASV/EPRT commands
    // for the remainder of the session (RFC 2428 section 4).
    private void doEpsv(String args) throws IOException {
        try {
            if (args != null && args.trim().equalsIgnoreCase("ALL")) {
                epsvAllMode = true;
                reply(200, L10N.getString("ftp.epsv_all_ok"));
                return;
            }

            if (args != null && !args.trim().isEmpty()) {
                int protocol = Integer.parseInt(args.trim());
                if (protocol != 1 && protocol != 2) {
                    reply(522, L10N.getString("ftp.err.network_protocol_not_supported"));
                    return;
                }
            }

            int actualPort = dataCoordinator.setupPassiveMode(0);

            String response = L10N.getString("ftp.entering_extended_passive");
            reply(229, MessageFormat.format(response, actualPort));

        } catch (NumberFormatException e) {
            reply(501, L10N.getString("ftp.err.invalid_epsv_arguments"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to set up extended passive mode", e);
            reply(425, L10N.getString("ftp.err.local_error"));
        }
    }

    // RFC 959 section 3.1.1 — DATA TYPE
    // Format: TYPE <type-code> [<format-control>]
    // A(SCII) is the default and MUST be accepted.
    // I(MAGE) SHOULD be accepted by all implementations.
    // Note: format control parameter (N/T/C) is not parsed; NON-PRINT is
    // assumed, which is the default per RFC 959 section 3.1.1.5.1.
    private void doType(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        String typeCode = args.trim().toUpperCase();
        switch (typeCode.charAt(0)) {
            case 'A': // RFC 959 section 3.1.1.1
                metadata.setTransferType(FTPConnectionMetadata.FTPTransferType.ASCII);
                reply(200, L10N.getString("ftp.command_ok"));
                break;
            case 'I': // RFC 959 section 3.1.1.3
                metadata.setTransferType(FTPConnectionMetadata.FTPTransferType.BINARY);
                reply(200, L10N.getString("ftp.command_ok"));
                break;
            case 'E': // RFC 959 section 3.1.1.2
                metadata.setTransferType(FTPConnectionMetadata.FTPTransferType.EBCDIC);
                reply(200, L10N.getString("ftp.command_ok"));
                break;
            case 'L': { // RFC 959 section 3.1.1.4 — mandatory byte-size parameter
                String rest = typeCode.substring(1).trim();
                if (rest.isEmpty()) {
                    reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
                    return;
                }
                try {
                    int byteSize = Integer.parseInt(rest);
                    if (byteSize < 1) {
                        reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
                        return;
                    }
                    metadata.setTransferType(FTPConnectionMetadata.FTPTransferType.LOCAL);
                    metadata.setLocalByteSize(byteSize);
                    reply(200, L10N.getString("ftp.command_ok"));
                } catch (NumberFormatException e) {
                    reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
                }
                break;
            }
            default:
                reply(504, L10N.getString("ftp.err.parameter_not_implemented"));
                break;
        }
    }

    // RFC 959 section 3.1.2 — DATA STRUCTURES
    // F(ile) is the default and MUST be accepted.
    // R(ecord) MUST be accepted for text files (ASCII, EBCDIC) per section 3.1.2.2.
    // P(age) is optional; 504 is appropriate.
    private void doStru(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        String structureCode = args.trim().toUpperCase();
        switch (structureCode.charAt(0)) {
            case 'F': // RFC 959 section 3.1.2.1
                reply(200, L10N.getString("ftp.command_ok"));
                break;
            case 'R': // RFC 959 section 3.1.2.2
                FTPConnectionMetadata.FTPTransferType currentType =
                        metadata.getTransferType();
                if (currentType == FTPConnectionMetadata.FTPTransferType.ASCII
                        || currentType == FTPConnectionMetadata.FTPTransferType.EBCDIC) {
                    reply(200, L10N.getString("ftp.command_ok"));
                } else {
                    reply(504, L10N.getString("ftp.err.parameter_not_implemented"));
                }
                break;
            case 'P': // RFC 959 section 3.1.2.3
                reply(504, L10N.getString("ftp.err.parameter_not_implemented"));
                break;
            default:
                reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
                break;
        }
    }

    // RFC 959 section 3.4 — TRANSMISSION MODES
    // S(tream) is the default and MUST be accepted.
    // B(lock) and C(ompressed) are optional; 504 is appropriate.
    private void doMode(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        String modeCode = args.trim().toUpperCase();
        switch (modeCode.charAt(0)) {
            case 'S': // RFC 959 section 3.4.1
                metadata.setTransferMode(FTPConnectionMetadata.FTPTransferMode.STREAM);
                reply(200, L10N.getString("ftp.command_ok"));
                break;
            case 'B': // RFC 959 section 3.4.2
            case 'C': // RFC 959 section 3.4.3
                reply(504, L10N.getString("ftp.err.parameter_not_implemented"));
                break;
            default:
                reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
                break;
        }
    }

    // ── RFC 959 section 4.1.3 — FTP SERVICE COMMANDS ──

    // RFC 959 section 4.1.3: RETR <SP> <pathname> <CRLF>
    private void doRetr(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        String filePath = args.trim();

        if (!checkAuthorization(FTPOperation.READ, filePath)) {
            return;
        }

        try {
            reply(150, L10N.getString("ftp.transfer_starting"));

            FTPDataConnectionCoordinator.PendingTransfer transfer =
                new FTPDataConnectionCoordinator.PendingTransfer(
                    FTPDataConnectionCoordinator.TransferType.DOWNLOAD,
                    filePath,
                    false,
                    restartOffset,
                    handler,
                    metadata
                );

            RetrTransferCallback retrCallback =
                    new RetrTransferCallback(filePath);
            dataCoordinator.startAsyncDownload(
                    endpoint, transfer, retrCallback);

            restartOffset = 0;

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "RETR failed for " + filePath, e);
            recordSessionException(e);
            reply(550, MessageFormat.format(L10N.getString("ftp.err.file_not_found"), filePath));
        }
    }

    /**
     * Completion callback for RETR async download.
     */
    private class RetrTransferCallback
            implements FTPDataConnectionCoordinator.TransferCallback {
        private final String filePath;

        RetrTransferCallback(String filePath) {
            this.filePath = filePath;
        }

        @Override
        public void transferComplete(long bytesTransferred) {
            recordFileTransfer("RETR", filePath);
            try {
                reply(226, L10N.getString("ftp.transfer_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send RETR completion", e);
            }
        }

        @Override
        public void transferFailed(IOException cause) {
            try {
                // 150 already sent; setup/open failure → 450 (not mid-transfer 426)
                reply(450, L10N.getString("ftp.err.file_system_error"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send RETR error", e);
            }
        }
    }

    /**
     * Completion callback for LIST/NLST async listing.
     */
    private class ListTransferCallback
            implements FTPDataConnectionCoordinator.TransferCallback {
        private final String listPath;

        ListTransferCallback(String listPath) {
            this.listPath = listPath;
        }

        @Override
        public void transferComplete(long bytesTransferred) {
            try {
                reply(226, L10N.getString("ftp.transfer_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send LIST completion", e);
            }
        }

        @Override
        public void transferFailed(IOException cause) {
            try {
                reply(450, L10N.getString("ftp.err.file_system_error"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send LIST error", e);
            }
        }
    }

    // RFC 959 section 4.1.3: STOR <SP> <pathname> <CRLF>
    private void doStor(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        String filePath = args.trim();

        if (!checkAuthorization(FTPOperation.WRITE, filePath)) {
            return;
        }

        if (!checkQuotaForUpload(filePath, -1)) {
            return;
        }

        try {
            reply(150, L10N.getString("ftp.transfer_starting"));

            FTPDataConnectionCoordinator.PendingTransfer transfer =
                new FTPDataConnectionCoordinator.PendingTransfer(
                    FTPDataConnectionCoordinator.TransferType.UPLOAD,
                    filePath,
                    false,
                    0,
                    handler,
                    metadata
                );

            StorTransferCallback storCallback =
                    new StorTransferCallback(filePath);
            dataCoordinator.startAsyncUpload(
                    endpoint, transfer, storCallback);

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "STOR failed for " + filePath, e);
            recordSessionException(e);
            reply(550, L10N.getString("ftp.err.file_system_error"));
        }
    }

    /**
     * Completion callback for STOR async upload.
     */
    private class StorTransferCallback
            implements FTPDataConnectionCoordinator.TransferCallback {
        private final String filePath;

        StorTransferCallback(String filePath) {
            this.filePath = filePath;
        }

        @Override
        public void transferComplete(long bytesTransferred) {
            recordFileTransfer("STOR", filePath);
            try {
                reply(226, L10N.getString("ftp.transfer_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send STOR completion", e);
            }
        }

        @Override
        public void transferFailed(IOException cause) {
            try {
                reply(450, L10N.getString("ftp.err.file_system_error"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send STOR error", e);
            }
        }
    }

    // RFC 959 section 4.1.3: STOU <CRLF>
    private void doStou(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (!checkAuthorization(FTPOperation.WRITE, null)) {
            return;
        }

        if (!checkQuotaForUpload(null, -1)) {
            return;
        }

        try {
            reply(150, L10N.getString("ftp.transfer_starting"));

            FTPDataConnectionCoordinator.PendingTransfer transfer =
                new FTPDataConnectionCoordinator.PendingTransfer(
                    FTPDataConnectionCoordinator.TransferType.UPLOAD,
                    "",
                    false,
                    0,
                    handler,
                    metadata
                );

            dataCoordinator.startAsyncUpload(
                    endpoint, transfer, new StouTransferCallback());

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "STOU failed", e);
            reply(550, L10N.getString("ftp.err.file_system_error"));
        }
    }

    /**
     * Completion callback for STOU async upload.
     */
    private class StouTransferCallback
            implements FTPDataConnectionCoordinator.TransferCallback {
        @Override
        public void transferComplete(long bytesTransferred) {
            recordFileTransfer("STOU", "");
            try {
                reply(226, L10N.getString("ftp.transfer_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send STOU completion", e);
            }
        }

        @Override
        public void transferFailed(IOException cause) {
            try {
                reply(450, L10N.getString("ftp.err.file_system_error"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to send STOU error", e);
            }
        }
    }

    // RFC 959 section 4.1.3: APPE <SP> <pathname> <CRLF>
    private void doAppe(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        String filePath = args.trim();

        if (!checkAuthorization(FTPOperation.WRITE, filePath)) {
            return;
        }

        if (!checkQuotaForUpload(filePath, -1)) {
            return;
        }

        try {
            reply(150, L10N.getString("ftp.transfer_starting"));

            FTPDataConnectionCoordinator.PendingTransfer transfer =
                new FTPDataConnectionCoordinator.PendingTransfer(
                    FTPDataConnectionCoordinator.TransferType.UPLOAD,
                    filePath,
                    true,
                    0,
                    handler,
                    metadata
                );

            dataCoordinator.startAsyncUpload(
                    endpoint, transfer, new AppeTransferCallback(filePath));

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "APPE failed for " + filePath, e);
            recordSessionException(e);
            reply(550, L10N.getString("ftp.err.file_system_error"));
        }
    }

    /**
     * Completion callback for APPE async upload.
     */
    private class AppeTransferCallback
            implements FTPDataConnectionCoordinator.TransferCallback {
        private final String filePath;

        AppeTransferCallback(String filePath) {
            this.filePath = filePath;
        }

        @Override
        public void transferComplete(long bytesTransferred) {
            recordFileTransfer("APPE", filePath);
            try {
                reply(226, L10N.getString("ftp.transfer_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send APPE completion", e);
            }
        }

        @Override
        public void transferFailed(IOException cause) {
            try {
                reply(450, L10N.getString("ftp.err.file_system_error"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to send APPE error", e);
            }
        }
    }

    // RFC 959 section 4.1.3: ALLO <SP> <decimal-integer> <CRLF>
    private void doAllo(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        try {
            long size = Long.parseLong(args.trim());
            FTPFileSystem fs = getFileSystem();
            if (fs != null) {
                FTPFileOperationResult result = fs.allocateSpace("", size, metadata);
                handleFileOperationResult(result, "allocation of " + size + " bytes");
            } else {
                reply(200, L10N.getString("ftp.command_ok"));
            }
        } catch (NumberFormatException e) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
        }
    }

    // RFC 959 section 4.1.3 / RFC 3659 section 5: REST <SP> <marker> <CRLF>
    private void doRest(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        try {
            long marker = Long.parseLong(args.trim());
            restartOffset = marker;
            String restartMsg = L10N.getString("ftp.restart_marker");
            reply(350, MessageFormat.format(restartMsg, marker));
        } catch (NumberFormatException e) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
        }
    }

    // RFC 959 section 4.1.3: RNFR <SP> <pathname> <CRLF>
    private void doRnfr(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        final String sourcePath = args.trim();

        if (!checkAuthorization(FTPOperation.RENAME, sourcePath)) {
            return;
        }

        final FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }
        submitStorage(new Callable<FTPFileInfo>() {
            @Override
            public FTPFileInfo call() {
                return fs.getFileInfo(sourcePath, metadata);
            }
        }, new StorageExecutor.Callback<FTPFileInfo>() {
            @Override
            public void completed(FTPFileInfo info) {
                if (info != null) {
                    renameFrom = sourcePath;
                    replyQuietly(350, L10N.getString("ftp.rename_pending"));
                } else {
                    replyQuietly(550, MessageFormat.format(
                            L10N.getString("ftp.err.file_not_found"),
                            sourcePath));
                }
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.WARNING, "RNFR failed for " + sourcePath, error);
                replyQuietly(550, L10N.getString("ftp.err.file_system_error"));
            }
        });
    }

    // RFC 959 section 4.1.3: RNTO <SP> <pathname> <CRLF>
    private void doRnto(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (renameFrom == null) {
            reply(503, L10N.getString("ftp.err.bad_sequence"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        final FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }

        final String targetPath = args.trim();
        final String fromPath = renameFrom;
        // Clear pending rename before offload so a second RNTO cannot race.
        renameFrom = null;
        submitStorage(new Callable<FTPFileOperationResult>() {
            @Override
            public FTPFileOperationResult call() {
                return fs.rename(fromPath, targetPath, metadata);
            }
        }, new StorageExecutor.Callback<FTPFileOperationResult>() {
            @Override
            public void completed(FTPFileOperationResult result) {
                handleFileOperationResultQuietly(
                        result, fromPath + " -> " + targetPath);
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.WARNING,
                        "RNTO failed for " + fromPath + " -> " + targetPath,
                        error);
                replyQuietly(550, L10N.getString("ftp.err.file_system_error"));
            }
        });
    }

    // RFC 959 section 4.1.3: ABOR <CRLF>
    // If a transfer is in progress the server MUST first send 426
    // (connection closed, transfer aborted) then 226.
    private void doAbor(String args) throws IOException {
        boolean wasTransferring = dataCoordinator.abortTransfer();
        if (wasTransferring) {
            reply(426, L10N.getString("ftp.transfer_aborted"));
        }
        reply(226, L10N.getString("ftp.abort_successful"));
    }

    // RFC 959 section 4.1.3: DELE <SP> <pathname> <CRLF>
    private void doDele(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        final String filePath = args.trim();

        if (!checkAuthorization(FTPOperation.DELETE, filePath)) {
            return;
        }

        final FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }

        submitStorage(new Callable<FTPFileOperationResult>() {
            @Override
            public FTPFileOperationResult call() {
                return fs.deleteFile(filePath, metadata);
            }
        }, new StorageExecutor.Callback<FTPFileOperationResult>() {
            @Override
            public void completed(FTPFileOperationResult result) {
                if (result == FTPFileOperationResult.SUCCESS) {
                    recordFileOperation("DELE", filePath);
                }
                handleFileOperationResultQuietly(result, filePath);
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.WARNING, "DELE failed for " + filePath, error);
                replyQuietly(550, L10N.getString("ftp.err.file_system_error"));
            }
        });
    }

    // RFC 959 section 4.1.3: RMD <SP> <pathname> <CRLF>
    private void doRmd(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        final String dirPath = args.trim();

        if (!checkAuthorization(FTPOperation.DELETE_DIR, dirPath)) {
            return;
        }

        final FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }

        submitStorage(new Callable<FTPFileOperationResult>() {
            @Override
            public FTPFileOperationResult call() {
                return fs.removeDirectory(dirPath, metadata);
            }
        }, new StorageExecutor.Callback<FTPFileOperationResult>() {
            @Override
            public void completed(FTPFileOperationResult result) {
                handleFileOperationResultQuietly(result, dirPath);
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.WARNING, "RMD failed for " + dirPath, error);
                replyQuietly(550, L10N.getString("ftp.err.file_system_error"));
            }
        });
    }

    // RFC 959 section 4.1.3: MKD <SP> <pathname> <CRLF>
    private void doMkd(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        final String dirPath = args.trim();

        if (!checkAuthorization(FTPOperation.CREATE_DIR, dirPath)) {
            return;
        }

        final FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }

        submitStorage(new Callable<FTPFileOperationResult>() {
            @Override
            public FTPFileOperationResult call() {
                return fs.createDirectory(dirPath, metadata);
            }
        }, new StorageExecutor.Callback<FTPFileOperationResult>() {
            @Override
            public void completed(FTPFileOperationResult result) {
                if (result == FTPFileOperationResult.SUCCESS) {
                    String successMsg = L10N.getString("ftp.directory_created");
                    replyQuietly(257,
                            MessageFormat.format(successMsg, dirPath));
                } else {
                    handleFileOperationResultQuietly(result, dirPath);
                }
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.WARNING, "MKD failed for " + dirPath, error);
                replyQuietly(550, L10N.getString("ftp.err.file_system_error"));
            }
        });
    }

    // RFC 959 section 4.1.3: PWD <CRLF>
    private void doPwd(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        reply(257, "\"" + currentDirectory + "\" " + L10N.getString("ftp.directory_created").substring(4));
    }

    // RFC 959 section 4.1.3: LIST [<SP> <pathname>] <CRLF>
    private void doList(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        String listPath = (args != null && !args.trim().isEmpty()) ? args.trim() : currentDirectory;

        if (!checkAuthorization(FTPOperation.READ, listPath)) {
            return;
        }

        try {
            reply(150, L10N.getString("ftp.directory_listing"));

            FTPDataConnectionCoordinator.PendingTransfer transfer =
                new FTPDataConnectionCoordinator.PendingTransfer(
                    FTPDataConnectionCoordinator.TransferType.LISTING,
                    listPath,
                    false,
                    0,
                    handler,
                    metadata
                );

            dataCoordinator.startAsyncListing(endpoint, transfer,
                    new ListTransferCallback(listPath));

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "LIST failed for " + listPath, e);
            reply(550, MessageFormat.format(L10N.getString("ftp.err.file_not_found"), listPath));
        }
    }

    // RFC 959 section 4.1.3: NLST [<SP> <pathname>] <CRLF>
    // NLST returns file names only (not full listing lines).
    private void doNlst(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        String listPath = (args != null && !args.trim().isEmpty()) ? args.trim() : currentDirectory;

        if (!checkAuthorization(FTPOperation.READ, listPath)) {
            return;
        }

        try {
            reply(150, L10N.getString("ftp.directory_listing"));

            FTPDataConnectionCoordinator.PendingTransfer transfer =
                new FTPDataConnectionCoordinator.PendingTransfer(
                    FTPDataConnectionCoordinator.TransferType.NAME_LIST,
                    listPath,
                    false,
                    0,
                    handler,
                    metadata
                );

            dataCoordinator.startAsyncListing(endpoint, transfer,
                    new ListTransferCallback(listPath));

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "NLST failed for " + listPath, e);
            reply(550, MessageFormat.format(L10N.getString("ftp.err.file_not_found"), listPath));
        }
    }

    // RFC 959 section 4.1.3: SITE <SP> <string> <CRLF>
    private void doSite(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        String siteCommand = (args != null) ? args.trim() : "";
        String upperCommand = siteCommand.toUpperCase();

        if (upperCommand.equals("QUOTA") || upperCommand.startsWith("QUOTA ")) {
            handleSiteQuota(siteCommand);
            return;
        }

        if (upperCommand.startsWith("SETQUOTA ")) {
            handleSiteSetQuota(siteCommand);
            return;
        }

        if (handler != null) {
            metadata.clearSiteCommandResponse();
            FTPFileOperationResult result = handler.handleSiteCommand(siteCommand, metadata);

            String customResponse = metadata.getSiteCommandResponse();
            if (customResponse != null && result == FTPFileOperationResult.SUCCESS) {
                replyMultiLine(211, customResponse);
            } else {
                handleFileOperationResult(result, siteCommand);
            }
        } else {
            reply(502, L10N.getString("ftp.err.command_not_implemented"));
        }
    }

    private void handleSiteQuota(String args) throws IOException {
        QuotaManager quotaManager = getQuotaManager();
        if (quotaManager == null) {
            reply(502, L10N.getString("ftp.err.quota_not_configured"));
            addSessionEvent("QUOTA_CHECK_FAILED");
            addSessionAttribute("ftp.quota.error", "not_configured");
            return;
        }

        String targetUser = user;
        String argPart = args.length() > 5 ? args.substring(5).trim() : "";
        if (!argPart.isEmpty() && handler != null) {
            if (handler.isAuthorized(FTPOperation.ADMIN, null, metadata)) {
                targetUser = argPart;
            }
        }

        Quota quota = quotaManager.getQuota(targetUser);

        StringBuilder response = new StringBuilder();
        response.append(MessageFormat.format(L10N.getString("ftp.quota.status_for"), targetUser)).append("\r\n");

        QuotaSource source = quota.getSource();
        String sourceDetail = quota.getSourceDetail();
        switch (source) {
            case USER:
                response.append(L10N.getString("ftp.quota.source_user")).append("\r\n");
                break;
            case ROLE:
                response.append(MessageFormat.format(L10N.getString("ftp.quota.source_role"), sourceDetail)).append("\r\n");
                break;
            case DEFAULT:
                response.append(L10N.getString("ftp.quota.source_default")).append("\r\n");
                break;
            default:
                response.append(L10N.getString("ftp.quota.source_none")).append("\r\n");
        }

        if (quota.isStorageUnlimited()) {
            response.append(L10N.getString("ftp.quota.storage_unlimited")).append("\r\n");
        } else {
            String used = QuotaPolicy.formatSize(quota.getStorageUsed());
            String limit = QuotaPolicy.formatSize(quota.getStorageLimit());
            int percent = quota.getStoragePercentUsed();
            response.append(MessageFormat.format(L10N.getString("ftp.quota.storage_status"),
                used, limit, String.valueOf(percent))).append("\r\n");
        }

        addSessionEvent("QUOTA_CHECK");
        addSessionAttribute("ftp.quota.user", targetUser);
        addSessionAttribute("ftp.quota.used", String.valueOf(quota.getStorageUsed()));
        addSessionAttribute("ftp.quota.limit", String.valueOf(quota.getStorageLimit()));

        replyMultiLine(211, response.toString());
    }

    private void handleSiteSetQuota(String args) throws IOException {
        QuotaManager quotaManager = getQuotaManager();
        if (quotaManager == null) {
            reply(502, L10N.getString("ftp.err.quota_not_configured"));
            addSessionEvent("QUOTA_SET_FAILED");
            addSessionAttribute("ftp.quota.error", "not_configured");
            return;
        }

        if (handler == null || !handler.isAuthorized(FTPOperation.ADMIN, null, metadata)) {
            reply(550, L10N.getString("ftp.err.permission_denied"));
            addSessionEvent("QUOTA_SET_DENIED");
            addSessionAttribute("ftp.quota.error", "permission_denied");
            return;
        }

        String argPart = args.substring(8).trim();

        String targetUser = null;
        String storageStr = null;
        int start = 0;
        int length = argPart.length();
        int tokenIndex = 0;
        while (start < length && tokenIndex < 2) {
            while (start < length && Character.isWhitespace(argPart.charAt(start))) {
                start++;
            }
            if (start >= length) {
                break;
            }
            int end = start;
            while (end < length && !Character.isWhitespace(argPart.charAt(end))) {
                end++;
            }
            String token = argPart.substring(start, end);
            if (tokenIndex == 0) {
                targetUser = token;
            } else {
                storageStr = token;
            }
            tokenIndex++;
            start = end;
        }

        if (targetUser == null || storageStr == null) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        try {
            long storageLimit = QuotaPolicy.parseSize(storageStr);
            quotaManager.setUserQuota(targetUser, storageLimit, -1);

            addSessionEvent("QUOTA_SET");
            addSessionAttribute("ftp.quota.target_user", targetUser);
            addSessionAttribute("ftp.quota.new_limit", String.valueOf(storageLimit));

            reply(200, MessageFormat.format(L10N.getString("ftp.quota.set_success"),
                targetUser, QuotaPolicy.formatSize(storageLimit)));

        } catch (IllegalArgumentException e) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            addSessionEvent("QUOTA_SET_FAILED");
            addSessionAttribute("ftp.quota.error", "invalid_format");
        }
    }

    private void replyMultiLine(int code, String message) throws IOException {
        int lineCount = 1;
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '\n') {
                lineCount++;
            }
        }

        StringBuilder response = new StringBuilder();
        int lineStart = 0;
        int msgLen = message.length();
        int lineIndex = 0;

        while (lineStart <= msgLen) {
            int lineEnd = -1;
            for (int i = lineStart; i < msgLen; i++) {
                char c = message.charAt(i);
                if (c == '\n') {
                    lineEnd = i;
                    break;
                } else if (c == '\r' && i + 1 < msgLen && message.charAt(i + 1) == '\n') {
                    lineEnd = i;
                    break;
                }
            }

            String line;
            if (lineEnd < 0) {
                line = message.substring(lineStart);
                lineStart = msgLen + 1;
            } else {
                line = message.substring(lineStart, lineEnd);
                if (lineEnd < msgLen && message.charAt(lineEnd) == '\r') {
                    lineStart = lineEnd + 2;
                } else {
                    lineStart = lineEnd + 1;
                }
            }

            if (lineIndex == lineCount - 1) {
                response.append(code).append(" ").append(line).append("\r\n");
            } else {
                response.append(code).append("-").append(line).append("\r\n");
            }
            lineIndex++;
        }

        send(ByteBuffer.wrap(response.toString().getBytes(US_ASCII)));
    }

    private boolean checkQuotaForUpload(String filePath, long expectedSize) throws IOException {
        QuotaManager quotaManager = getQuotaManager();
        if (quotaManager == null) {
            return true;
        }

        long bytesToCheck = expectedSize > 0 ? expectedSize : 0;

        if (!quotaManager.canStore(user, bytesToCheck)) {
            Quota quota = quotaManager.getQuota(user);

            addSessionEvent("QUOTA_EXCEEDED");
            addSessionAttribute("ftp.quota.file", filePath != null ? filePath : "STOU");
            addSessionAttribute("ftp.quota.requested", String.valueOf(bytesToCheck));
            addSessionAttribute("ftp.quota.available", String.valueOf(quota.getStorageRemaining()));
            addSessionAttribute("ftp.quota.limit", String.valueOf(quota.getStorageLimit()));
            recordSessionError(ErrorCategory.LIMIT_EXCEEDED, L10N.getString("ftp.err.quota_exceeded"));

            reply(552, MessageFormat.format(L10N.getString("ftp.err.quota_exceeded_detail"),
                QuotaPolicy.formatSize(quota.getStorageUsed()),
                QuotaPolicy.formatSize(quota.getStorageLimit())));
            return false;
        }

        return true;
    }

    private QuotaManager getQuotaManager() {
        if (handler != null) {
            return handler.getQuotaManager();
        }
        return null;
    }

    // RFC 959 section 4.1.3: SYST <CRLF>
    private void doSyst(String args) throws IOException {
        reply(215, L10N.getString("ftp.system_type"));
    }

    // RFC 959 section 4.1.3: STAT [<SP> <pathname>] <CRLF>
    // Without argument: server status.
    // With a directory argument: directory listing over the control connection
    // (no data connection needed).
    private void doStat(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            StringBuilder status = new StringBuilder();
            status.append("Server: ").append(metadata.getConnectorDescription()).append("\r\n");
            status.append("User: ").append(authenticated ? user : "Not logged in").append("\r\n");
            status.append("Directory: ").append(currentDirectory).append("\r\n");
            status.append("Secure: ").append(metadata.isSecureConnection() ? "Yes" : "No").append("\r\n");
            reply(211, status.toString());
            return;
        }

        final String path = args.trim();
        final FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }
        submitStorage(new Callable<StatOutcome>() {
            @Override
            public StatOutcome call() {
                FTPFileInfo info = fs.getFileInfo(path, metadata);
                if (info == null) {
                    return StatOutcome.notFound();
                }
                if (info.isDirectory()) {
                    List<FTPFileInfo> files = fs.listDirectory(path, metadata);
                    return StatOutcome.directory(files);
                }
                return StatOutcome.file(info);
            }
        }, new StorageExecutor.Callback<StatOutcome>() {
            @Override
            public void completed(StatOutcome outcome) {
                if (outcome.kind == StatOutcome.Kind.NOT_FOUND) {
                    replyQuietly(550, MessageFormat.format(
                            L10N.getString("ftp.err.file_not_found"), path));
                } else if (outcome.kind == StatOutcome.Kind.DIRECTORY) {
                    if (outcome.listing == null) {
                        replyQuietly(550, MessageFormat.format(
                                L10N.getString("ftp.err.file_not_found"), path));
                        return;
                    }
                    sendLineQuietly("213-Status of " + path + ":");
                    for (FTPFileInfo file : outcome.listing) {
                        sendLineQuietly(" " + file.formatAsListingLine());
                    }
                    sendLineQuietly("213 End of status");
                } else {
                    replyQuietly(213, outcome.info.formatAsListingLine());
                }
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.WARNING, "STAT failed for " + path, error);
                replyQuietly(550, L10N.getString("ftp.err.file_system_error"));
            }
        });
    }

    /** Outcome of an offloaded STAT-with-path filesystem probe. */
    private static final class StatOutcome {
        enum Kind { NOT_FOUND, FILE, DIRECTORY }

        final Kind kind;
        final FTPFileInfo info;
        final List<FTPFileInfo> listing;

        private StatOutcome(Kind kind, FTPFileInfo info,
                List<FTPFileInfo> listing) {
            this.kind = kind;
            this.info = info;
            this.listing = listing;
        }

        static StatOutcome notFound() {
            return new StatOutcome(Kind.NOT_FOUND, null, null);
        }

        static StatOutcome file(FTPFileInfo info) {
            return new StatOutcome(Kind.FILE, info, null);
        }

        static StatOutcome directory(List<FTPFileInfo> listing) {
            return new StatOutcome(Kind.DIRECTORY, null, listing);
        }
    }

    // RFC 959 section 4.1.3: HELP [<SP> <string>] <CRLF>
    private void doHelp(String args) throws IOException {
        reply(214, L10N.getString("ftp.help_message"));
    }

    // RFC 959 section 4.1.3: NOOP <CRLF>
    private void doNoop(String args) throws IOException {
        reply(200, L10N.getString("ftp.no_operation"));
    }

    // ── RFC 4217 — FTP Security Extensions ──

    // RFC 4217 section 4: AUTH <SP> <mechanism> <CRLF>
    // AUTH TLS initiates explicit TLS on the control connection.
    // RFC 4217 section 4 says re-AUTH MUST be accepted (to renegotiate);
    // the `!authUsed` guard in `lineRead` incorrectly blocks re-AUTH.
    private void doAuth(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            reply(504, L10N.getString("ftp.err.auth_mechanism_required"));
            return;
        }

        String mechanism = args.trim().toUpperCase();

        if (!mechanism.equals("TLS") && !mechanism.equals("SSL")) {
            reply(504, MessageFormat.format(L10N.getString("ftp.err.auth_unknown_mechanism"), mechanism));
            return;
        }

        if (endpoint.isSecure()) {
            reply(503, L10N.getString("ftp.err.already_secure"));
            return;
        }

        if (server == null || !server.isSTARTTLSAvailable()) {
            reply(534, L10N.getString("ftp.err.auth_tls_not_available"));
            return;
        }

        try {
            reply(234, MessageFormat.format(L10N.getString("ftp.auth_tls_ok"), mechanism));

            endpoint.startTLS();

            pbszSet = false;
            dataProtection = false;

            recordStartTLSSuccess();

            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info("AUTH " + mechanism + " upgrade initiated for " + getRemoteSocketAddress());
            }

        } catch (Exception e) {
            recordStartTLSFailure(e);
            reply(431, L10N.getString("ftp.err.auth_tls_failed"));
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "AUTH TLS failed", e);
            }
        }
    }

    // RFC 4217 section 8: PBSZ <SP> <decimal-integer> <CRLF>
    // For TLS, the protection buffer size MUST be 0. The server
    // always responds with PBSZ=0 regardless of client value.
    private void doPbsz(String args) throws IOException {
        if (!endpoint.isSecure()) {
            reply(503, L10N.getString("ftp.err.pbsz_requires_auth"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.pbsz_size_required"));
            return;
        }

        try {
            int bufferSize = Integer.parseInt(args.trim());
            if (bufferSize != 0) {
                reply(200, "PBSZ=0");
            } else {
                reply(200, "PBSZ=0");
            }
            pbszSet = true;

        } catch (NumberFormatException e) {
            reply(501, L10N.getString("ftp.err.pbsz_invalid_size"));
        }
    }

    // RFC 4217 section 9: PROT <SP> <level> <CRLF>
    // C = Clear (no protection), P = Private (TLS on data channel).
    // S (Safe) and E (Confidential) are not supported — 536 is correct.
    // PBSZ must be issued before PROT (RFC 4217 section 9).
    private void doProt(String args) throws IOException {
        if (!endpoint.isSecure()) {
            reply(503, L10N.getString("ftp.err.prot_requires_auth"));
            return;
        }

        if (!pbszSet) {
            reply(503, L10N.getString("ftp.err.prot_requires_pbsz"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.prot_level_required"));
            return;
        }

        String level = args.trim().toUpperCase();

        switch (level) {
            case "C":
                dataProtection = false;
                dataCoordinator.setDataProtection(false);
                reply(200, L10N.getString("ftp.prot_clear"));
                break;

            case "P":
                dataProtection = true;
                dataCoordinator.setDataProtection(true);
                reply(200, L10N.getString("ftp.prot_private"));
                break;

            case "S":
            case "E":
                reply(536, MessageFormat.format(L10N.getString("ftp.err.prot_level_not_supported"), level));
                break;

            default:
                reply(504, MessageFormat.format(L10N.getString("ftp.err.prot_unknown_level"), level));
        }
    }

    // RFC 4217 section 6: CCC <CRLF>
    // Clears the command channel protection (reverts control connection
    // to plaintext).  Server MAY refuse; 533 is appropriate.
    private void doCcc(String args) throws IOException {
        reply(533, L10N.getString("ftp.err.ccc_not_supported"));
    }

    // ── RFC 3659 — Extensions to FTP ──

    // RFC 3659 section 4: SIZE <SP> <pathname> <CRLF>
    // Returns the transfer size of the file identified by <pathname>.
    private void doSize(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }
        final String path = args.trim();
        if (!checkAuthorization(FTPOperation.READ, path)) {
            return;
        }
        final FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }
        submitStorage(new Callable<FTPFileInfo>() {
            @Override
            public FTPFileInfo call() {
                return fs.getFileInfo(path, metadata);
            }
        }, new StorageExecutor.Callback<FTPFileInfo>() {
            @Override
            public void completed(FTPFileInfo info) {
                if (info == null) {
                    replyQuietly(550, MessageFormat.format(
                            L10N.getString("ftp.err.file_not_found"), path));
                    return;
                }
                if (info.isDirectory()) {
                    replyQuietly(550, MessageFormat.format(
                            L10N.getString("ftp.err.is_directory"), path));
                    return;
                }
                // RFC 3659 section 4: reply code 213
                replyQuietly(213, String.valueOf(info.getSize()));
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.WARNING, "SIZE failed for " + path, error);
                replyQuietly(550, L10N.getString("ftp.err.file_system_error"));
            }
        });
    }

    // RFC 3659 section 3: MDTM <SP> <pathname> <CRLF>
    // Returns the last modification time of the file as YYYYMMDDhhmmss (UTC).
    private void doMdtm(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }
        final String path = args.trim();
        if (!checkAuthorization(FTPOperation.READ, path)) {
            return;
        }
        final FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }
        submitStorage(new Callable<FTPFileInfo>() {
            @Override
            public FTPFileInfo call() {
                return fs.getFileInfo(path, metadata);
            }
        }, new StorageExecutor.Callback<FTPFileInfo>() {
            @Override
            public void completed(FTPFileInfo info) {
                if (info == null) {
                    replyQuietly(550, MessageFormat.format(
                            L10N.getString("ftp.err.file_not_found"), path));
                    return;
                }
                if (info.isDirectory()) {
                    replyQuietly(550, MessageFormat.format(
                            L10N.getString("ftp.err.is_directory"), path));
                    return;
                }
                java.time.Instant modified = info.getLastModified();
                if (modified == null) {
                    replyQuietly(550, MessageFormat.format(
                            L10N.getString("ftp.err.file_not_found"), path));
                    return;
                }
                // RFC 3659 section 3: reply code 213, format YYYYMMDDhhmmss
                String timestamp = java.time.format.DateTimeFormatter
                        .ofPattern("yyyyMMddHHmmss")
                        .withZone(java.time.ZoneOffset.UTC)
                        .format(modified);
                replyQuietly(213, timestamp);
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.WARNING, "MDTM failed for " + path, error);
                replyQuietly(550, L10N.getString("ftp.err.file_system_error"));
            }
        });
    }

    // RFC 3659 section 7.2: MLST <SP> <pathname> <CRLF>
    // Returns a single machine-readable listing entry over the control connection.
    private void doMlst(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        final String path = (args != null && !args.trim().isEmpty())
                ? args.trim() : currentDirectory;
        if (!checkAuthorization(FTPOperation.READ, path)) {
            return;
        }
        final FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }
        submitStorage(new Callable<FTPFileInfo>() {
            @Override
            public FTPFileInfo call() {
                return fs.getFileInfo(path, metadata);
            }
        }, new StorageExecutor.Callback<FTPFileInfo>() {
            @Override
            public void completed(FTPFileInfo info) {
                if (info == null) {
                    replyQuietly(550, MessageFormat.format(
                            L10N.getString("ftp.err.file_not_found"), path));
                    return;
                }
                // RFC 3659 section 7.2: multi-line 250 with leading space on the entry
                sendLineQuietly("250-Listing " + path);
                sendLineQuietly(" " + info.formatAsMLSEntry());
                sendLineQuietly("250 End");
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.WARNING, "MLST failed for " + path, error);
                replyQuietly(550, L10N.getString("ftp.err.file_system_error"));
            }
        });
    }

    // RFC 3659 section 7.2: MLSD [<SP> <pathname>] <CRLF>
    // Machine-readable directory listing over the data connection.
    private void doMlsd(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        String listPath = (args != null && !args.trim().isEmpty()) ? args.trim() : currentDirectory;
        if (!checkAuthorization(FTPOperation.READ, listPath)) {
            return;
        }
        try {
            reply(150, L10N.getString("ftp.directory_listing"));
            FTPDataConnectionCoordinator.PendingTransfer transfer =
                new FTPDataConnectionCoordinator.PendingTransfer(
                    FTPDataConnectionCoordinator.TransferType.MACHINE_LISTING,
                    listPath,
                    false,
                    0,
                    handler,
                    metadata
                );
            dataCoordinator.startAsyncListing(endpoint, transfer,
                    new ListTransferCallback(listPath));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "MLSD failed for " + listPath, e);
            reply(550, MessageFormat.format(L10N.getString("ftp.err.file_not_found"), listPath));
        }
    }

    // ── RFC 2640 — Internationalization of FTP ──

    // RFC 2640: OPTS <SP> <command> [<SP> <parameter>] <CRLF>
    // Handles OPTS UTF8 ON to enable UTF-8 on the control connection.
    private void doOpts(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }
        String option = args.trim().toUpperCase();
        // RFC 2640 section 4.2: OPTS UTF8 ON
        if ("UTF8 ON".equals(option) || "UTF8".equals(option)) {
            utf8Enabled = true;
            reply(200, "UTF8 set to ON");
        } else {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
        }
    }

    // RFC 2389 section 3.2: FEAT response format:
    //   211-Extensions supported:
    //    <feature>
    //   211 End
    // RFC 2389 section 3: "A server MUST NOT include in the FEAT response
    // any feature which it does not support."
    // SIZE (RFC 3659 section 4) and MDTM (section 3) are not implemented
    // and were incorrectly advertised.  UTF8 was also not truly supported.
    // REST STREAM (RFC 3659 section 5) is supported via doRest.
    // TVFS (RFC 3659 section 6) is supported by BasicFTPFileSystem.
    private void doFeat(String args) throws IOException {
        sendLine("211-Extensions supported:");

        sendLine(" EPRT");
        sendLine(" EPSV");

        if (server != null && server.isSTARTTLSAvailable() && !endpoint.isSecure()) {
            sendLine(" AUTH TLS");
            sendLine(" AUTH SSL");
        }
        if (endpoint.isSecure()) {
            sendLine(" PBSZ");
            sendLine(" PROT");
        }

        sendLine(" REST STREAM");
        sendLine(" TVFS");

        // RFC 3659 extensions
        sendLine(" SIZE");
        sendLine(" MDTM");
        sendLine(" MLST size*;modify*;type*;perm*;");

        // RFC 2640: UTF-8 support
        sendLine(" UTF8");

        sendLine("211 End");
    }

    /**
     * Returns whether data connections should be TLS-protected.
     *
     * @return true if PROT P is active
     */
    public boolean isDataProtectionEnabled() {
        return dataProtection;
    }

    // ── Telemetry ──

    private void initConnectionTrace() {
        if (endpoint != null && endpoint.isTelemetryEnabled()) {
            TelemetryConfig config = endpoint.getTelemetryConfig();
            if (config != null) {
                String traceName = L10N.getString("telemetry.ftp_connection");
                connectionTrace = config.createTrace(traceName);
                if (connectionTrace != null) {
                    Span rootSpan = connectionTrace.getRootSpan();
                    if (rootSpan != null) {
                        rootSpan.addAttribute("net.transport", "ip_tcp");
                        rootSpan.addAttribute("net.peer.ip", getRemoteSocketAddress());
                        rootSpan.addAttribute("rpc.system", "ftp");
                    }
                }
            }
        }
    }

    private void startSessionSpan() {
        if (connectionTrace != null) {
            String spanName = L10N.getString("telemetry.ftp_session");
            sessionSpan = connectionTrace.startSpan(spanName, SpanKind.SERVER);
            if (sessionSpan != null) {
                sessionSpan.addEvent("SESSION_START");
            }
        }
    }

    private void startAuthenticatedSpan() {
        if (sessionSpan != null) {
            String spanName = L10N.getString("telemetry.ftp_authenticated");
            authenticatedSpan = sessionSpan.startChild(spanName, SpanKind.SERVER);
            if (authenticatedSpan != null) {
                authenticatedSpan.addAttribute("enduser.id", user);
                authenticatedSpan.addEvent("AUTHENTICATED");
            }
        }
    }

    private void endSessionSpan() {
        if (sessionSpan != null) {
            sessionSpan.addEvent("SESSION_END");
            sessionSpan.setStatusOk();
            sessionSpan.end();
            sessionSpan = null;
        }
        if (connectionTrace != null) {
            connectionTrace.end();
            connectionTrace = null;
        }
    }

    private void endSessionSpanError(String error) {
        if (sessionSpan != null) {
            sessionSpan.addEvent("SESSION_ERROR");
            sessionSpan.setStatusError(error);
            sessionSpan.end();
            sessionSpan = null;
        }
        if (connectionTrace != null) {
            connectionTrace.end();
            connectionTrace = null;
        }
    }

    private void endAuthenticatedSpan() {
        if (authenticatedSpan != null) {
            authenticatedSpan.setStatusOk();
            authenticatedSpan.end();
            authenticatedSpan = null;
        }
    }

    private void addSessionAttribute(String key, String value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    private void addSessionEvent(String event) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addEvent(event);
        }
    }

    private void addAuthenticatedEvent(String event) {
        if (authenticatedSpan != null && !authenticatedSpan.isEnded()) {
            authenticatedSpan.addEvent(event);
        }
    }

    private void recordSessionException(Exception e) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.recordExceptionWithCategory(e);
        }
    }

    private void recordSessionException(Exception e, ErrorCategory category) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.recordException(e, category);
        }
    }

    private void recordSessionError(ErrorCategory category, String message) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.recordError(category, 0, message);
        }
    }

    private void recordAuthenticationSuccess(String mechanism) {
        addSessionAttribute("ftp.auth.mechanism", mechanism);
        addSessionAttribute("enduser.id", user);
        addSessionEvent("AUTH_SUCCESS");
        startAuthenticatedSpan();
    }

    private void recordAuthenticationFailure(String mechanism, String attemptedUser) {
        addSessionAttribute("ftp.auth.mechanism", mechanism);
        if (attemptedUser != null) {
            addSessionAttribute("ftp.auth.attempted_user", attemptedUser);
        }
        addSessionEvent("AUTH_FAILURE");
    }

    private void recordStartTLSSuccess() {
        addSessionEvent("STARTTLS_SUCCESS");
        addSessionAttribute("net.protocol.name", "ftps");
    }

    private void recordStartTLSFailure(Exception e) {
        addSessionEvent("STARTTLS_FAILURE");
        recordSessionException(e);
    }

    private void recordFileTransfer(String operation, String path) {
        addAuthenticatedEvent(operation);
        if (authenticatedSpan != null && !authenticatedSpan.isEnded()) {
            authenticatedSpan.addAttribute("ftp.file.path", path);
        }
    }

    private void recordFileOperation(String operation, String path) {
        addAuthenticatedEvent(operation);
        if (authenticatedSpan != null && !authenticatedSpan.isEnded()) {
            authenticatedSpan.addAttribute("ftp.file.path", path);
        }
    }

}
