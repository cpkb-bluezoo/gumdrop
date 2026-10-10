/*
 * SmtpProtocolHandler.java
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

package org.bluezoo.gumdrop.smtp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.Subject;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;

import org.bluezoo.gumdrop.ByteStreamLexer;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.TokenErrorRecovery;
import org.bluezoo.gumdrop.auth.GssapiServer;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.RealmCallback;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.auth.SaslUtils;
import org.bluezoo.gumdrop.mime.HeaderLineTooLongException;
import org.bluezoo.gumdrop.mime.HeaderValueTooLongException;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddressParser;
import org.bluezoo.gumdrop.smtp.server.AuthenticateState;
import org.bluezoo.gumdrop.smtp.server.ClientConnected;
import org.bluezoo.gumdrop.smtp.server.ConnectedState;
import org.bluezoo.gumdrop.smtp.server.HelloHandler;
import org.bluezoo.gumdrop.smtp.server.HelloState;
import org.bluezoo.gumdrop.smtp.server.MailFromHandler;
import org.bluezoo.gumdrop.smtp.server.MailFromState;
import org.bluezoo.gumdrop.smtp.server.MessageEndState;
import org.bluezoo.gumdrop.smtp.server.MessageStartState;
import org.bluezoo.gumdrop.smtp.server.RecipientState;
import org.bluezoo.gumdrop.smtp.server.ResetState;
import org.bluezoo.gumdrop.smtp.server.MessageDataHandler;
import org.bluezoo.gumdrop.smtp.server.MessageEndState;
import org.bluezoo.gumdrop.smtp.server.MessageStartState;
import org.bluezoo.gumdrop.smtp.server.RecipientHandler;
import org.bluezoo.gumdrop.smtp.server.RecipientState;
import org.bluezoo.gumdrop.smtp.server.ResetState;

import org.bluezoo.gumdrop.smtp.DeliveryRequirements;
import org.bluezoo.gumdrop.smtp.DsnNotify;
import org.bluezoo.gumdrop.smtp.DsnReturn;
import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.telemetry.EventLogger;

/**
 * SMTP server protocol handler implementing RFC 5321 (SMTP) and
 * RFC 6409 (Message Submission).
 *
 * <p>Implements the SMTP protocol with the transport layer fully decoupled:
 * <ul>
 * <li>Transport operations delegate to an {@link Endpoint} reference
 *     received in {@link #connected(Endpoint)}</li>
 * <li>Line parsing uses a streaming {@link SmtpServerLexer} (issue #85):
 *     bytes are tokenised as they arrive rather than buffered into whole
 *     lines — see {@link ByteStreamLexer}. DATA (RFC 5321 §4.5.2) and BDAT
 *     (RFC 3030) message content bypass the lexer entirely; {@link
 *     #receive(ByteBuffer)} checks state before calling {@link
 *     SmtpServerLexer#feed}, exactly as before this conversion</li>
 * <li>TLS upgrade uses {@link Endpoint#startTLS()}</li>
 * <li>Security info uses {@link Endpoint#getSecurityInfo()}</li>
 * </ul>
 *
 * <p>Supported SMTP extensions:
 * <ul>
 *   <li>STARTTLS (RFC 3207)</li>
 *   <li>AUTH (RFC 4954) &mdash; PLAIN, LOGIN, SCRAM-SHA-256, OAUTHBEARER, EXTERNAL</li>
 *   <li>SIZE (RFC 1870)</li>
 *   <li>8BITMIME (RFC 6152)</li>
 *   <li>SMTPUTF8 (RFC 6531)</li>
 *   <li>PIPELINING (RFC 2920)</li>
 *   <li>CHUNKING / BINARYMIME (RFC 3030)</li>
 *   <li>ENHANCEDSTATUSCODES (RFC 2034)</li>
 *   <li>DSN (RFC 3461)</li>
 *   <li>LIMITS (RFC 9422)</li>
 *   <li>REQUIRETLS (RFC 8689)</li>
 *   <li>MT-PRIORITY (RFC 6710)</li>
 *   <li>FUTURERELEASE (RFC 4865)</li>
 *   <li>DELIVERBY (RFC 2852)</li>
 *   <li>XCLIENT (Postfix extension)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtocolHandler
 * @see SmtpServerLexer
 * @see SmtpListener
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5321">RFC 5321 - SMTP</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6409">RFC 6409 - Message Submission</a>
 */
public final class SmtpProtocolHandler
        implements ProtocolHandler, ByteStreamLexer.Handler<SmtpServerLexer.Token>,
                   ConnectedState, HelloState, AuthenticateState,
                   MailFromState, RecipientState, MessageStartState, MessageEndState,
                   ResetState, SmtpConnectionMetadata {

    private static final Logger LOGGER =
            Logger.getLogger(SmtpProtocolHandler.class.getName());

    private EventLogger events() {
        // before the handler is connected, events go to a configuration of its own
        TelemetryConfig telemetry = endpoint != null ? endpoint.getTelemetryConfig() : new TelemetryConfig();
        return telemetry.getLogger(SmtpProtocolHandler.class, L10N);
    }
    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");

    static final Charset US_ASCII = Charset.forName("US-ASCII");
    static final CharsetDecoder US_ASCII_DECODER = US_ASCII.newDecoder();
    static final Charset UTF_8 = Charset.forName("UTF-8");
    static final CharsetDecoder UTF_8_DECODER = UTF_8.newDecoder();

    private static final int MAX_LINE_LENGTH = 998;
    private static final int MAX_CONTROL_BUFFER_SIZE = 8;
    private static final int MAX_COMMAND_LINE_LENGTH = 1000;

    enum SmtpState {
        INITIAL, REJECTED, READY, MAIL, RCPT, DATA, BDAT, QUIT
    }

    enum AuthState {
        NONE, PLAIN_RESPONSE, LOGIN_USERNAME, LOGIN_PASSWORD,
        SCRAM_INITIAL, SCRAM_FINAL,
        OAUTH_RESPONSE, GSSAPI_EXCHANGE, EXTERNAL_CERT
    }

    enum DataState {
        NORMAL, SAW_CR, SAW_CRLF, SAW_DOT, SAW_DOT_CR
    }

    // RFC 5321 §4.1.1, RFC 3030, RFC 4954, Postfix XCLIENT — recognised
    // command verbs. Resolved once, directly from the KEYWORD token's
    // bytes (issue #85), rather than buffered as a String and re-compared
    // later at dispatch time.
    enum SmtpCommand {
        HELO, EHLO, MAIL, RCPT, DATA, BDAT, RSET, QUIT, NOOP, HELP, VRFY,
        EXPN, STARTTLS, AUTH, XCLIENT, ETRN,
        UNKNOWN
    }

    private Endpoint endpoint;

    private final SmtpListener server;
    private final ClientConnected connectedHandler;
    private final long connectionTimeMillis;
    private final List<EmailAddress> recipients;
    private final Map<EmailAddress, DsnRecipientParameters> dsnRecipients;
    private ByteBuffer controlBuffer;

    // Streaming lexer (issue #85) and per-line parse state
    private final SmtpServerLexer lexer;
    private final TokenErrorRecovery<SmtpServerLexer.Token> lexerRecovery =
            new TokenErrorRecovery<SmtpServerLexer.Token>(SmtpServerLexer.Token.CRLF);
    private SmtpCommand pendingCommand = SmtpCommand.UNKNOWN;
    private String pendingUnknownText = "";
    private String pendingContinuationText = "";
    private boolean pendingHasSp;
    private boolean pendingUseUtf8;
    private boolean pendingSawNonAscii;
    private final StringBuilder argsBuilder = new StringBuilder();
    private int lineByteCount;
    private String lineErrorMessage;

    private Realm realm;
    private HelloHandler helloHandler;
    private MailFromHandler mailFromHandler;
    private RecipientHandler recipientHandler;
    private MessageDataHandler messageHandler;
    private SmtpPipeline currentPipeline;

    /** Domain this server announced in its greeting, or null if none. */
    private String greetingDomain;

    private SmtpState state = SmtpState.INITIAL;
    private String heloName;
    private EmailAddress mailFrom;
    private boolean extendedSMTP;
    private boolean smtputf8;
    private BodyType bodyType = BodyType.SEVEN_BIT;
    private DefaultDeliveryRequirements deliveryRequirements;

    private DsnRecipientParameters pendingRecipientDSN;
    private boolean authenticated;
    private String authenticatedUser;
    private AuthState authState = AuthState.NONE;
    private String authMechanism;
    private String pendingAuthUsername;
    private boolean starttlsUsed;

    private String authChallenge;
    private String authNonce;
    private String authClientNonce;
    private String authServerSignature;
    private byte[] authSalt;
    private int authIterations = 4096;

    private SaslServer saslServer;
    private GssapiServer.GssapiExchange gssapiExchange;
    private X509Certificate clientCertificate;
    private InetSocketAddress xclientAddr;
    private InetSocketAddress xclientDestAddr;
    private String xclientName;
    private String xclientHelo;
    private String xclientProto;
    private String xclientLogin;

    private DataState dataState = DataState.NORMAL;
    private long dataBytesReceived;
    private boolean sizeExceeded;
    private boolean dataTransferRejected;
    private String dataTransferRejectionMessage;

    // Set while a message data handler is completing delivery asynchronously
    // (it returned from messageComplete() without synchronously calling
    // acceptMessageDelivery/rejectMessage*). While pending, any further input
    // is retained rather than processed, so the (possibly pipelined) next
    // command is not handled in a stale DATA/BDAT state. The retained bytes
    // are re-driven once the async delivery reply is sent.
    private boolean deliveryPending;
    private ByteBuffer retainedInput;

    private long bdatBytesRemaining;
    private boolean bdatLast;
    private boolean bdatStarted;

    private Span sessionSpan;
    private int sessionNumber;
    private int transactionCount;

    private EmailAddress pendingRecipient;

    public SmtpProtocolHandler(SmtpListener server, ClientConnected handler) {
        this.server = server;
        this.connectedHandler = handler;
        this.connectionTimeMillis = System.currentTimeMillis();
        this.recipients = new ArrayList<EmailAddress>();
        this.dsnRecipients = new HashMap<EmailAddress, DsnRecipientParameters>();
        this.controlBuffer = ByteBuffer.allocate(MAX_CONTROL_BUFFER_SIZE);
        ByteStreamLexer.checkTokenCap(MAX_COMMAND_LINE_LENGTH, server.getMaxNetInSize());
        this.lexer = new SmtpServerLexer(this, MAX_COMMAND_LINE_LENGTH);
    }

    // ── ProtocolHandler implementation ──

    /** RFC 5321 §4.2 — server greeting (220) on connection. */
    @Override
    public void connected(Endpoint ep) {
        this.endpoint = ep;
        initConnectionTrace();
        SmtpServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.connectionOpened();
        }
        if (endpoint.isSecure()) {
            return;
        }
        sendGreeting();
    }

    /** RFC 5321 §2.3.8 — line-oriented command processing; DATA/BDAT binary. */
    @Override
    public void receive(ByteBuffer buf) {
        if (deliveryPending) {
            // An async message delivery is in flight; do not process further
            // input (which may be a pipelined next command) until the reply
            // is sent. The bytes are re-driven from the delivery callback.
            retainInput(buf);
            return;
        }
        if (state == SmtpState.BDAT) {
            handleBdatContent(buf);
        } else if (state == SmtpState.DATA) {
            handleDataContent(buf);
        } else {
            lexer.feed(buf);
            if (buf.hasRemaining()) {
                if (state == SmtpState.BDAT) {
                    handleBdatContent(buf);
                } else if (state == SmtpState.DATA) {
                    handleDataContent(buf);
                }
            }
        }
    }

    @Override
    public void disconnected() {
        try {
            SmtpServerMetrics metrics = getServerMetrics();
            if (metrics != null) {
                double durationMs = System.currentTimeMillis() - connectionTimeMillis;
                metrics.connectionClosed(durationMs);
            }
            if (sessionSpan != null && !sessionSpan.isEnded()) {
                if (state == SmtpState.QUIT) {
                    endSessionSpan("Connection closed");
                } else {
                    endSessionSpanError("Connection lost");
                }
            }
            if (connectedHandler != null) {
                connectedHandler.disconnected();
            }
            // Connection admission (rate-limit / per-IP / global count) is
            // released centrally by TcpEndpoint when the endpoint closes, so
            // no per-handler connectionClosed call is needed here.
        } catch (Exception e) {
            events().warn("warn.error_disconnected_handler").thrown(e).emit();
        } finally {
            if (recipients != null) {
                recipients.clear();
            }
            if (dsnRecipients != null) {
                dsnRecipients.clear();
            }
            deliveryRequirements = null;
            resetDataState();
        }
    }

    /**
     * RFC 3207 §4.2 — post-STARTTLS, state resets and client must re-EHLO.
     * RFC 8314 — implicit TLS (port 465): greeting sent after TLS established.
     */
    @Override
    public void securityEstablished(SecurityInfo info) {
        if (state == SmtpState.INITIAL && !starttlsUsed) {
            sendGreeting();
        } else if (helloHandler != null && starttlsUsed) {
            helloHandler.tlsEstablished(info);
        }
    }

    @Override
    public void error(Exception cause) {
        events().warn("warn.smtp_transport_error").thrown(cause).emit();
        if (endpoint != null) {
            endpoint.close();
        }
    }

    // ── ByteStreamLexer.Handler implementation (issue #85) ──

    // RFC 5321 §2.3.8: KEYWORD [SP TEXT] CRLF. TEXT is delivered in
    // zero-copy chunks by the lexer (see SmtpServerLexer / ByteStreamLexer);
    // this dispatcher accumulates only what it needs to retain (the args
    // string) and enforces the combined line-length budget itself, since
    // free-form text is intentionally exempt from the lexer's own cap.
    @Override
    public boolean token(SmtpServerLexer.Token type, ByteBuffer window) {
        if (lexerRecovery.handleToken(type)) {
            // Discarding the remainder of a line already rejected by
            // tokenTooLong(); the error reply was already sent there.
            return false;
        }
        switch (type) {
            case KEYWORD:
                lineByteCount = window.remaining();
                // RFC 6531 §3.4 — MAIL FROM addresses may need UTF-8; this
                // mirrors the pre-conversion isMailCommandPrefix() peek: a
                // 4-byte "MAIL" keyword can never itself contain non-ASCII
                // bytes, so this check is purely a byte comparison, no
                // decode needed.
                pendingUseUtf8 = smtputf8 || (state == SmtpState.READY
                        && isMailKeyword(window));
                if (authState != AuthState.NONE) {
                    // SASL continuation data must preserve original case,
                    // and isn't a command at all, so it is never matched
                    // against known verbs.
                    try {
                        pendingContinuationText = decodeText(window, pendingUseUtf8);
                    } catch (CharacterCodingException e) {
                        lineErrorMessage = L10N.getString("smtp.err.invalid_encoding");
                    }
                } else {
                    // Resolve the command directly from the token's bytes
                    // now, once, rather than buffering a string to
                    // re-compare at CRLF time. A string is decoded only for
                    // the (rare) unrecognised-verb case, where the exact
                    // text is needed for the error reply.
                    pendingCommand = matchCommand(window);
                    if (pendingCommand == SmtpCommand.UNKNOWN) {
                        try {
                            pendingUnknownText =
                                    decodeText(window, pendingUseUtf8).toUpperCase(Locale.ENGLISH);
                        } catch (CharacterCodingException e) {
                            lineErrorMessage = L10N.getString("smtp.err.invalid_encoding");
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
                    if (lineByteCount + len > MAX_COMMAND_LINE_LENGTH) {
                        lineErrorMessage = L10N.getString("smtp.err.line_too_long");
                    } else {
                        if (containsNonAscii(window)) {
                            pendingSawNonAscii = true;
                        }
                        try {
                            argsBuilder.append(decodeText(window, pendingUseUtf8));
                            lineByteCount += len;
                        } catch (CharacterCodingException e) {
                            lineErrorMessage = L10N.getString("smtp.err.invalid_encoding");
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
        // SMTP's command channel is always line-based; DATA/BDAT content is
        // handled directly from receive(), never as a lexer raw escape, so
        // this is structurally unreachable.
        events().warn("warn.unexpected_raw_bytes_server").emit();
    }

    @Override
    public void tokenTooLong() {
        lexerRecovery.beginDiscard();
        resetLineState();
        reply(500, L10N.getString("smtp.err.line_too_long"));
    }

    private static String decodeText(ByteBuffer window, boolean utf8)
            throws CharacterCodingException {
        CharsetDecoder decoder = utf8 ? UTF_8_DECODER : US_ASCII_DECODER;
        return decoder.decode(window).toString();
    }

    private static boolean containsNonAscii(ByteBuffer window) {
        int base = window.position();
        int lim = window.limit();
        for (int i = base; i < lim; i++) {
            if ((window.get(i) & 0xFF) > 127) {
                return true;
            }
        }
        return false;
    }

    private void resetLineState() {
        pendingCommand = SmtpCommand.UNKNOWN;
        pendingUnknownText = "";
        pendingContinuationText = "";
        pendingHasSp = false;
        pendingUseUtf8 = false;
        pendingSawNonAscii = false;
        argsBuilder.setLength(0);
        lineByteCount = 0;
        lineErrorMessage = null;
    }

    /**
     * Matches a KEYWORD token's raw bytes against the known SMTP verbs
     * (RFC 5321 §4.1.1, RFC 3030, RFC 4954, Postfix XCLIENT), case-
     * insensitively, without decoding to a String.
     *
     * @param window the KEYWORD token's bytes
     * @return the matched command, or {@link SmtpCommand#UNKNOWN}
     */
    private static SmtpCommand matchCommand(ByteBuffer window) {
        int len = window.remaining();
        int base = window.position();
        if (len == 4) {
            switch (pack4(window, base)) {
                case ('H' << 24) | ('E' << 16) | ('L' << 8) | 'O':
                    return SmtpCommand.HELO;
                case ('E' << 24) | ('H' << 16) | ('L' << 8) | 'O':
                    return SmtpCommand.EHLO;
                case ('M' << 24) | ('A' << 16) | ('I' << 8) | 'L':
                    return SmtpCommand.MAIL;
                case ('R' << 24) | ('C' << 16) | ('P' << 8) | 'T':
                    return SmtpCommand.RCPT;
                case ('D' << 24) | ('A' << 16) | ('T' << 8) | 'A':
                    return SmtpCommand.DATA;
                case ('B' << 24) | ('D' << 16) | ('A' << 8) | 'T':
                    return SmtpCommand.BDAT;
                case ('R' << 24) | ('S' << 16) | ('E' << 8) | 'T':
                    return SmtpCommand.RSET;
                case ('Q' << 24) | ('U' << 16) | ('I' << 8) | 'T':
                    return SmtpCommand.QUIT;
                case ('N' << 24) | ('O' << 16) | ('O' << 8) | 'P':
                    return SmtpCommand.NOOP;
                case ('H' << 24) | ('E' << 16) | ('L' << 8) | 'P':
                    return SmtpCommand.HELP;
                case ('V' << 24) | ('R' << 16) | ('F' << 8) | 'Y':
                    return SmtpCommand.VRFY;
                case ('E' << 24) | ('X' << 16) | ('P' << 8) | 'N':
                    return SmtpCommand.EXPN;
                case ('A' << 24) | ('U' << 16) | ('T' << 8) | 'H':
                    return SmtpCommand.AUTH;
                case ('E' << 24) | ('T' << 16) | ('R' << 8) | 'N':
                    return SmtpCommand.ETRN;
                default:
                    return SmtpCommand.UNKNOWN;
            }
        }
        if (len == 7 && matchesLiteral(window, base, "XCLIENT")) {
            return SmtpCommand.XCLIENT;
        }
        if (len == 8 && matchesLiteral(window, base, "STARTTLS")) {
            return SmtpCommand.STARTTLS;
        }
        return SmtpCommand.UNKNOWN;
    }

    private static boolean matchesLiteral(ByteBuffer window, int base, String literal) {
        int len = literal.length();
        for (int i = 0; i < len; i++) {
            if (upperFold(window.get(base + i)) != literal.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int pack4(ByteBuffer window, int base) {
        int packed = 0;
        for (int i = 0; i < 4; i++) {
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

    // RFC 5321 §2.3.8 — a complete command/continuation line has been
    // lexed; the command was already resolved to an enum at the KEYWORD
    // token, so dispatch is a direct switch, not a re-parse of a string.
    // requestStop() is checked here, fresh, after dispatchCommand()
    // returns — not eagerly inside data()/bdat() — so that a BDAT with
    // chunkSize==0 (which synchronously completes and may revert state
    // back to RCPT within the same call) is correctly NOT stopped,
    // exactly replicating continueLineProcessing()'s per-line-fresh-check
    // semantics from the pre-conversion LineParser-based code.
    private void dispatchLine() {
        SmtpCommand command = pendingCommand;
        String unknownText = pendingUnknownText;
        String continuationText = pendingContinuationText;
        boolean hadArgs = pendingHasSp;
        String args = hadArgs ? argsBuilder.toString() : null;
        boolean sawNonAscii = pendingSawNonAscii;
        boolean useUtf8 = pendingUseUtf8;
        String error = lineErrorMessage;
        resetLineState();

        if (error != null) {
            reply(500, error);
            return;
        }

        if (authState != AuthState.NONE) {
            String rawLine = hadArgs
                    ? (continuationText + " " + args) : continuationText;
            handleAuthData(rawLine);
            return;
        }

        if (LOGGER.isLoggable(Level.FINEST)) {
            String msg = L10N.getString("log.smtp_command");
            msg = MessageFormat.format(msg, command.name(), args != null ? args : "");
            LOGGER.finest(msg);
        }

        dispatchCommand(command, unknownText, args);

        if (useUtf8 && command == SmtpCommand.MAIL && !smtputf8 && sawNonAscii) {
            resetTransaction();
            reply(553, L10N.getString("smtp.err.smtputf8_required"));
            return;
        }

        if (state == SmtpState.DATA || state == SmtpState.BDAT) {
            lexer.enterContentMode();
        }
    }

    // ── Transport helpers ──

    private void reply(int code, String message) {
        sendResponse(code, message);
    }

    private void replyMultiline(int code, String message) {
        String response = String.format("%d-%s\r\n", code, message);
        ByteBuffer buffer = ByteBuffer.wrap(response.getBytes(US_ASCII));
        endpoint.send(buffer);
    }

    private void sendResponse(int code, String message) {
        String response = String.format("%d %s\r\n", code, message);
        ByteBuffer buffer = ByteBuffer.wrap(response.getBytes(US_ASCII));
        endpoint.send(buffer);
    }

    private void closeEndpoint() {
        if (endpoint != null) {
            endpoint.close();
        }
    }

    private Realm getRealm() {
        if (realm == null) {
            Realm serverRealm = server.getRealm();
            if (serverRealm != null) {
                SelectorLoop loop = endpoint != null ? endpoint.getSelectorLoop() : null;
                if (loop != null) {
                    realm = serverRealm.forSelectorLoop(loop);
                } else {
                    realm = serverRealm;
                }
            }
        }
        return realm;
    }

    private SmtpServerMetrics getServerMetrics() {
        return server != null ? server.getMetrics() : null;
    }

    private void initConnectionTrace() {
        TelemetryConfig cfg = endpoint != null ? endpoint.getTelemetryConfig() : null;
        if (cfg != null) {
            String spanName = L10N.getString("telemetry.smtp_connection");
            Trace trace = cfg.createTrace(spanName, SpanKind.SERVER);
            if (trace != null) {
                endpoint.setTrace(trace);
                Span rootSpan = trace.getRootSpan();
                if (rootSpan != null && endpoint.getRemoteAddress() != null) {
                    rootSpan.addAttribute("net.transport", "ip_tcp");
                    rootSpan.addAttribute("net.peer.ip",
                            endpoint.getRemoteAddress().toString());
                    rootSpan.addAttribute("rpc.system", "smtp");
                }
            }
        }
    }

    private void startSessionSpan() {
        Trace trace = endpoint != null ? endpoint.getTrace() : null;
        if (trace == null) {
            return;
        }
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.end();
        }
        sessionNumber++;
        String spanName = MessageFormat.format(
                L10N.getString("telemetry.smtp_session"), sessionNumber);
        sessionSpan = trace.startSpan(spanName, SpanKind.SERVER);
        sessionSpan.addAttribute("smtp.session_number", sessionNumber);
    }

    private void endSessionSpan(String message) {
        if (sessionSpan == null || sessionSpan.isEnded()) {
            return;
        }
        if (message != null) {
            sessionSpan.addAttribute("smtp.result", message);
        }
        sessionSpan.setStatusOk();
        sessionSpan.end();
    }

    private void endSessionSpanError(String message) {
        if (sessionSpan == null || sessionSpan.isEnded()) {
            return;
        }
        sessionSpan.setStatusError(message);
        sessionSpan.end();
    }

    /**
     * The name this server gives itself in EHLO/HELO replies and SASL
     * challenges (RFC 5321 section 4.1.1.1). It is the domain the handler
     * put first in its 220 greeting, which RFC 5321 section 4.2 defines as
     * the server's domain. When the greeting names none, the local address
     * is given as an address literal (section 4.1.3).
     */
    private String localName() {
        if (greetingDomain != null) {
            return greetingDomain;
        }
        SocketAddress local = endpoint.getLocalAddress();
        if (local instanceof InetSocketAddress
                && ((InetSocketAddress) local).getAddress() != null) {
            String host = ((InetSocketAddress) local).getAddress().getHostAddress();
            int zone = host.indexOf('%');
            if (zone >= 0) {
                host = host.substring(0, zone);
            }
            return host.indexOf(':') >= 0 ? "[IPv6:" + host + "]" : "[" + host + "]";
        }
        return "localhost";
    }

    private static String greetingDomainOf(String greeting) {
        if (greeting == null) {
            return null;
        }
        int space = greeting.indexOf(' ');
        String first = space < 0 ? greeting : greeting.substring(0, space);
        if (first.isEmpty() || !first.matches("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?")) {
            return null;
        }
        return first;
    }

    private void sendGreeting() {
        if (connectedHandler != null) {
            connectedHandler.connected(this, endpoint);
        } else {
            startSessionSpan();
            reply(220, localName() + " ESMTP Service ready");
        }
    }

    // RFC 6531 §3.4 — a KEYWORD token that literally spells MAIL (any
    // case), tested purely on raw bytes: a 4-byte match here can never
    // itself contain non-ASCII, so no decode is needed to test this.
    private static boolean isMailKeyword(ByteBuffer window) {
        if (window.remaining() != 4) {
            return false;
        }
        int base = window.position();
        return pack4(window, base) == (('M' << 24) | ('A' << 16) | ('I' << 8) | 'L');
    }

    // RFC 5321 §4.5.1 — command dispatch and sequencing. `command` was
    // already resolved from the KEYWORD token's raw bytes (see
    // matchCommand()); SASL continuation routing happens earlier, in
    // dispatchLine(), before this is ever called.
    private void dispatchCommand(SmtpCommand command, String unknownText, String args) {
        if (state == SmtpState.REJECTED) {
            if (command == SmtpCommand.QUIT) {
                quit(args);
            } else {
                reply(554, L10N.getString("smtp.err.connection_rejected"));
            }
            return;
        }
        switch (command) {
            case HELO:
                helo(args);
                break;
            case EHLO:
                ehlo(args);
                break;
            case MAIL:
                mail(args);
                break;
            case RCPT:
                rcpt(args);
                break;
            case DATA:
                data(args);
                break;
            case BDAT:
                bdat(args);
                break;
            case RSET:
                rset(args);
                break;
            case QUIT:
                quit(args);
                break;
            case NOOP:
                noop(args);
                break;
            case HELP:
                help(args);
                break;
            case VRFY:
                vrfy(args);
                break;
            case EXPN:
                expn(args);
                break;
            case STARTTLS:
                if (!endpoint.isSecure() && server.isSTARTTLSAvailable() && !starttlsUsed) {
                    starttls(args);
                } else {
                    reply(500, MessageFormat.format(
                            L10N.getString("smtp.err.command_unrecognized"), command.name()));
                }
                break;
            case AUTH:
                auth(args);
                break;
            case XCLIENT:
                xclient(args);
                break;
            case ETRN:
                etrn(args);                                   // RFC 1985
                break;
            default:
                reply(500, MessageFormat.format(L10N.getString("smtp.err.command_unrecognized"),
                        unknownCommandText(command, unknownText)));
        }
    }

    // A command may reach the "unknown" branch either because it truly
    // didn't match any verb (unknownText holds its decoded text) or
    // because STARTTLS matched but its dispatch guard failed — in which
    // case the enum's own name is already the exact uppercased text, with
    // no decode needed.
    private static String unknownCommandText(SmtpCommand command, String unknownText) {
        return command == SmtpCommand.UNKNOWN ? unknownText : command.name();
    }

    private void handlePipelinedCommands(ByteBuffer buf) {
        lexer.feed(buf);
        if (buf.hasRemaining()) {
            if (state == SmtpState.BDAT) {
                handleBdatContent(buf);
            } else if (state == SmtpState.DATA) {
                handleDataContent(buf);
            }
        }
    }

    private boolean needsControlBuffering() {
        return dataState == DataState.SAW_CR || dataState == DataState.SAW_CRLF
                || dataState == DataState.SAW_DOT || dataState == DataState.SAW_DOT_CR;
    }

    private int getControlSequenceStart(int currentPos) {
        switch (dataState) {
            case SAW_CR:
                return currentPos - 1;
            case SAW_CRLF:
                return currentPos - 2;
            case SAW_DOT:
                return currentPos - 1;
            case SAW_DOT_CR:
                return currentPos - 2;
            default:
                return currentPos;
        }
    }

    private void appendToControlBuffer(ByteBuffer source) {
        if (controlBuffer.remaining() < source.remaining()) {
            int newCapacity = controlBuffer.capacity()
                    + Math.max(source.remaining(), MAX_CONTROL_BUFFER_SIZE);
            ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
            controlBuffer.flip();
            newBuffer.put(controlBuffer);
            controlBuffer = newBuffer;
        }
        controlBuffer.put(source);
    }

    private void messageContent(ByteBuffer messageBuffer) {
        if (messageHandler != null) {
            int originalPosition = messageBuffer.position();
            messageHandler.messageContent(messageBuffer);
            if (currentPipeline != null) {
                messageBuffer.position(originalPosition);
            }
        }
        if (currentPipeline != null && !dataTransferRejected) {
            try {
                WritableByteChannel ch = currentPipeline.getMessageChannel();
                if (ch != null) {
                    ch.write(messageBuffer);
                }
            } catch (IOException e) {
                Throwable cause = e.getCause();
                dataTransferRejected = true;
                dataTransferRejectionMessage = (cause instanceof HeaderLineTooLongException)
                    ? L10N.getString("smtp.err.header_line_too_long")
                    : (cause instanceof HeaderValueTooLongException)
                        ? L10N.getString("smtp.err.header_value_too_long")
                        : L10N.getString("smtp.err.syntax_error");
                events().warn("warn.error_writing_pipeline").thrown(e).emit();
            }
        }
    }

    /**
     * Checks whether the message data handler needs a read pause.
     * If so, pauses reading and provides the handler with a
     * resume callback.
     *
     * @return true if reading was paused
     */
    private boolean checkMessageHandlerBackPressure() {
        if (messageHandler == null) {
            return false;
        }
        if (!messageHandler.wantsPause()) {
            return false;
        }
        messageHandler.setResumeCallback(new DataResumeTask());
        endpoint.pauseRead();
        return true;
    }

    /**
     * Task to resume reading after a message data handler signals
     * readiness during DATA/BDAT transfer.
     */
    private class DataResumeTask implements Runnable {
        @Override
        public void run() {
            endpoint.resumeRead();
        }
    }

    private void sendChunk(ByteBuffer source, int start, int end) {
        if (end > start) {
            int savedPosition = source.position();
            int savedLimit = source.limit();
            int actualStart = Math.max(0, Math.min(start, source.limit()));
            int actualEnd = Math.max(actualStart, Math.min(end, source.limit()));
            dataBytesReceived += (actualEnd - actualStart);
            long maxSize = server.getMaxMessageSize();
            if (maxSize > 0 && dataBytesReceived > maxSize) {
                sizeExceeded = true;
                source.limit(savedLimit);
                source.position(Math.min(savedPosition, source.limit()));
                return;
            }
            source.position(actualStart);
            source.limit(actualEnd);
            ByteBuffer chunk = source.slice();
            source.limit(savedLimit);
            source.position(Math.min(savedPosition, source.limit()));
            messageContent(chunk);
        }
    }

    /**
     * Merges the saved control bytes with newly arrived data. The saved
     * bytes are re-scanned from the state that preceded them (see
     * {@link #processDataBuffer}), so the combined buffer is returned for
     * normal processing; the caller then also sees any pipelined bytes
     * that follow the end-of-data marker.
     */
    private ByteBuffer mergeControlSequence(ByteBuffer newData) {
        appendToControlBuffer(newData);
        controlBuffer.flip();
        ByteBuffer merged = ByteBuffer.allocate(controlBuffer.remaining());
        merged.put(controlBuffer);
        merged.flip();
        controlBuffer.clear();
        return merged;
    }

    private void saveControlSequence(ByteBuffer source, int start) {
        controlBuffer.clear();
        int savedPosition = source.position();
        int safeStart = Math.max(0, Math.min(start, source.limit()));
        source.position(safeStart);
        int bytesToCopy = 0;
        while (source.hasRemaining() && controlBuffer.hasRemaining()
                && bytesToCopy < MAX_CONTROL_BUFFER_SIZE) {
            controlBuffer.put(source.get());
            bytesToCopy++;
        }
        source.position(Math.max(0, Math.min(savedPosition, source.limit())));
    }

    private void resetDataState() {
        controlBuffer.clear();
        dataState = DataState.NORMAL;
        dataBytesReceived = 0L;
        sizeExceeded = false;
        dataTransferRejected = false;
        dataTransferRejectionMessage = null;
        resetBdatState();
    }

    private void resetBdatState() {
        bdatBytesRemaining = 0L;
        bdatLast = false;
        bdatStarted = false;
    }

    /**
     * Marks the start of an asynchronous message delivery. Called immediately
     * before {@link MessageDataHandler#messageComplete}. If the handler
     * completes synchronously (calls acceptMessageDelivery/rejectMessage*
     * before returning) the flag is cleared inside that call; otherwise
     * subsequent input is retained until the async reply arrives.
     */
    private void beginDelivery() {
        deliveryPending = true;
    }

    /**
     * Called from the delivery reply methods once the async (or synchronous)
     * delivery has produced its response. Clears the pending flag and
     * re-drives any input retained while the delivery was in flight.
     */
    private void endDelivery() {
        if (!deliveryPending) {
            return;
        }
        deliveryPending = false;
        ByteBuffer pending = retainedInput;
        retainedInput = null;
        if (pending != null && pending.hasRemaining()) {
            receive(pending);
        }
    }

    /**
     * Appends the remaining bytes of {@code buf} to the retained-input buffer
     * so they can be re-driven once an in-flight async delivery completes.
     */
    private void retainInput(ByteBuffer buf) {
        if (buf == null || !buf.hasRemaining()) {
            return;
        }
        if (retainedInput == null || !retainedInput.hasRemaining()) {
            ByteBuffer copy = ByteBuffer.allocate(buf.remaining());
            copy.put(buf);
            copy.flip();
            retainedInput = copy;
        } else {
            ByteBuffer merged = ByteBuffer.allocate(
                    retainedInput.remaining() + buf.remaining());
            merged.put(retainedInput);
            merged.put(buf);
            merged.flip();
            retainedInput = merged;
        }
    }

    private void handleDataContent(ByteBuffer buf) {
        if (controlBuffer.position() > 0) {
            ByteBuffer merged = mergeControlSequence(buf);
            handleDataBytes(merged);
            // Whatever was left unconsumed is a suffix of the new data;
            // leave it in the caller's buffer.
            int unconsumed = Math.min(merged.remaining(), buf.limit());
            buf.position(buf.limit() - unconsumed);
            return;
        }
        handleDataBytes(buf);
    }

    private void handleDataBytes(ByteBuffer buf) {
        processDataBuffer(buf);
        if (deliveryPending) {
            retainInput(buf);
            return;
        }
        if (state != SmtpState.DATA && buf.hasRemaining()) {
            handlePipelinedCommands(buf);
        }
    }

    /** RFC 5321 §4.5.2 — DATA content dot-unstuffing state machine. */
    private void processDataBuffer(ByteBuffer buf) {
        int chunkStart = buf.position();
        while (buf.hasRemaining()) {
            int currentPos = buf.position();
            byte b = buf.get();
            switch (dataState) {
                case NORMAL:
                    if (b == '\r') {
                        dataState = DataState.SAW_CR;
                    }
                    break;
                case SAW_CR:
                    if (b == '\n') {
                        dataState = DataState.SAW_CRLF;
                    }
                    break;
                case SAW_CRLF:
                    if (b == '.') {
                        if (currentPos > chunkStart) {
                            sendChunk(buf, chunkStart, currentPos);
                        }
                        dataState = DataState.SAW_DOT;
                        chunkStart = buf.position();
                    } else {
                        dataState = DataState.NORMAL;
                        if (b == '\r') {
                            dataState = DataState.SAW_CR;
                        }
                    }
                    break;
                case SAW_DOT:
                    if (b == '\r') {
                        dataState = DataState.SAW_DOT_CR;
                    } else {
                        dataState = DataState.NORMAL;
                    }
                    break;
                case SAW_DOT_CR:
                    if (b == '\n') {
                        long messageSize = dataBytesReceived;
                        int recipientCount = recipients != null ? recipients.size() : 0;
                        boolean exceeded = sizeExceeded;
                        boolean rejected = dataTransferRejected;
                        String rejectionMsg = dataTransferRejectionMessage;
                        long maxSize = server.getMaxMessageSize();
                        resetDataState();
                        state = SmtpState.READY;
                        if (exceeded) {
                            addSessionEvent("DATA rejected (size exceeded)");
                            reply(552, "5.3.4 Message size exceeds maximum (" + maxSize + " bytes)");
                            return;
                        }
                        if (rejected) {
                            addSessionEvent("DATA rejected (parse error)");
                            reply(554, rejectionMsg);
                            return;
                        }
                        addSessionEvent("DATA complete");
                        SmtpServerMetrics metrics = getServerMetrics();
                        if (metrics != null) {
                            metrics.messageReceived(messageSize, recipientCount);
                        }
                        if (currentPipeline != null) {
                            currentPipeline.endData();
                        }
                        if (messageHandler != null) {
                            beginDelivery();
                            messageHandler.messageComplete(this);
                            return;
                        }
                        reply(250, "2.0.0 Message accepted for delivery");
                        return;
                    } else {
                        dataState = DataState.NORMAL;
                        if (b == '\r') {
                            dataState = DataState.SAW_CR;
                        }
                    }
                    break;
            }
        }
        if (needsControlBuffering()) {
            int controlStart = getControlSequenceStart(buf.position());
            if (controlStart > chunkStart) {
                sendChunk(buf, chunkStart, controlStart);
            }
            saveControlSequence(buf, controlStart);
            // The saved bytes will be scanned again with the next read, so
            // restore the state they were first scanned from: a saved dot
            // (or dot and CR) follows a line break, a saved CR or CRLF
            // follows ordinary content.
            if (dataState == DataState.SAW_DOT || dataState == DataState.SAW_DOT_CR) {
                dataState = DataState.SAW_CRLF;
            } else {
                dataState = DataState.NORMAL;
            }
        } else {
            if (buf.position() > chunkStart) {
                sendChunk(buf, chunkStart, buf.position());
            }
        }
        checkMessageHandlerBackPressure();
    }

    /** RFC 3030 §3 — BDAT chunk content processing. */
    private void handleBdatContent(ByteBuffer buf) {
        while (buf.hasRemaining() && bdatBytesRemaining > 0) {
            int available = buf.remaining();
            int toProcess = (int) Math.min(available, bdatBytesRemaining);
            int startPos = buf.position();
            int oldLimit = buf.limit();
            buf.limit(startPos + toProcess);
            messageContent(buf);
            dataBytesReceived += toProcess;
            bdatBytesRemaining -= toProcess;
            buf.limit(oldLimit);
            buf.position(startPos + toProcess);

            if (bdatBytesRemaining > 0
                    && checkMessageHandlerBackPressure()) {
                return;
            }
        }
        if (bdatBytesRemaining == 0) {
            handleBdatChunkComplete();
            if (deliveryPending) {
                retainInput(buf);
                return;
            }
            if (buf.hasRemaining() && state != SmtpState.BDAT) {
                handlePipelinedCommands(buf);
            }
        }
    }

    private void handleBdatChunkComplete() {
        if (bdatLast) {
            long messageSize = dataBytesReceived;
            int recipientCount = recipients != null ? recipients.size() : 0;
            addSessionEvent("BDAT LAST complete");
            if (dataTransferRejected) {
                resetDataState();
                state = SmtpState.READY;
                reply(554, dataTransferRejectionMessage);
                return;
            }
            SmtpServerMetrics metrics = getServerMetrics();
            if (metrics != null) {
                metrics.messageReceived(messageSize, recipientCount);
            }
            if (currentPipeline != null) {
                currentPipeline.endData();
            }
            if (messageHandler != null) {
                beginDelivery();
                messageHandler.messageComplete(this);
                return;
            }
            resetDataState();
            state = SmtpState.READY;
            reply(250, "2.0.0 Message accepted for delivery (" + messageSize + " bytes)");
        } else {
            state = SmtpState.RCPT;
            reply(250, "2.0.0 " + dataBytesReceived + " bytes received");
        }
    }

    private void addSessionEvent(String name) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addEvent(name);
        }
    }

    private void addSessionAttribute(String key, String value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    private void addSessionAttribute(String key, long value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    private void addSessionAttribute(String key, boolean value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    private boolean isXclientAuthorized() {
        if (endpoint == null) {
            return false;
        }
        try {
            InetSocketAddress clientAddr =
                    (InetSocketAddress) endpoint.getRemoteAddress();
            if (clientAddr != null && server != null) {
                return server.isXclientAuthorized(clientAddr.getAddress());
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    /** RFC 5321 §4.1.1.1 — HELO command. */
    private void helo(String hostname) {
        if (hostname == null || hostname.trim().isEmpty()) {
            reply(501, "5.0.0 Syntax: HELO hostname");
            return;
        }
        this.heloName = hostname.trim();
        this.extendedSMTP = false;
        addSessionAttribute("smtp.helo", this.heloName);
        addSessionEvent("HELO");
        if (helloHandler != null) {
            helloHandler.hello(this, false, this.heloName);
        } else {
            this.state = SmtpState.READY;
            String localHostname = localName();
            reply(250, localHostname + " Hello " + hostname);
        }
    }

    /** RFC 5321 §4.1.1.1 — EHLO command with extension negotiation. */
    private void ehlo(String hostname) {
        if (hostname == null || hostname.trim().isEmpty()) {
            reply(501, "5.0.0 Syntax: EHLO hostname");
            return;
        }
        this.heloName = hostname.trim();
        this.extendedSMTP = true;
        addSessionAttribute("smtp.ehlo", this.heloName);
        addSessionAttribute("smtp.esmtp", true);
        addSessionEvent("EHLO");
        if (helloHandler != null) {
            helloHandler.hello(this, true, this.heloName);
        } else {
            this.state = SmtpState.READY;
            sendEhloResponse();
        }
    }

    /**
     * RFC 5321 §4.1.1.1 — EHLO 250 multi-line response advertising extensions.
     * Each keyword references its defining RFC.
     */
    private void sendEhloResponse() {
        String localHostname = localName();
        replyMultiline(250, localHostname + " Hello " + heloName);
        replyMultiline(250, "SIZE " + server.getMaxMessageSize());     // RFC 1870
        replyMultiline(250, "PIPELINING");                              // RFC 2920
        replyMultiline(250, "8BITMIME");                                // RFC 6152
        replyMultiline(250, "SMTPUTF8");                                // RFC 6531
        replyMultiline(250, "ENHANCEDSTATUSCODES");                     // RFC 2034
        replyMultiline(250, "CHUNKING");                                // RFC 3030
        replyMultiline(250, "BINARYMIME");                              // RFC 3030
        replyMultiline(250, "DSN");                                     // RFC 3461
        StringBuilder limits = new StringBuilder("LIMITS");
        int maxRecipients = server.getMaxRecipients();
        if (maxRecipients > 0) {
            limits.append(" RCPTMAX=").append(maxRecipients);
        }
        int maxTransactions = server.getMaxTransactionsPerSession();
        if (maxTransactions > 0) {
            limits.append(" MAILMAX=").append(maxTransactions);
        }
        replyMultiline(250, limits.toString());                       // RFC 9422
        if (endpoint.isSecure()) {
            replyMultiline(250, "REQUIRETLS");                          // RFC 8689
        }
        replyMultiline(250, "MT-PRIORITY MIXER STANAG4406 NSEP");       // RFC 6710
        replyMultiline(250, "FUTURERELEASE 604800 2012-01-01T00:00:00Z"); // RFC 4865
        replyMultiline(250, "DELIVERBY 604800");                        // RFC 2852
        if (isXclientAuthorized()) {
            replyMultiline(250, "XCLIENT NAME ADDR PORT PROTO HELO LOGIN DESTADDR DESTPORT");
        }
        if (!endpoint.isSecure() && server.isSTARTTLSAvailable()) {
            replyMultiline(250, "STARTTLS");
        }
        Realm r = getRealm();
        if (r != null && (endpoint.isSecure() || server.isSTARTTLSAvailable())) {
            Set<SaslMechanism> supported = r.getSupportedSASLMechanisms();
            if (!supported.isEmpty() || server.getGSSAPIServer() != null) {
                StringBuilder authLine = new StringBuilder("AUTH");
                for (SaslMechanism mech : supported) {
                    if (!endpoint.isSecure() && mech.requiresTLS()) {
                        continue;
                    }
                    if (mech == SaslMechanism.EXTERNAL && !endpoint.isSecure()) {
                        continue;
                    }
                    authLine.append(" ").append(mech.getMechanismName());
                }
                // RFC 4752 — advertise GSSAPI when configured
                if (server.getGSSAPIServer() != null) {
                    authLine.append(" GSSAPI");
                }
                replyMultiline(250, authLine.toString());
            }
        }
        sendResponse(250, "HELP");
    }

    /** RFC 3207 §4 — STARTTLS command. */
    private void starttls(String args) {
        if (endpoint.isSecure()) {
            reply(454, "4.7.0 TLS already active");
            return;
        }
        if (!server.isSTARTTLSAvailable()) {
            reply(454, "4.7.0 TLS not available");
            return;
        }
        if (state != SmtpState.INITIAL && state != SmtpState.READY) {
            reply(503, "5.0.0 Bad sequence of commands");
            return;
        }
        doStarttls();
    }

    /** RFC 3207 §4.2 — 220 response, start TLS, reset SMTP state. */
    private void doStarttls() {
        try {
            reply(220, "2.0.0 Ready to start TLS");
            endpoint.startTLS();
            state = SmtpState.INITIAL;
            heloName = null;
            extendedSMTP = false;
            starttlsUsed = true;
            addSessionAttribute("smtp.starttls", true);
            addSessionEvent("STARTTLS");
            SmtpServerMetrics metrics = getServerMetrics();
            if (metrics != null) {
                metrics.starttlsUpgraded();
            }
        } catch (Exception e) {
            reply(454, "4.3.0 TLS not available due to temporary reason");
            events().warn("warn.starttls_failed").thrown(e).emit();
        }
    }

    /**
     * RFC 4954 — AUTH command.
     * Supported mechanisms: PLAIN (RFC 4616), LOGIN,
     * SCRAM-SHA-256 (RFC 5802/7677), OAUTHBEARER (RFC 7628),
     * EXTERNAL (RFC 4422).
     */
    private void auth(String args) {
        if (getRealm() == null) {
            reply(502, "5.5.1 Authentication not available");
            return;
        }
        if (!extendedSMTP) {
            reply(503, "5.0.0 AUTH requires EHLO");
            return;
        }
        if (authenticated) {
            reply(503, "5.0.0 Already authenticated");
            return;
        }
        if (server.isAuthLockedOut(endpoint.getRemoteAddress())) {
            reply(454, "4.7.0 Too many failed authentication attempts, try again later");
            return;
        }
        if (!endpoint.isSecure() && !server.isSTARTTLSAvailable()) {
            reply(538, "5.7.11 Encryption required for requested authentication mechanism");
            return;
        }
        if (args == null || args.trim().isEmpty()) {
            reply(501, "5.0.0 Syntax: AUTH mechanism [initial-response]");
            return;
        }
        String trimmedArgs = args.trim();
        String mechanism;
        String initialResponse = null;
        int spaceIdx = -1;
        for (int i = 0; i < trimmedArgs.length(); i++) {
            char c = trimmedArgs.charAt(i);
            if (c == ' ' || c == '\t') {
                spaceIdx = i;
                break;
            }
        }
        if (spaceIdx > 0) {
            mechanism = trimmedArgs.substring(0, spaceIdx).toUpperCase(Locale.ENGLISH);
            initialResponse = trimmedArgs.substring(spaceIdx + 1).trim();
            if (initialResponse.isEmpty()) {
                initialResponse = null;
            }
        } else {
            mechanism = trimmedArgs.toUpperCase(Locale.ENGLISH);
        }
        if ("PLAIN".equals(mechanism)) {
            handleAuthPlain(initialResponse);
        } else if ("LOGIN".equals(mechanism)) {
            handleAuthLogin(initialResponse);
        } else if ("EXTERNAL".equals(mechanism)) {
            handleAuthExternal(initialResponse);
        } else if ("SCRAM-SHA-256".equals(mechanism)) {
            handleAuthScramSHA256(initialResponse);       // RFC 5802, RFC 7677
        } else if ("OAUTHBEARER".equals(mechanism)) {
            handleAuthOAuthBearer(initialResponse);       // RFC 7628
        } else if ("GSSAPI".equals(mechanism)) {
            handleAuthGSSAPI(initialResponse);            // RFC 4752
        } else {
            reply(504, "5.5.4 Authentication mechanism not supported");
        }
    }

    /** RFC 4616 — SASL PLAIN mechanism (authzid NUL authcid NUL password). */
    private void handleAuthPlain(String initialResponse) {
        try {
            String credentials;
            if (initialResponse != null && !initialResponse.equals("=")) {
                credentials = initialResponse;
            } else {
                reply(334, "");
                authState = AuthState.PLAIN_RESPONSE;
                authMechanism = "PLAIN";
                return;
            }
            byte[] decoded = Base64.getDecoder().decode(credentials);
            String authString = new String(decoded, US_ASCII);
            int firstNull = authString.indexOf('\0');
            int secondNull = (firstNull >= 0) ? authString.indexOf('\0', firstNull + 1) : -1;
            if (firstNull < 0 || secondNull < 0
                    || authString.indexOf('\0', secondNull + 1) >= 0) {
                rejectCredentials();
                resetAuthState();
                return;
            }
            final String username = authString.substring(firstNull + 1, secondNull);
            String password = authString.substring(secondNull + 1);
            if (username.isEmpty() || password.isEmpty()) {
                rejectCredentials();
                resetAuthState();
                return;
            }
            authenticateUserAsync(username, password, new StorageExecutor.Callback<Boolean>() {
                @Override
                public void completed(Boolean authenticated) {
                    if (authenticated) {
                        notifyAuthenticationSuccess(username, "PLAIN");
                    } else {
                        notifyAuthenticationFailure(username, "PLAIN");
                    }
                    resetAuthState();
                }

                @Override
                public void failed(Throwable t) {
                    events().warn("warn.auth_plain_check_failed").thrown(t).emit();
                    notifyAuthenticationFailure(username, "PLAIN");
                    resetAuthState();
                }
            });
        } catch (Exception e) {
            rejectCredentials();
            resetAuthState();
            events().warn("warn.auth_plain_error").thrown(e).emit();
        }
    }

    /** RFC 4422 — SASL EXTERNAL mechanism using TLS client certificate. */
    private void handleAuthExternal(String initialResponse) {
        String authzid = null;
        if (initialResponse != null && !initialResponse.isEmpty()
                && !initialResponse.equals("=")) {
            try {
                byte[] decoded = Base64.getDecoder().decode(initialResponse);
                String authzidParam = new String(decoded, US_ASCII);
                if (!authzidParam.isEmpty()) {
                    authzid = authzidParam;
                }
            } catch (IllegalArgumentException e) {
                reply(501, "5.5.2 Invalid BASE64 encoding");
                return;
            }
        }

        final String requestedAuthzid = authzid;
        SaslUtils.authenticateExternal(endpoint, getRealm(), authzid,
                new RealmCallback<Realm.CertificateAuthenticationResult>() {
            @Override
            public void completed(Realm.CertificateAuthenticationResult result) {
                if (result == null || !result.valid) {
                    notifyAuthenticationFailure(requestedAuthzid, "EXTERNAL");
                    return;
                }
                notifyAuthenticationSuccess(result.username, "EXTERNAL");
                resetAuthState();
            }

            @Override
            public void failed(Throwable cause) {
                events().warn("warn.auth_external_error").thrown(cause).emit();
                notifyAuthenticationFailure(requestedAuthzid, "EXTERNAL");
            }
        });
    }

    /** draft-murchison-sasl-login — SASL LOGIN mechanism (username/password). */
    private void handleAuthLogin(String initialResponse) {
        try {
            if (initialResponse != null && !initialResponse.equals("=")) {
                byte[] decoded = Base64.getDecoder().decode(initialResponse);
                String username = new String(decoded, US_ASCII);
                if (username.isEmpty()) {
                    rejectCredentials();
                    resetAuthState();
                    return;
                }
                pendingAuthUsername = username;
                authState = AuthState.LOGIN_PASSWORD;
                authMechanism = "LOGIN";
                String passwordPrompt = Base64.getEncoder()
                        .encodeToString("Password:".getBytes(US_ASCII));
                reply(334, passwordPrompt);
            } else {
                authState = AuthState.LOGIN_USERNAME;
                authMechanism = "LOGIN";
                String usernamePrompt = Base64.getEncoder()
                        .encodeToString("Username:".getBytes(US_ASCII));
                reply(334, usernamePrompt);
            }
        } catch (Exception e) {
            rejectCredentials();
            resetAuthState();
            events().warn("warn.auth_login_error").thrown(e).emit();
        }
    }

    /**
     * RFC 5802 / RFC 7677 — SCRAM-SHA-256 mechanism.
     * Multi-round: client-first → server-first → client-final → server-final.
     */
    private void handleAuthScramSHA256(String initialResponse) {
        try {
            if (initialResponse != null && !initialResponse.equals("=")) {
                processScramClientFirst(initialResponse);
            } else {
                reply(334, "");
                authState = AuthState.SCRAM_INITIAL;
                authMechanism = "SCRAM-SHA-256";
            }
        } catch (Exception e) {
            rejectCredentials();
            resetAuthState();
            events().warn("warn.auth_scram_sha256_error").thrown(e).emit();
        }
    }

    /** RFC 5802 §5 — parse the SCRAM client-first-message and reply with server-first. */
    private void processScramClientFirst(String encoded) {
        final String clientFirstBare;
        final String username;
        final String clientNonce;
        try {
            String clientFirst = new String(Base64.getDecoder().decode(encoded), UTF_8);
            // client-first-message: gs2-header "," authcid "," nonce
            // e.g. n,,n=user,r=clientnonce
            int commaCount = 0;
            int bareStart = 0;
            for (int i = 0; i < clientFirst.length(); i++) {
                if (clientFirst.charAt(i) == ',') {
                    commaCount++;
                    if (commaCount == 2) {
                        bareStart = i + 1;
                        break;
                    }
                }
            }
            clientFirstBare = clientFirst.substring(bareStart);
            String usernameParsed = null;
            String clientNonceParsed = null;
            for (String attr : clientFirstBare.split(",")) {
                if (attr.startsWith("n=")) {
                    usernameParsed = attr.substring(2);
                } else if (attr.startsWith("r=")) {
                    clientNonceParsed = attr.substring(2);
                }
            }
            if (usernameParsed == null || clientNonceParsed == null) {
                rejectCredentials();
                resetAuthState();
                return;
            }
            username = usernameParsed;
            clientNonce = clientNonceParsed;
            if (getRealm() == null) {
                rejectCredentials();
                resetAuthState();
                return;
            }
        } catch (Exception e) {
            rejectCredentials();
            resetAuthState();
            events().warn("warn.auth_scram_sha256_error").thrown(e).emit();
            return;
        }

        getScramCredentialsAsync(username, new StorageExecutor.Callback<Realm.ScramCredentials>() {
            @Override
            public void completed(Realm.ScramCredentials creds) {
                if (creds == null) {
                    rejectCredentials();
                    resetAuthState();
                    return;
                }
                pendingAuthUsername = username;
                authClientNonce = clientNonce;
                String serverNonce = clientNonce + SaslUtils.generateNonce(16);
                authNonce = serverNonce;
                authSalt = Base64.getDecoder().decode(creds.salt);
                authIterations = creds.iterations;

                String serverFirst = SaslUtils.generateScramServerFirst(
                        serverNonce, creds.salt, creds.iterations);
                // Store the auth message for later verification:
                // clientFirstBare + "," + serverFirst
                authChallenge = clientFirstBare + "," + serverFirst;
                authServerSignature = null; // computed in final step

                String serverFirstEncoded = Base64.getEncoder()
                        .encodeToString(serverFirst.getBytes(UTF_8));
                reply(334, serverFirstEncoded);
                authState = AuthState.SCRAM_FINAL;
                authMechanism = "SCRAM-SHA-256";
            }

            @Override
            public void failed(Throwable error) {
                rejectCredentials();
                resetAuthState();
                events().warn("warn.auth_scram_sha256_error").thrown(error).emit();
            }
        });
    }

    /** RFC 5802 §5 — process SCRAM client-final-message and verify proof. */
    private void processScramClientFinal(String encoded) {
        final String clientFinal;
        try {
            clientFinal = new String(Base64.getDecoder().decode(encoded), UTF_8);
            if (getRealm() == null) {
                rejectCredentials();
                resetAuthState();
                return;
            }
        } catch (Exception e) {
            rejectCredentials();
            resetAuthState();
            events().warn("warn.auth_scram_sha256_error").thrown(e).emit();
            return;
        }

        final String scramUser = pendingAuthUsername;
        getScramCredentialsAsync(scramUser, new StorageExecutor.Callback<Realm.ScramCredentials>() {
            @Override
            public void completed(Realm.ScramCredentials creds) {
                if (creds == null) {
                    rejectCredentials();
                    resetAuthState();
                    return;
                }
                byte[] serverSignature = SaslUtils.verifyScramClientFinal(creds,
                        authChallenge, clientFinal, authNonce);
                if (serverSignature == null) {
                    notifyAuthenticationFailure(scramUser, "SCRAM-SHA-256");
                    resetAuthState();
                    return;
                }
                String serverFinal = "v=" + Base64.getEncoder().encodeToString(serverSignature);
                notifyAuthenticationSuccess(scramUser, "SCRAM-SHA-256");
                // RFC 5802 §5: server-final appended to the 235 response
                reply(235, "2.7.0 " + Base64.getEncoder().encodeToString(serverFinal.getBytes(UTF_8)));
                resetAuthState();
            }

            @Override
            public void failed(Throwable error) {
                rejectCredentials();
                resetAuthState();
                events().warn("warn.auth_scram_sha256_error").thrown(error).emit();
            }
        });
    }

    /**
     * RFC 7628 — OAUTHBEARER mechanism.
     * Client sends a single message containing the Bearer token.
     */
    private void handleAuthOAuthBearer(String initialResponse) {
        try {
            if (initialResponse != null && !initialResponse.equals("=")) {
                processOAuthBearerResponse(initialResponse);
            } else {
                reply(334, "");
                authState = AuthState.OAUTH_RESPONSE;
                authMechanism = "OAUTHBEARER";
            }
        } catch (Exception e) {
            rejectCredentials();
            resetAuthState();
            events().warn("warn.auth_oauthbearer_error").thrown(e).emit();
        }
    }

    /** RFC 7628 §3.1 — validate the OAUTHBEARER initial client response. */
    private void processOAuthBearerResponse(String encoded) {
        String decoded = new String(Base64.getDecoder().decode(encoded), UTF_8);
        Map<String, String> oauthParams = SaslUtils.parseOAuthBearerCredentials(decoded);
        String token = oauthParams.get("token");
        String user = oauthParams.get("user");
        if (token == null || token.isEmpty()) {
            rejectCredentials();
            resetAuthState();
            return;
        }
        final String expectedUser = user;
        getRealm().validateBearerToken(token,
                awaiting(new StorageExecutor.Callback<Realm.TokenValidationResult>() {
            @Override
            public void completed(Realm.TokenValidationResult result) {
                if (result == null || !result.valid || result.isExpired()) {
                    // RFC 7628 §3.2.2 — server sends JSON error on failure
                    String errorJson = "{\"status\":\"invalid_token\"}";
                    String errorEncoded = Base64.getEncoder()
                            .encodeToString(errorJson.getBytes(UTF_8));
                    reply(334, errorEncoded);
                    // Client must send empty response (^A) to acknowledge, then 535
                    authState = AuthState.OAUTH_RESPONSE;
                    authMechanism = "OAUTHBEARER";
                    return;
                }
                if (expectedUser != null && !expectedUser.isEmpty()
                        && !expectedUser.equals(result.username)) {
                    rejectCredentials();
                    resetAuthState();
                    return;
                }
                notifyAuthenticationSuccess(result.username, "OAUTHBEARER");
                resetAuthState();
            }

            @Override
            public void failed(Throwable cause) {
                events().warn("warn.auth_oauthbearer_error").thrown(cause).emit();
                rejectCredentials();
                resetAuthState();
            }
        }));
    }

    /** RFC 4752 — SASL GSSAPI mechanism (Kerberos V5). */
    private void handleAuthGSSAPI(String initialResponse) {
        GssapiServer gssapiServer = server.getGSSAPIServer();
        if (gssapiServer == null) {
            reply(504, "5.5.4 Authentication mechanism not supported");
            return;
        }
        try {
            gssapiExchange = gssapiServer.createExchange();
        } catch (IOException e) {
            events().warn("warn.gssapi_exchange_creation_failed").thrown(e).emit();
            reply(454, "4.7.0 Temporary authentication failure");
            return;
        }
        authState = AuthState.GSSAPI_EXCHANGE;
        authMechanism = "GSSAPI";
        if (initialResponse != null && !initialResponse.equals("=")) {
            processGSSAPIToken(initialResponse);
        } else {
            reply(334, "");
        }
    }

    /** RFC 4752 §3.1 — processes a GSSAPI token exchange step. */
    private void processGSSAPIToken(String line) {
        try {
            byte[] clientToken = Base64.getDecoder().decode(line);
            byte[] responseToken = gssapiExchange.acceptToken(clientToken);

            if (gssapiExchange.isContextEstablished()) {
                byte[] challenge =
                        gssapiExchange.generateSecurityLayerChallenge();
                String encoded = Base64.getEncoder()
                        .encodeToString(challenge);
                reply(334, encoded);
                return;
            }

            if (responseToken != null && responseToken.length > 0) {
                String encoded = Base64.getEncoder()
                        .encodeToString(responseToken);
                reply(334, encoded);
            } else {
                reply(334, "");
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, L10N.getString("debug.gssapi_token_rejected"), e);
            rejectCredentials();
            resetAuthState();
        } catch (IllegalArgumentException e) {
            rejectCredentials();
            resetAuthState();
        }
    }

    /** RFC 4752 §3.1 para 7-8 — processes the security layer response. */
    private void processGSSAPISecurityLayer(String line) {
        try {
            byte[] wrapped = Base64.getDecoder().decode(line);
            String gssName =
                    gssapiExchange.validateSecurityLayerResponse(wrapped);
            final String principal = gssName;
            Realm realm = getRealm();
            if (realm == null) {
                gssapiAuthenticated(null, principal);
                return;
            }
            realm.mapKerberosPrincipal(gssName,
                    awaiting(new StorageExecutor.Callback<String>() {
                @Override
                public void completed(String localUser) {
                    gssapiAuthenticated(localUser, principal);
                }

                @Override
                public void failed(Throwable cause) {
                    LOGGER.log(Level.FINE, L10N.getString("debug.gssapi_security_layer_failed"), cause);
                    rejectCredentials();
                    resetAuthState();
                }
            }));
        } catch (IOException e) {
            LOGGER.log(Level.FINE, L10N.getString("debug.gssapi_security_layer_failed"), e);
            rejectCredentials();
            resetAuthState();
        } catch (IllegalArgumentException e) {
            rejectCredentials();
            resetAuthState();
        }
    }

    /** GSSAPI succeeded; {@code localUser} is the realm's mapping, or null to strip the Kerberos realm. */
    private void gssapiAuthenticated(String localUser, String gssName) {
        if (localUser == null) {
            localUser = gssName;
            int atIndex = localUser.indexOf('@');
            if (atIndex > 0) {
                localUser = localUser.substring(0, atIndex);
            }
        }
        notifyAuthenticationSuccess(localUser, "GSSAPI");
        resetAuthState();
    }

    /**
     * RFC 4954 §4 — process AUTH continuation data.
     * If the client sends "*", the exchange is aborted (501).
     */
    private void handleAuthData(String data) {
        // RFC 4954 §4 — "*" aborts the authentication exchange
        if ("*".equals(data)) {
            reply(501, "5.0.0 Authentication aborted");
            resetAuthState();
            return;
        }
        try {
            switch (authState) {
                case PLAIN_RESPONSE:
                    handleAuthPlain(data);
                    break;
                case LOGIN_USERNAME: {
                    byte[] decoded = Base64.getDecoder().decode(data);
                    String username = new String(decoded, US_ASCII);
                    if (username.isEmpty()) {
                        rejectCredentials();
                        resetAuthState();
                        return;
                    }
                    pendingAuthUsername = username;
                    authState = AuthState.LOGIN_PASSWORD;
                    String passwordPrompt = Base64.getEncoder()
                            .encodeToString("Password:".getBytes(US_ASCII));
                    reply(334, passwordPrompt);
                    break;
                }
                case LOGIN_PASSWORD: {
                    byte[] decoded = Base64.getDecoder().decode(data);
                    String password = new String(decoded, US_ASCII);
                    if (password.isEmpty()) {
                        rejectCredentials();
                        resetAuthState();
                        return;
                    }
                    final String loginUsername = pendingAuthUsername;
                    authenticateUserAsync(loginUsername, password,
                            new StorageExecutor.Callback<Boolean>() {
                        @Override
                        public void completed(Boolean authenticated0) {
                            if (authenticated0) {
                                authenticatedUser = loginUsername;
                                authMechanism = "LOGIN";
                                if (helloHandler != null) {
                                    Principal principal = new Principal() {
                                        @Override
                                        public String getName() {
                                            return loginUsername;
                                        }
                                        @Override
                                        public String toString() {
                                            return loginUsername;
                                        }
                                    };
                                    helloHandler.authenticated(
                                            SmtpProtocolHandler.this, principal);
                                } else {
                                    authenticated = true;
                                    recordAuthenticationSuccess(loginUsername, "LOGIN");
                                    reply(235, "2.7.0 Authentication successful");
                                }
                            } else {
                                notifyAuthenticationFailure(loginUsername, "LOGIN");
                            }
                            resetAuthState();
                        }

                        @Override
                        public void failed(Throwable t) {
                            events().warn("warn.auth_login_check_failed").thrown(t).emit();
                            notifyAuthenticationFailure(loginUsername, "LOGIN");
                            resetAuthState();
                        }
                    });
                    break;
                }
                case SCRAM_INITIAL:
                    processScramClientFirst(data);
                    break;
                case SCRAM_FINAL:
                    processScramClientFinal(data);
                    break;
                case OAUTH_RESPONSE:
                    handleOAuthDataResponse(data);
                    break;
                case GSSAPI_EXCHANGE:
                    if (gssapiExchange != null
                            && gssapiExchange.isContextEstablished()) {
                        processGSSAPISecurityLayer(data);
                    } else {
                        processGSSAPIToken(data);
                    }
                    break;
                default:
                    reply(503, "5.5.1 Bad sequence of commands");
                    resetAuthState();
            }
        } catch (Exception e) {
            rejectCredentials();
            resetAuthState();
            events().warn("warn.auth_data_handling_error").thrown(e).emit();
        }
    }

    /** RFC 7628 §3.2.2 — handle OAUTHBEARER continuation (either initial or error ack). */
    private void handleOAuthDataResponse(String data) {
        // After an error challenge (334), client sends empty response to acknowledge
        String decoded = new String(Base64.getDecoder().decode(data), UTF_8);
        if (decoded.isEmpty() || decoded.equals("\u0001")) {
            rejectCredentials();
            resetAuthState();
            return;
        }
        processOAuthBearerResponse(data);
    }

    /**
     * Verifies a username/password with the realm, without waiting for it.
     * The realm (bound to this connection's loop) calls back on that loop
     * when it has answered: an LDAP realm does so when its directory
     * exchange completes, a local realm at once.
     *
     * @param callback receives the result on the loop thread
     */
    private void authenticateUserAsync(final String username,
            final String password,
            final StorageExecutor.Callback<Boolean> callback) {
        final Realm realm = getRealm();
        if (realm == null) {
            callback.completed(Boolean.FALSE);
            return;
        }
        realm.passwordMatch(username, password, awaiting(callback));
    }

    /**
     * Pauses reads while the realm answers, so that a pipelined command
     * cannot race the result, and resumes them before the callback runs.
     * The realm calls back on this connection's loop, without having made
     * the loop wait.
     */
    private <T> RealmCallback<T> awaiting(final StorageExecutor.Callback<T> callback) {
        endpoint.pauseRead();
        return new RealmCallback<T>() {
            @Override
            public void completed(T result) {
                endpoint.resumeRead();
                callback.completed(result);
            }

            @Override
            public void failed(Throwable cause) {
                endpoint.resumeRead();
                callback.failed(cause);
            }
        };
    }

    /**
     * Fetches the user's SCRAM credentials from the realm without waiting
     * for it. For {@link org.bluezoo.gumdrop.auth.BasicRealm}, a cache miss
     * runs a 210,000-iteration PBKDF2-HMAC-SHA256 derivation, which the
     * realm moves off the loop itself. Mirrors {@link
     * #authenticateUserAsync}, which makes the same call for {@link
     * Realm#passwordMatch}.
     *
     * @param callback receives the result (or the failure, e.g. an
     *                 {@link UnsupportedOperationException} if this realm
     *                 doesn't support SCRAM) on the loop thread
     */
    private void getScramCredentialsAsync(final String username,
            final StorageExecutor.Callback<Realm.ScramCredentials> callback) {
        final Realm realm = getRealm();
        if (realm == null) {
            callback.completed(null);
            return;
        }
        realm.getScramCredentials(username, awaiting(callback));
    }

    private void resetAuthState() {
        authState = AuthState.NONE;
        authMechanism = null;
        pendingAuthUsername = null;
        authChallenge = null;
        authNonce = null;
        authClientNonce = null;
        authServerSignature = null;
        authSalt = null;
        authIterations = 4096;
        if (saslServer != null) {
            try {
                saslServer.dispose();
            } catch (SaslException e) {
                // Ignore
            }
            saslServer = null;
        }
        if (gssapiExchange != null) {
            gssapiExchange.dispose();
            gssapiExchange = null;
        }
        clientCertificate = null;
    }

    private void notifyAuthenticationSuccess(String username, String mechanism) {
        authenticatedUser = username;
        authMechanism = mechanism;
        if (helloHandler != null) {
            Principal principal = new Principal() {
                @Override
                public String getName() {
                    return username;
                }
                @Override
                public String toString() {
                    return username;
                }
            };
            helloHandler.authenticated(SmtpProtocolHandler.this, principal);
        } else {
            authenticated = true;
            recordAuthenticationSuccess(username, mechanism);
            reply(235, "2.7.0 Authentication successful");
        }
    }

    private void notifyAuthenticationFailure(String username, String mechanism) {
        SmtpServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.authAttempt(mechanism);
            metrics.authFailure(mechanism);
        }
        server.recordAuthFailure(endpoint.getRemoteAddress(), username);
        reply(535, "5.7.8 Authentication credentials invalid");
    }

    /** Counts a failed attempt towards the client's lockout, then answers 535. */
    private void rejectCredentials() {
        server.recordAuthFailure(endpoint.getRemoteAddress(), null);
        reply(535, "5.7.8 Authentication credentials invalid");
    }

    /** RFC 5321 §4.1.1.10 — QUIT command; reply 221 and close. */
    private void quit(String args) {
        endSessionSpan("QUIT");
        this.state = SmtpState.QUIT;
        reply(221, "2.0.0 Goodbye");
        closeEndpoint();
        if (connectedHandler != null) {
            connectedHandler.disconnected();
        }
    }

    /** RFC 5321 §4.1.1.9 — NOOP command; reply 250. */
    private void noop(String args) {
        reply(250, "2.0.0 Ok");
    }

    /** RFC 5321 §4.1.1.8 — HELP command; reply 214. */
    private void help(String args) {
        if (args == null || args.trim().isEmpty()) {
            replyMultiline(214, "2.0.0 Gumdrop SMTP server - supported commands:");
            replyMultiline(214, "  HELO EHLO MAIL RCPT DATA BDAT RSET ETRN");
            replyMultiline(214, "  VRFY NOOP QUIT HELP");
            if (!endpoint.isSecure() && server.isSTARTTLSAvailable() && !starttlsUsed) {
                replyMultiline(214, "  STARTTLS");
            }
            if (getRealm() != null) {
                replyMultiline(214, "  AUTH");
            }
            if (isXclientAuthorized()) {
                replyMultiline(214, "  XCLIENT");
            }
            reply(214, "2.0.0 For more info: https://www.nongnu.org/gumdrop/smtp.html");
        } else {
            String cmd = args.trim().toUpperCase();
            if ("HELO".equals(cmd)) {
                reply(214, "2.0.0 HELO <hostname> - Identify client to server");
            } else if ("EHLO".equals(cmd)) {
                reply(214, "2.0.0 EHLO <hostname> - Extended HELO with capability negotiation");
            } else if ("MAIL".equals(cmd)) {
                replyMultiline(214, "2.0.0 MAIL FROM:<sender> [parameters...]");
                replyMultiline(214, "  SIZE=<n> BODY=7BIT|8BITMIME|BINARYMIME SMTPUTF8");
                reply(214, "  REQUIRETLS MT-PRIORITY=<-9..9> HOLDFOR=<s> HOLDUNTIL=<time> BY=<s>;R|N RET=FULL|HDRS ENVID=<id>");
            } else if ("RCPT".equals(cmd)) {
                reply(214, "2.0.0 RCPT TO:<recipient> [NOTIFY=NEVER|SUCCESS|FAILURE|DELAY] [ORCPT=<type>;<addr>]");
            } else if ("DATA".equals(cmd)) {
                reply(214, "2.0.0 DATA - Start message content (end with <CRLF>.<CRLF>)");
            } else if ("BDAT".equals(cmd)) {
                reply(214, "2.0.0 BDAT <size> [LAST] - Send message chunk (RFC 3030 CHUNKING)");
            } else if ("RSET".equals(cmd)) {
                reply(214, "2.0.0 RSET - Reset transaction state");
            } else if ("VRFY".equals(cmd)) {
                reply(214, "2.0.0 VRFY <address> - Verify address (limited support)");
            } else if ("NOOP".equals(cmd)) {
                reply(214, "2.0.0 NOOP - No operation");
            } else if ("QUIT".equals(cmd)) {
                reply(214, "2.0.0 QUIT - Close connection");
            } else if ("STARTTLS".equals(cmd)) {
                reply(214, "2.0.0 STARTTLS - Upgrade to TLS encryption");
            } else if ("AUTH".equals(cmd)) {
                reply(214, "2.0.0 AUTH <mechanism> [initial-response] - Authenticate");
            } else if ("XCLIENT".equals(cmd)) {
                reply(214, "2.0.0 XCLIENT attr=value [...] - Override connection attributes (Postfix extension)");
            } else if ("ETRN".equals(cmd)) {
                reply(214, "2.0.0 ETRN <node> - Request remote queue processing (RFC 1985)");
            } else {
                reply(504, "5.5.1 HELP not available for: " + args);
            }
        }
    }

    /** RFC 5321 §4.1.1.6 — VRFY command; 252 per §7.3 (information hiding). */
    private void vrfy(String args) {
        reply(252, "2.5.2 Cannot VRFY user, but will accept message and attempt delivery");
    }

    /** RFC 5321 §4.1.1.7 — EXPN command; 502 (not implemented, optional). */
    private void expn(String args) {
        reply(502, "5.5.1 EXPN not implemented");
    }

    /**
     * RFC 1985 — ETRN command (Remote Message Queue Starting).
     * Optional; returns 502 since this server does not support on-demand relay.
     */
    private void etrn(String args) {
        if (!extendedSMTP) {
            reply(502, "5.5.1 ETRN requires EHLO");
            return;
        }
        reply(458, "Unable to queue messages for node " + (args != null ? args.trim() : ""));
    }

    private String decodeXtext(String value) {
        if (value == null || !value.contains("+")) {
            return value;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '+' && i + 2 < value.length()) {
                try {
                    int hex = Integer.parseInt(value.substring(i + 1, i + 3), 16);
                    sb.append((char) hex);
                    i += 2;
                } catch (NumberFormatException e) {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Postfix XCLIENT extension — override client connection attributes. */
    private void xclient(String args) {
        if (!isXclientAuthorized()) {
            reply(550, "5.7.0 XCLIENT not authorized");
            return;
        }
        if (state == SmtpState.MAIL || state == SmtpState.RCPT
                || state == SmtpState.DATA || state == SmtpState.BDAT) {
            reply(503, "5.5.1 Mail transaction in progress");
            return;
        }
        if (args == null || args.trim().isEmpty()) {
            reply(501, "5.5.4 Syntax: XCLIENT attribute=value [...]");
            return;
        }
        String trimmedArgs = args.trim();
        int pairStart = 0;
        int pairLen = trimmedArgs.length();
        while (pairStart < pairLen) {
            while (pairStart < pairLen
                    && Character.isWhitespace(trimmedArgs.charAt(pairStart))) {
                pairStart++;
            }
            if (pairStart >= pairLen) {
                break;
            }
            int pairEnd = pairStart;
            while (pairEnd < pairLen
                    && !Character.isWhitespace(trimmedArgs.charAt(pairEnd))) {
                pairEnd++;
            }
            String pair = trimmedArgs.substring(pairStart, pairEnd);
            pairStart = pairEnd;
            int eqIdx = pair.indexOf('=');
            if (eqIdx <= 0) {
                reply(501, "5.5.4 Invalid XCLIENT attribute syntax: " + pair);
                return;
            }
            String attr = pair.substring(0, eqIdx).toUpperCase();
            String value = decodeXtext(pair.substring(eqIdx + 1));
            if ("[UNAVAILABLE]".equalsIgnoreCase(value)
                    || "[TEMPUNAVAIL]".equalsIgnoreCase(value)) {
                value = null;
            }
            if ("NAME".equals(attr)) {
                xclientName = value;
            } else if ("ADDR".equals(attr)) {
                if (value != null) {
                    try {
                        InetAddress addr = InetAddress.getByName(value);
                        int port = xclientAddr != null ? xclientAddr.getPort() : 0;
                        xclientAddr = new InetSocketAddress(addr, port);
                    } catch (UnknownHostException e) {
                        reply(501, "5.5.4 Invalid ADDR value: " + value);
                        return;
                    }
                } else {
                    xclientAddr = null;
                }
            } else if ("PORT".equals(attr)) {
                if (value != null) {
                    try {
                        int port = Integer.parseInt(value);
                        if (port < 0 || port > 65535) {
                            reply(501, "5.5.4 Invalid PORT value: " + value);
                            return;
                        }
                        InetAddress addr = xclientAddr != null
                                ? xclientAddr.getAddress()
                                : InetAddress.getLoopbackAddress();
                        xclientAddr = new InetSocketAddress(addr, port);
                    } catch (NumberFormatException e) {
                        reply(501, "5.5.4 Invalid PORT value: " + value);
                        return;
                    }
                }
            } else if ("PROTO".equals(attr)) {
                if (value != null && !"SMTP".equalsIgnoreCase(value)
                        && !"ESMTP".equalsIgnoreCase(value)) {
                    reply(501, "5.5.4 Invalid PROTO value: " + value);
                    return;
                }
                xclientProto = value;
                if ("ESMTP".equalsIgnoreCase(value)) {
                    extendedSMTP = true;
                }
            } else if ("HELO".equals(attr)) {
                xclientHelo = value;
                if (value != null) {
                    heloName = value;
                }
            } else if ("LOGIN".equals(attr)) {
                // Record the proxied login name for informational/policy use
                // only. Do NOT set authenticated=true or authenticatedUser:
                // those fields are reserved for locally verified SASL AUTH
                // exchanges and must not be derived from a proxy assertion.
                xclientLogin = value;
            } else if ("DESTADDR".equals(attr)) {
                if (value != null) {
                    try {
                        InetAddress addr = InetAddress.getByName(value);
                        int port = xclientDestAddr != null
                                ? xclientDestAddr.getPort() : 25;
                        xclientDestAddr = new InetSocketAddress(addr, port);
                    } catch (UnknownHostException e) {
                        reply(501, "5.5.4 Invalid DESTADDR value: " + value);
                        return;
                    }
                } else {
                    xclientDestAddr = null;
                }
            } else if ("DESTPORT".equals(attr)) {
                if (value != null) {
                    try {
                        int port = Integer.parseInt(value);
                        if (port < 0 || port > 65535) {
                            reply(501, "5.5.4 Invalid DESTPORT value: " + value);
                            return;
                        }
                        InetAddress addr = xclientDestAddr != null
                                ? xclientDestAddr.getAddress()
                                : InetAddress.getLoopbackAddress();
                        xclientDestAddr = new InetSocketAddress(addr, port);
                    } catch (NumberFormatException e) {
                        reply(501, "5.5.4 Invalid DESTPORT value: " + value);
                        return;
                    }
                }
            } else {
                reply(501, "5.5.4 Unknown XCLIENT attribute: " + attr);
                return;
            }
        }
        state = SmtpState.INITIAL;
        heloName = xclientHelo;
        mailFrom = null;
        recipients.clear();
        dsnRecipients.clear();
        smtputf8 = false;
        bodyType = BodyType.SEVEN_BIT;
        deliveryRequirements = null;
        resetDataState();
        if (xclientLogin != null) {
            addSessionAttribute("smtp.xclient_login", xclientLogin);
        }
        String localHostname = ((InetSocketAddress) endpoint.getLocalAddress())
                .getHostString();
        reply(220, localHostname + " ESMTP Gumdrop");
    }

    private void resetTransaction() {
        this.state = SmtpState.READY;
        this.mailFrom = null;
        this.recipients.clear();
        this.dsnRecipients.clear();
        this.deliveryRequirements = null;
        this.smtputf8 = false;
        this.bodyType = BodyType.SEVEN_BIT;
        this.pendingRecipient = null;
        this.pendingRecipientDSN = null;
        if (currentPipeline != null) {
            currentPipeline.reset();
            currentPipeline = null;
        }
        startSessionSpan();
    }

    /** RFC 5321 §4.1.1.5 — RSET command; reset transaction state, reply 250. */
    private void rset(String args) {
        endSessionSpan("RSET");
        resetDataState();
        if (mailFromHandler != null) {
            mailFromHandler.reset(this);
        } else if (recipientHandler != null) {
            recipientHandler.reset(this);
        } else {
            resetTransaction();
            reply(250, "2.0.0 Reset state");
        }
    }

    /**
     * RFC 5321 §4.1.1.2 — MAIL FROM command.
     * Parameters: SIZE (RFC 1870), BODY (RFC 6152 / RFC 3030),
     * SMTPUTF8 (RFC 6531), RET/ENVID (RFC 3461), REQUIRETLS (RFC 8689),
     * MT-PRIORITY (RFC 6710), HOLDFOR/HOLDUNTIL (RFC 4865), BY (RFC 2852).
     */
    private void mail(String args) {
        if (state != SmtpState.READY) {
            reply(503, "5.0.0 Bad sequence of commands");
            return;
        }
        int maxTransactions = server.getMaxTransactionsPerSession();
        if (maxTransactions > 0 && transactionCount >= maxTransactions) {
            reply(421, "4.7.0 Too many transactions, closing connection");
            closeEndpoint();
            return;
        }
        if (server.isAuthRequired() && !authenticated) {
            reply(530, "5.7.0 Authentication required");
            return;
        }
        if (args == null || !args.toUpperCase().startsWith("FROM:")) {
            reply(501, "5.0.0 Syntax: MAIL FROM:<address>");
            return;
        }
        String fromArg = args.substring(5).trim();
        String addressPart;
        String paramsPart = null;
        if (fromArg.startsWith("<")) {
            int closeAngle = fromArg.indexOf('>');
            if (closeAngle < 0) {
                reply(501, "5.1.7 Invalid sender address syntax");
                return;
            }
            addressPart = fromArg.substring(1, closeAngle);
            if (closeAngle + 1 < fromArg.length()) {
                paramsPart = fromArg.substring(closeAngle + 1).trim();
            }
        } else {
            int spaceIdx = fromArg.indexOf(' ');
            if (spaceIdx > 0) {
                addressPart = fromArg.substring(0, spaceIdx);
                paramsPart = fromArg.substring(spaceIdx + 1).trim();
            } else {
                addressPart = fromArg;
            }
        }
        String fromAddrStr = addressPart;
        long declaredSize = -1;
        boolean useSmtputf8 = false;
        if (paramsPart != null && !paramsPart.isEmpty()) {
            int paramStart = 0;
            int paramsLen = paramsPart.length();
            while (paramStart < paramsLen) {
                while (paramStart < paramsLen && Character.isWhitespace(paramsPart.charAt(paramStart))) {
                    paramStart++;
                }
                if (paramStart >= paramsLen) {
                    break;
                }
                int paramEnd = paramStart;
                while (paramEnd < paramsLen && !Character.isWhitespace(paramsPart.charAt(paramEnd))) {
                    paramEnd++;
                }
                String param = paramsPart.substring(paramStart, paramEnd);
                paramStart = paramEnd;
                String upperParam = param.toUpperCase();
                if (upperParam.startsWith("SIZE=")) {
                    try {
                        declaredSize = Long.parseLong(param.substring(5));
                        if (declaredSize < 0) {
                            reply(501, "5.5.4 Invalid SIZE parameter");
                            return;
                        }
                    } catch (NumberFormatException e) {
                        reply(501, "5.5.4 Invalid SIZE parameter");
                        return;
                    }
                } else if (upperParam.equals("SMTPUTF8")) {
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 SMTPUTF8 requires EHLO");
                        return;
                    }
                    useSmtputf8 = true;
                } else if (upperParam.startsWith("RET=")) {
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 RET requires EHLO");
                        return;
                    }
                    try {
                        DsnReturn dsnRet = DsnReturn.parse(param.substring(4));
                        if (deliveryRequirements == null) {
                            deliveryRequirements = new DefaultDeliveryRequirements();
                        }
                        deliveryRequirements.setDsnReturn(dsnRet);
                    } catch (IllegalArgumentException e) {
                        reply(501, "5.5.4 Invalid RET parameter (must be FULL or HDRS)");
                        return;
                    }
                } else if (upperParam.startsWith("ENVID=")) {
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 ENVID requires EHLO");
                        return;
                    }
                    String dsnEnvid = decodeXtext(param.substring(6));
                    if (dsnEnvid.isEmpty()) {
                        reply(501, "5.5.4 Invalid ENVID parameter");
                        return;
                    }
                    if (deliveryRequirements == null) {
                        deliveryRequirements = new DefaultDeliveryRequirements();
                    }
                    deliveryRequirements.setDsnEnvelopeId(dsnEnvid);
                } else if (upperParam.startsWith("BODY=")) {
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 BODY requires EHLO");
                        return;
                    }
                    try {
                        this.bodyType = BodyType.parse(param.substring(5));
                    } catch (IllegalArgumentException e) {
                        reply(501, "5.5.4 Invalid BODY parameter");
                        return;
                    }
                } else if (upperParam.equals("REQUIRETLS")) {
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 REQUIRETLS requires EHLO");
                        return;
                    }
                    if (!endpoint.isSecure()) {
                        reply(530, "5.7.10 REQUIRETLS requires TLS connection");
                        return;
                    }
                    if (deliveryRequirements == null) {
                        deliveryRequirements = new DefaultDeliveryRequirements();
                    }
                    deliveryRequirements.setRequireTls(true);
                } else if (upperParam.startsWith("MT-PRIORITY=")) {
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 MT-PRIORITY requires EHLO");
                        return;
                    }
                    try {
                        int priority = Integer.parseInt(param.substring(12));
                        if (priority < -9 || priority > 9) {
                            reply(501, "5.5.4 MT-PRIORITY must be between -9 and 9");
                            return;
                        }
                        if (deliveryRequirements == null) {
                            deliveryRequirements = new DefaultDeliveryRequirements();
                        }
                        deliveryRequirements.setPriority(priority);
                    } catch (NumberFormatException e) {
                        reply(501, "5.5.4 Invalid MT-PRIORITY value");
                        return;
                    }
                } else if (upperParam.startsWith("HOLDFOR=")) {
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 HOLDFOR requires EHLO");
                        return;
                    }
                    try {
                        long seconds = Long.parseLong(param.substring(8));
                        if (seconds < 0) {
                            reply(501, "5.5.4 HOLDFOR value must be non-negative");
                            return;
                        }
                        if (deliveryRequirements == null) {
                            deliveryRequirements = new DefaultDeliveryRequirements();
                        }
                        deliveryRequirements.setReleaseTime(Instant.now().plusSeconds(seconds));
                    } catch (NumberFormatException e) {
                        reply(501, "5.5.4 Invalid HOLDFOR value");
                        return;
                    }
                } else if (upperParam.startsWith("HOLDUNTIL=")) {
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 HOLDUNTIL requires EHLO");
                        return;
                    }
                    try {
                        Instant releaseTime = Instant.parse(param.substring(10));
                        if (deliveryRequirements == null) {
                            deliveryRequirements = new DefaultDeliveryRequirements();
                        }
                        deliveryRequirements.setReleaseTime(releaseTime);
                    } catch (DateTimeParseException e) {
                        reply(501, "5.5.4 Invalid HOLDUNTIL value (use ISO 8601 format)");
                        return;
                    }
                } else if (upperParam.startsWith("BY=")) {
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 BY requires EHLO");
                        return;
                    }
                    String byValue = param.substring(3);
                    boolean returnOnFail = true;
                    int semiIdx = byValue.indexOf(';');
                    String secondsStr;
                    if (semiIdx > 0) {
                        secondsStr = byValue.substring(0, semiIdx);
                        String trace = byValue.substring(semiIdx + 1).toUpperCase();
                        if ("R".equals(trace)) {
                            returnOnFail = true;
                        } else if ("N".equals(trace)) {
                            returnOnFail = false;
                        } else {
                            reply(501, "5.5.4 Invalid BY trace modifier (must be R or N)");
                            return;
                        }
                    } else {
                        secondsStr = byValue;
                    }
                    try {
                        long seconds = Long.parseLong(secondsStr);
                        if (seconds <= 0) {
                            reply(501, "5.5.4 BY value must be positive");
                            return;
                        }
                        if (deliveryRequirements == null) {
                            deliveryRequirements = new DefaultDeliveryRequirements();
                        }
                        deliveryRequirements.setDeliverByDeadline(Instant.now().plusSeconds(seconds));
                        deliveryRequirements.setDeliverByReturn(returnOnFail);
                    } catch (NumberFormatException e) {
                        reply(501, "5.5.4 Invalid BY value");
                        return;
                    }
                }
            }
        }
        this.smtputf8 = useSmtputf8;
        long maxSize = server.getMaxMessageSize();
        if (maxSize > 0 && declaredSize > maxSize) {
            reply(552, "5.3.4 Message size exceeds maximum (" + maxSize + " bytes)");
            return;
        }
        EmailAddress sender = null;
        if (!fromAddrStr.isEmpty()) {
            sender = EmailAddressParser.parseEnvelopeAddress(fromAddrStr, smtputf8);
            if (sender == null) {
                reply(501, "5.1.7 Invalid sender address syntax");
                return;
            }
        }
        final String fromAddr = (sender != null) ? sender.getEnvelopeAddress() : "";
        final EmailAddress envelopeSender = sender;
        final boolean utf8 = smtputf8;
        if (!authenticated) {
            acceptMailFrom(envelopeSender, utf8);
            return;
        }
        isAuthorizedSender(fromAddr, authenticatedUser,
                awaiting(new StorageExecutor.Callback<Boolean>() {
            @Override
            public void completed(Boolean allowed) {
                if (allowed == null || !allowed.booleanValue()) {
                    reply(550, "5.7.1 Not authorized to send from this address");
                    return;
                }
                acceptMailFrom(envelopeSender, utf8);
            }

            @Override
            public void failed(Throwable cause) {
                events().warn("warn.sender_authorization_failed").thrown(cause).emit();
                reply(451, "4.3.0 Unable to check the sender, try again later");
            }
        }));
    }

    /** The envelope sender is acceptable: starts the transaction. */
    private void acceptMailFrom(EmailAddress sender, boolean smtputf8) {
        this.mailFrom = sender;
        this.recipients.clear();
        this.dsnRecipients.clear();
        if (mailFromHandler != null) {
            DeliveryRequirements delivery = deliveryRequirements != null
                    ? deliveryRequirements : DefaultDeliveryRequirements.EMPTY;
            mailFromHandler.mailFrom(this, sender, smtputf8, delivery);
        } else {
            this.state = SmtpState.MAIL;
            transactionCount++;
            reply(250, "2.1.0 Sender ok");
        }
    }

    /**
     * Whether the authenticated user may use the address as envelope
     * sender: it is their own, or they are an admin or postmaster. The
     * first two are decided here; the roles are asked of the realm.
     */
    private void isAuthorizedSender(String fromAddress, final String authenticatedUser,
            final RealmCallback<Boolean> callback) {
        if (fromAddress == null || authenticatedUser == null) {
            callback.completed(Boolean.FALSE);
            return;
        }
        if (authenticatedUser.equalsIgnoreCase(fromAddress)) {
            callback.completed(Boolean.TRUE);
            return;
        }
        int atIndex = fromAddress.indexOf('@');
        if (atIndex > 0) {
            String localPart = fromAddress.substring(0, atIndex);
            if (authenticatedUser.equalsIgnoreCase(localPart)) {
                callback.completed(Boolean.TRUE);
                return;
            }
        }
        final Realm r = getRealm();
        if (r == null) {
            callback.completed(Boolean.FALSE);
            return;
        }
        r.isUserInRole(authenticatedUser, "admin", new RealmCallback<Boolean>() {
            @Override
            public void completed(Boolean admin) {
                if (admin != null && admin.booleanValue()) {
                    callback.completed(Boolean.TRUE);
                    return;
                }
                r.isUserInRole(authenticatedUser, "postmaster", callback);
            }

            @Override
            public void failed(Throwable cause) {
                callback.failed(cause);
            }
        });
    }

    /**
     * RFC 5321 §4.1.1.3 — RCPT TO command.
     * Parameters: NOTIFY/ORCPT (RFC 3461 §4.1–4.2).
     */
    private void rcpt(String args) {
        if (state != SmtpState.MAIL && state != SmtpState.RCPT) {
            reply(503, "5.0.0 Bad sequence of commands");
            return;
        }
        if (server.isAuthRequired() && !authenticated) {
            reply(530, "5.7.0 Authentication required");
            return;
        }
        if (args == null || !args.toUpperCase().startsWith("TO:")) {
            reply(501, "5.0.0 Syntax: RCPT TO:<address>");
            return;
        }
        int maxRecipients = server.getMaxRecipients();
        if (recipients.size() >= maxRecipients) {
            reply(452, "4.5.3 Too many recipients (maximum " + maxRecipients + ")");
            return;
        }
        String toArg = args.substring(3).trim();
        String addressPart;
        String paramsPart = null;
        if (toArg.startsWith("<")) {
            int closeAngle = toArg.indexOf('>');
            if (closeAngle < 0) {
                reply(501, "5.1.3 Invalid recipient address syntax");
                return;
            }
            addressPart = toArg.substring(1, closeAngle);
            if (closeAngle + 1 < toArg.length()) {
                paramsPart = toArg.substring(closeAngle + 1).trim();
            }
        } else {
            int spaceIdx = toArg.indexOf(' ');
            if (spaceIdx > 0) {
                addressPart = toArg.substring(0, spaceIdx);
                paramsPart = toArg.substring(spaceIdx + 1).trim();
            } else {
                addressPart = toArg;
            }
        }
        Set<DsnNotify> dsnNotify = null;
        String orcptType = null;
        String orcptAddress = null;
        if (paramsPart != null && !paramsPart.isEmpty()) {
            int paramStart = 0;
            int paramsLen = paramsPart.length();
            while (paramStart < paramsLen) {
                while (paramStart < paramsLen && Character.isWhitespace(paramsPart.charAt(paramStart))) {
                    paramStart++;
                }
                if (paramStart >= paramsLen) {
                    break;
                }
                int paramEnd = paramStart;
                while (paramEnd < paramsLen && !Character.isWhitespace(paramsPart.charAt(paramEnd))) {
                    paramEnd++;
                }
                String param = paramsPart.substring(paramStart, paramEnd);
                paramStart = paramEnd;
                String upperParam = param.toUpperCase();
                if (upperParam.startsWith("NOTIFY=")) {
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 NOTIFY requires EHLO");
                        return;
                    }
                    String notifyValue = param.substring(7);
                    dsnNotify = EnumSet.noneOf(DsnNotify.class);
                    try {
                        int kwStart = 0;
                        int kwLen = notifyValue.length();
                        while (kwStart <= kwLen) {
                            int kwEnd = notifyValue.indexOf(',', kwStart);
                            if (kwEnd < 0) {
                                kwEnd = kwLen;
                            }
                            String keyword = notifyValue.substring(kwStart, kwEnd).trim();
                            if (!keyword.isEmpty()) {
                                dsnNotify.add(DsnNotify.parse(keyword));
                            }
                            kwStart = kwEnd + 1;
                        }
                    } catch (IllegalArgumentException e) {
                        reply(501, "5.5.4 Invalid NOTIFY parameter");
                        return;
                    }
                    if (dsnNotify.contains(DsnNotify.NEVER) && dsnNotify.size() > 1) {
                        reply(501, "5.5.4 NOTIFY=NEVER cannot be combined with other values");
                        return;
                    }
                } else if (upperParam.startsWith("ORCPT=")) {
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 ORCPT requires EHLO");
                        return;
                    }
                    String orcptValue = param.substring(6);
                    int semicolon = orcptValue.indexOf(';');
                    if (semicolon <= 0) {
                        reply(501, "5.5.4 Invalid ORCPT syntax (expected type;address)");
                        return;
                    }
                    orcptType = orcptValue.substring(0, semicolon);
                    orcptAddress = decodeXtext(orcptValue.substring(semicolon + 1));
                    if (orcptAddress.isEmpty()) {
                        reply(501, "5.5.4 Invalid ORCPT address");
                        return;
                    }
                }
            }
        }
        if (addressPart.isEmpty()) {
            reply(501, "5.1.3 Invalid recipient address - empty");
            return;
        }
        EmailAddress recipient = EmailAddressParser.parseEnvelopeAddress(addressPart, smtputf8);
        if (recipient == null) {
            reply(501, "5.1.3 Invalid recipient address syntax");
            return;
        }
        DsnRecipientParameters pendingDsnParams = null;
        if (dsnNotify != null || orcptType != null) {
            pendingDsnParams = new DsnRecipientParameters(dsnNotify, orcptType, orcptAddress);
        }
        this.pendingRecipientDSN = pendingDsnParams;
        this.pendingRecipient = recipient;
        if (recipientHandler != null) {
            recipientHandler.rcptTo(this, recipient, server.getMailboxFactory());
        } else {
            this.recipients.add(recipient);
            this.state = SmtpState.RCPT;
            reply(250, "2.1.5 " + recipient.getEnvelopeAddress() + "... Recipient ok");
        }
    }

    /**
     * RFC 5321 §4.1.1.4 — DATA command; §4.5.2 dot transparency.
     * RFC 3030 — BODY=BINARYMIME requires BDAT, not DATA.
     */
    private void data(String args) {
        if (state != SmtpState.RCPT) {
            reply(503, "5.0.0 Bad sequence of commands");
            return;
        }
        if (server.isAuthRequired() && !authenticated) {
            reply(530, "5.7.0 Authentication required");
            return;
        }
        if (recipients.isEmpty()) {
            reply(503, "5.0.0 Need RCPT (recipient)");
            return;
        }
        if (bodyType.requiresBdat()) {
            reply(503, "5.6.1 BODY=BINARYMIME requires BDAT, not DATA");
            return;
        }
        if (recipientHandler != null) {
            recipientHandler.startMessage(this);
        } else {
            doAcceptMessage();
        }
    }

    private void doAcceptMessage() {
        resetDataState();
        this.state = SmtpState.DATA;
        reply(354, "Start mail input; end with <CRLF>.<CRLF>");
    }

    /** RFC 3030 §3 — BDAT command for chunked content transfer. */
    private void bdat(String args) {
        if (!extendedSMTP) {
            reply(503, "5.0.0 BDAT requires EHLO");
            return;
        }
        if (state != SmtpState.RCPT) {
            reply(503, "5.0.0 Bad sequence of commands");
            return;
        }
        if (server.isAuthRequired() && !authenticated) {
            reply(530, "5.7.0 Authentication required");
            return;
        }
        if (recipients.isEmpty()) {
            reply(503, "5.0.0 Need RCPT (recipient)");
            return;
        }
        if (args == null || args.trim().isEmpty()) {
            reply(501, "5.5.4 Syntax: BDAT size [LAST]");
            return;
        }
        String trimmedArgs = args.trim();
        String sizePart;
        String lastPart = null;
        int pos = 0;
        while (pos < trimmedArgs.length() && Character.isWhitespace(trimmedArgs.charAt(pos))) {
            pos++;
        }
        int sizeStart = pos;
        while (pos < trimmedArgs.length() && !Character.isWhitespace(trimmedArgs.charAt(pos))) {
            pos++;
        }
        if (sizeStart >= trimmedArgs.length()) {
            reply(501, "5.5.4 Syntax: BDAT size [LAST]");
            return;
        }
        sizePart = trimmedArgs.substring(sizeStart, pos);
        while (pos < trimmedArgs.length() && Character.isWhitespace(trimmedArgs.charAt(pos))) {
            pos++;
        }
        if (pos < trimmedArgs.length()) {
            int lastStart = pos;
            while (pos < trimmedArgs.length() && !Character.isWhitespace(trimmedArgs.charAt(pos))) {
                pos++;
            }
            lastPart = trimmedArgs.substring(lastStart, pos);
            while (pos < trimmedArgs.length() && Character.isWhitespace(trimmedArgs.charAt(pos))) {
                pos++;
            }
            if (pos < trimmedArgs.length()) {
                reply(501, "5.5.4 Syntax: BDAT size [LAST]");
                return;
            }
        }
        long chunkSize;
        try {
            chunkSize = Long.parseLong(sizePart);
        } catch (NumberFormatException e) {
            reply(501, "5.5.4 Invalid BDAT size: " + sizePart);
            return;
        }
        if (chunkSize < 0) {
            reply(501, "5.5.4 Invalid BDAT size: negative value");
            return;
        }
        long maxSize = server.getMaxMessageSize();
        if (maxSize > 0 && dataBytesReceived + chunkSize > maxSize) {
            reply(552, "5.3.4 Message size exceeds maximum permitted");
            resetBdatState();
            return;
        }
        boolean last = false;
        if (lastPart != null) {
            if ("LAST".equalsIgnoreCase(lastPart)) {
                last = true;
            } else {
                reply(501, "5.5.4 Invalid BDAT parameter: " + lastPart);
                return;
            }
        }
        if (!bdatStarted) {
            bdatStarted = true;
            if (recipientHandler != null) {
                recipientHandler.startMessage(new BdatStartStateImpl());
            }
        }
        bdatBytesRemaining = chunkSize;
        bdatLast = last;
        state = SmtpState.BDAT;
        if (chunkSize == 0) {
            handleBdatChunkComplete();
        }
    }

    private class BdatStartStateImpl implements MessageStartState {
        @Override
        public void acceptMessage(MessageDataHandler handler) {
            messageHandler = handler;
        }

        @Override
        public void rejectMessageStorageFull(RecipientHandler handler) {
            SmtpProtocolHandler.this.rejectMessageStorageFull(handler);
        }

        @Override
        public void rejectMessageProcessingError(RecipientHandler handler) {
            SmtpProtocolHandler.this.rejectMessageProcessingError(handler);
        }

        @Override
        public void rejectMessage(String message, MailFromHandler handler) {
            SmtpProtocolHandler.this.rejectMessage(message, handler);
        }

        @Override
        public void serverShuttingDown() {
            SmtpProtocolHandler.this.serverShuttingDown();
        }
    }

    @Override
    public void rejectMessageStorageFull(RecipientHandler handler) {
        this.recipientHandler = handler;
        reply(452, "4.3.1 Insufficient system storage");
    }

    @Override
    public void rejectMessageProcessingError(RecipientHandler handler) {
        this.recipientHandler = handler;
        reply(451, "4.3.0 Local processing error");
    }

    @Override
    public void rejectMessage(String message, MailFromHandler handler) {
        this.mailFromHandler = handler;
        resetTransaction();
        reply(550, "5.7.0 " + message);
    }

    // ── ConnectedState implementation (RFC 5321 §4.2 — greeting) ──

    /** RFC 5321 §4.2 — 220 greeting. */
    @Override
    public void acceptConnection(String greeting, HelloHandler handler) {
        this.helloHandler = handler;
        this.greetingDomain = greetingDomainOf(greeting);
        startSessionSpan();
        reply(220, greeting);
    }

    @Override
    public void rejectConnection() {
        rejectConnection("Connection rejected");
    }

    /** RFC 5321 §4.2 — 554 connection refused. */
    @Override
    public void rejectConnection(String message) {
        this.state = SmtpState.REJECTED;
        reply(554, "5.0.0 " + message);
        closeEndpoint();
        if (connectedHandler != null) {
            connectedHandler.disconnected();
        }
    }

    // ── HelloState implementation (RFC 5321 §4.1.1.1) ──

    /** RFC 5321 §4.1.1.1 — 250 EHLO/HELO accepted. */
    @Override
    public void acceptHello(MailFromHandler handler) {
        this.mailFromHandler = handler;
        this.state = SmtpState.READY;
        sendEhloResponse();
    }

    @Override
    public void rejectHelloTemporary(String message, HelloHandler handler) {
        this.helloHandler = handler;
        reply(421, "4.3.0 " + message);
    }

    @Override
    public void rejectHello(String message, HelloHandler handler) {
        this.helloHandler = handler;
        reply(550, "5.0.0 " + message);
    }

    @Override
    public void rejectHelloAndClose(String message) {
        this.state = SmtpState.REJECTED;
        reply(554, "5.0.0 " + message);
        closeEndpoint();
    }

    @Override
    public void serverShuttingDown() {
        reply(421, "4.3.0 Server shutting down");
        closeEndpoint();
    }

    // ── AuthenticateState implementation (RFC 4954) ──

    /** RFC 4954 — 235 authentication successful. */
    @Override
    public void accept(MailFromHandler handler) {
        this.authenticated = true;
        this.mailFromHandler = handler;
        this.state = SmtpState.READY;
        recordAuthenticationSuccess(authenticatedUser, authMechanism);
        reply(235, "2.7.0 Authentication successful");
    }

    /** RFC 4954 — 535 authentication credentials invalid. */
    @Override
    public void reject(HelloHandler handler) {
        this.helloHandler = handler;
        SmtpServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.authAttempt(authMechanism);
            metrics.authFailure(authMechanism);
        }
        server.recordAuthFailure(endpoint.getRemoteAddress(), null);
        reply(535, "5.7.8 Authentication rejected");
    }

    @Override
    public void rejectAndClose() {
        SmtpServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.authAttempt(authMechanism);
            metrics.authFailure(authMechanism);
        }
        server.recordAuthFailure(endpoint.getRemoteAddress(), null);
        reply(535, "5.7.8 Authentication rejected");
        closeEndpoint();
    }

    // ── MailFromState implementation (RFC 5321 §4.1.1.2) ──

    /** RFC 5321 §4.1.1.2 — 250 sender accepted. */
    @Override
    public void acceptSender(RecipientHandler handler) {
        this.recipientHandler = handler;
        this.state = SmtpState.MAIL;
        transactionCount++;
        if (mailFromHandler != null) {
            this.currentPipeline = mailFromHandler.getPipeline();
            if (currentPipeline != null) {
                currentPipeline.mailFrom(mailFrom);
            }
        }
        String senderAddr = (mailFrom != null) ? mailFrom.getEnvelopeAddress() : "";
        addSessionAttribute("smtp.mail_from", senderAddr);
        addSessionEvent("MAIL FROM: " + senderAddr);
        reply(250, "2.1.0 Sender ok");
    }

    @Override
    public void rejectSenderGreylist(MailFromHandler handler) {
        this.mailFromHandler = handler;
        reply(450, "4.7.1 Greylisting in effect, please try again later");
    }

    @Override
    public void rejectSenderRateLimit(MailFromHandler handler) {
        this.mailFromHandler = handler;
        reply(450, "4.7.1 Rate limit exceeded, please try again later");
    }

    @Override
    public void rejectSenderStorageFull(MailFromHandler handler) {
        this.mailFromHandler = handler;
        reply(452, "4.3.1 Insufficient system storage");
    }

    @Override
    public void rejectSenderBlockedDomain(MailFromHandler handler) {
        this.mailFromHandler = handler;
        reply(550, "5.1.1 Sender domain blocked by policy");
    }

    @Override
    public void rejectSenderInvalidDomain(MailFromHandler handler) {
        this.mailFromHandler = handler;
        reply(550, "5.1.1 Sender domain does not exist");
    }

    @Override
    public void rejectSenderPolicy(String message, MailFromHandler handler) {
        this.mailFromHandler = handler;
        reply(553, "5.7.1 " + message);
    }

    @Override
    public void rejectSenderSpam(MailFromHandler handler) {
        this.mailFromHandler = handler;
        reply(554, "5.7.1 Sender has poor reputation");
    }

    @Override
    public void rejectSenderSyntax(MailFromHandler handler) {
        this.mailFromHandler = handler;
        reply(501, "5.1.3 Invalid sender address format");
    }

    // ── RecipientState implementation (RFC 5321 §4.1.1.3) ──

    /** RFC 5321 §4.1.1.3 — 250 recipient accepted. */
    @Override
    public DsnRecipientParameters getRecipientDsnParameters() {
        return pendingRecipientDSN;
    }

    @Override
    public void acceptRecipient(RecipientHandler handler) {
        this.recipientHandler = handler;
        EmailAddress recipient = pendingRecipient;
        this.recipients.add(recipient);
        if (pendingRecipientDSN != null) {
            this.dsnRecipients.put(recipient, pendingRecipientDSN);
            pendingRecipientDSN = null;
        }
        this.state = SmtpState.RCPT;
        if (currentPipeline != null) {
            currentPipeline.rcptTo(recipient);
        }
        String addr = recipient.getEnvelopeAddress();
        addSessionAttribute("smtp.rcpt_count", recipients.size());
        addSessionEvent("RCPT TO: " + addr);
        reply(250, "2.1.5 " + addr + "... Recipient ok");
    }

    /** RFC 5321 §4.1.1.3 — 251 user not local; will forward. */
    @Override
    public void acceptRecipientForward(String forwardPath, RecipientHandler handler) {
        this.recipientHandler = handler;
        EmailAddress recipient = pendingRecipient;
        this.recipients.add(recipient);
        if (pendingRecipientDSN != null) {
            this.dsnRecipients.put(recipient, pendingRecipientDSN);
            pendingRecipientDSN = null;
        }
        this.state = SmtpState.RCPT;
        addSessionAttribute("smtp.rcpt_count", recipients.size());
        addSessionEvent("RCPT TO (forward): " + recipient.getEnvelopeAddress());
        reply(251, "2.1.5 User not local; will forward to " + forwardPath);
    }

    @Override
    public void rejectRecipientUnavailable(RecipientHandler handler) {
        this.recipientHandler = handler;
        reply(450, "4.2.1 Mailbox temporarily unavailable");
    }

    @Override
    public void rejectRecipientSystemError(RecipientHandler handler) {
        this.recipientHandler = handler;
        reply(451, "4.3.0 Local error in processing");
    }

    @Override
    public void rejectRecipientStorageFull(RecipientHandler handler) {
        this.recipientHandler = handler;
        reply(452, "4.3.1 Insufficient system storage");
    }

    @Override
    public void rejectRecipientNotFound(RecipientHandler handler) {
        this.recipientHandler = handler;
        reply(550, "5.1.1 Mailbox unavailable");
    }

    @Override
    public void rejectRecipientNotLocal(RecipientHandler handler) {
        this.recipientHandler = handler;
        reply(551, "5.1.1 User not local");
    }

    @Override
    public void rejectRecipientQuota(RecipientHandler handler) {
        this.recipientHandler = handler;
        reply(552, "5.2.2 Mailbox full, quota exceeded");
    }

    @Override
    public void rejectRecipientInvalid(RecipientHandler handler) {
        this.recipientHandler = handler;
        reply(553, "5.1.3 Mailbox name not allowed");
    }

    @Override
    public void rejectRecipientRelayDenied(RecipientHandler handler) {
        this.recipientHandler = handler;
        reply(551, "5.7.1 Relaying denied");
    }

    @Override
    public void rejectRecipientPolicy(String message, RecipientHandler handler) {
        this.recipientHandler = handler;
        reply(553, "5.7.1 " + message);
    }

    // ── MessageStartState implementation (RFC 5321 §4.1.1.4) ──

    /** RFC 5321 §4.1.1.4 — 354 start mail input. */
    @Override
    public void acceptMessage(MessageDataHandler handler) {
        this.messageHandler = handler;
        doAcceptMessage();
    }

    // ── MessageEndState implementation (RFC 5321 §3.3) ──

    /** RFC 5321 §3.3 — 250 message accepted for delivery. */
    @Override
    public void acceptMessageDelivery(String queueId, MailFromHandler handler) {
        this.mailFromHandler = handler;
        resetTransaction();
        String msg = "2.0.0 Message accepted for delivery";
        if (queueId != null) {
            msg = msg + " " + queueId;
        }
        reply(250, msg);
        endDelivery();
    }

    @Override
    public void rejectMessageTemporary(String message, MailFromHandler handler) {
        this.mailFromHandler = handler;
        resetTransaction();
        reply(450, "4.0.0 " + message);
        endDelivery();
    }

    @Override
    public void rejectMessagePermanent(String message, MailFromHandler handler) {
        this.mailFromHandler = handler;
        resetTransaction();
        reply(550, "5.0.0 " + message);
        endDelivery();
    }

    @Override
    public void rejectMessagePolicy(String message, MailFromHandler handler) {
        this.mailFromHandler = handler;
        resetTransaction();
        reply(553, "5.7.1 " + message);
        endDelivery();
    }

    // ── ResetState implementation (RFC 5321 §4.1.1.5) ──

    /** RFC 5321 §4.1.1.5 — 250 reset OK. */
    @Override
    public void acceptReset(MailFromHandler handler) {
        this.mailFromHandler = handler;
        resetTransaction();
        reply(250, "2.0.0 Reset OK");
    }

    // ── SmtpConnectionMetadata implementation (RFC 5321 / RFC 3461 / RFC 8689) ──

    @Override
    public InetSocketAddress getClientAddress() {
        if (xclientAddr != null) {
            return xclientAddr;
        }
        if (endpoint != null && endpoint.getRemoteAddress() != null) {
            return (InetSocketAddress) endpoint.getRemoteAddress();
        }
        return null;
    }

    @Override
    public InetSocketAddress getServerAddress() {
        if (xclientDestAddr != null) {
            return xclientDestAddr;
        }
        if (endpoint != null && endpoint.getLocalAddress() != null) {
            return (InetSocketAddress) endpoint.getLocalAddress();
        }
        return null;
    }

    @Override
    public boolean isSecure() {
        return endpoint != null && endpoint.isSecure();
    }

    @Override
    public X509Certificate[] getClientCertificates() {
        if (endpoint != null) {
            SecurityInfo info = endpoint.getSecurityInfo();
            if (info != null) {
                Certificate[] certs = info.getPeerCertificates();
                if (certs != null) {
                    X509Certificate[] x509 = new X509Certificate[certs.length];
                    for (int i = 0; i < certs.length; i++) {
                        x509[i] = (X509Certificate) certs[i];
                    }
                    return x509;
                }
            }
        }
        return null;
    }

    @Override
    public String getCipherSuite() {
        if (endpoint != null) {
            SecurityInfo info = endpoint.getSecurityInfo();
            if (info != null) {
                return info.getCipherSuite();
            }
        }
        return null;
    }

    @Override
    public String getProtocolVersion() {
        if (endpoint != null) {
            SecurityInfo info = endpoint.getSecurityInfo();
            if (info != null) {
                return info.getProtocol();
            }
        }
        return null;
    }

    @Override
    public long getConnectionTimeMillis() {
        return connectionTimeMillis;
    }

    @Override
    public DsnEnvelopeParameters getDSNEnvelopeParameters() {
        if (deliveryRequirements == null
                || !deliveryRequirements.hasDsnParameters()) {
            return null;
        }
        return new DsnEnvelopeParameters(deliveryRequirements.getDsnReturn(),
                deliveryRequirements.getDsnEnvelopeId());
    }

    @Override
    public DsnRecipientParameters getDSNRecipientParameters(
            EmailAddress recipient) {
        if (dsnRecipients == null || recipient == null) {
            return null;
        }
        return dsnRecipients.get(recipient);
    }

    @Override
    public boolean isRequireTls() {
        return deliveryRequirements != null
                && deliveryRequirements.isRequireTls();
    }

    private void recordAuthenticationSuccess(String username, String mechanism) {
        server.recordAuthSuccess(endpoint.getRemoteAddress(), username);
        addSessionAttribute("smtp.authenticated", true);
        addSessionAttribute("smtp.auth_user", username);
        addSessionAttribute("smtp.auth_mechanism", mechanism);
        addSessionEvent("AUTH success: " + mechanism);
        SmtpServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.authAttempt(mechanism);
            metrics.authSuccess(mechanism);
        }
    }
}
