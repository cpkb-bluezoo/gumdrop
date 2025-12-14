/*
 * SMTPConnection.java
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

package org.bluezoo.gumdrop.smtp;

import org.bluezoo.gumdrop.LineBasedConnection;
import org.bluezoo.gumdrop.ConnectionInfo;
import org.bluezoo.gumdrop.smtp.handler.*;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TLSInfo;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SASLMechanism;
import org.bluezoo.gumdrop.auth.SASLUtils;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddressParser;
import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.nio.CharBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.security.SecureRandom;
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
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.Subject;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;

import javax.net.ssl.SSLEngine;

/**
 * Connection handler for the SMTP protocol.
 * This manages one SMTP session over a TCP connection, handling command
 * parsing, authentication, and message transfer according to RFC 5321.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see https://www.rfc-editor.org/rfc/rfc5321 (SMTP)
 * @see https://www.rfc-editor.org/rfc/rfc6409 (Message Submission)
 * @see https://www.rfc-editor.org/rfc/rfc3207 (SMTP STARTTLS)
 */
public class SMTPConnection extends LineBasedConnection 
        implements ConnectedState, HelloState, AuthenticateState, 
                   MailFromState, RecipientState, MessageStartState, MessageEndState, 
                   ResetState, SMTPConnectionMetadata {

    private static final Logger LOGGER = Logger.getLogger(SMTPConnection.class.getName());

    /**
     * Localized message resources for SMTP.
     */
    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");

    /**
     * SMTP uses US-ASCII for commands and responses, except when SMTPUTF8 is active.
     */
    static final Charset US_ASCII = Charset.forName("US-ASCII");
    static final CharsetDecoder US_ASCII_DECODER = US_ASCII.newDecoder();
    static final Charset UTF_8 = Charset.forName("UTF-8");
    static final CharsetDecoder UTF_8_DECODER = UTF_8.newDecoder();

    // RFC 5321 limits to prevent memory exhaustion attacks
    private static final int MAX_LINE_LENGTH = 998;           // RFC 5321 section 4.5.3.1.6 (excluding CRLF)
    private static final int MAX_CONTROL_BUFFER_SIZE = 8;     // Max bytes for control sequences (\r\n.\r\n = 4 bytes + safety)
    // Note: Recipient limit (RCPTMAX) is now configurable via SMTPServer.setMaxRecipients()
    private static final int MAX_COMMAND_LINE_LENGTH = 1000;  // MAX_LINE_LENGTH + CRLF

    /**
     * SMTP session states according to RFC 5321 and RFC 3030 (BDAT/CHUNKING).
     */
    enum SMTPState {
        INITIAL,    // Initial state, waiting for HELO/EHLO
        REJECTED,   // Connection rejected at banner, only QUIT accepted
        READY,      // After successful HELO/EHLO, ready for mail transaction
        MAIL,       // After MAIL FROM command
        RCPT,       // After one or more RCPT TO commands
        DATA,       // During DATA command (receiving message content with dot-stuffing)
        BDAT,       // During BDAT command (receiving exact byte count, no dot-stuffing)
        QUIT        // After QUIT command
    }

    /**
     * SMTP authentication states for multi-step authentication.
     */
    enum AuthState {
        NONE,             // No authentication in progress
        PLAIN_RESPONSE,   // PLAIN: waiting for credentials
        LOGIN_USERNAME,   // LOGIN: waiting for username
        LOGIN_PASSWORD,   // LOGIN: waiting for password
        CRAM_MD5_RESPONSE, // CRAM-MD5: waiting for response to challenge
        DIGEST_MD5_RESPONSE, // DIGEST-MD5: waiting for response to challenge
        SCRAM_INITIAL,    // SCRAM-SHA-256: waiting for client first message
        SCRAM_FINAL,      // SCRAM-SHA-256: waiting for client final message
        OAUTH_RESPONSE,   // OAUTHBEARER: waiting for bearer token
        GSSAPI_EXCHANGE,  // GSSAPI: waiting for GSS-API token exchange
        EXTERNAL_CERT,    // EXTERNAL: certificate-based authentication
        NTLM_TYPE1,       // NTLM: waiting for Type 1 message
        NTLM_TYPE3        // NTLM: waiting for Type 3 message
    }


    // Using base class protected SocketChannel channel field
    private final SMTPServer server;
    private final long connectionTimeMillis;
    
    // Staged handler pattern - the current handler for each protocol state
    private ClientConnected connectedHandler;     // The initial handler that owns this connection
    private HelloHandler helloHandler;            // Receives HELO/EHLO, authenticated Principal
    private MailFromHandler mailFromHandler;      // Receives MAIL FROM
    private RecipientHandler recipientHandler;    // Receives RCPT TO, DATA/BDAT
    private MessageDataHandler messageHandler;    // Receives message completion
    private SMTPPipeline currentPipeline;         // Current transaction's processing pipeline
    
    // Bound realm for this connection's SelectorLoop
    private Realm realm;

    // SMTP session state
    private SMTPState state = SMTPState.INITIAL;
    private String heloName;
    private EmailAddress mailFrom;
    private List<EmailAddress> recipients;
    private boolean extendedSMTP = false; // true if EHLO was used
    private boolean smtputf8 = false;     // true if SMTPUTF8 parameter was used (RFC 6531)
    private BodyType bodyType = BodyType.SEVEN_BIT; // BODY parameter value (default 7BIT)
    private DefaultDeliveryRequirements deliveryRequirements; // Delivery options (REQUIRETLS, MT-PRIORITY, etc.)
    
    // DSN state (RFC 3461 - Delivery Status Notifications)
    // Note: DSN envelope params (RET, ENVID) are now in deliveryRequirements
    private Map<EmailAddress, DSNRecipientParameters> dsnRecipients = null; // DSN params per recipient
    private DSNRecipientParameters pendingRecipientDSN = null; // Temporary storage during rcptTo callback
    
    // Authentication state
    private boolean authenticated = false;
    private String authenticatedUser = null;
    private AuthState authState = AuthState.NONE;
    private String authMechanism = null;
    private String pendingAuthUsername = null;
    private boolean starttlsUsed = false;
    
    // Extended authentication state for advanced mechanisms
    private String authChallenge = null;       // Challenge string for CRAM-MD5, DIGEST-MD5
    private String authNonce = null;          // Nonce for DIGEST-MD5, SCRAM
    private String authClientNonce = null;    // Client nonce for SCRAM
    private String authServerSignature = null; // Server signature for SCRAM
    private byte[] authSalt = null;           // Salt for SCRAM-SHA-256
    private int authIterations = 4096;        // Iteration count for SCRAM-SHA-256
    
    // Enterprise authentication state
    private SaslServer saslServer = null;     // GSSAPI SASL server context
    private X509Certificate clientCertificate = null; // EXTERNAL: client certificate
    private byte[] ntlmChallenge = null;      // NTLM: server challenge
    private String ntlmTargetName = null;     // NTLM: target name info
    
    // XCLIENT state (Postfix XCLIENT extension)
    // These override the real connection values when set by an authorized proxy
    private InetSocketAddress xclientAddr = null;     // Overridden client address (ADDR/PORT)
    private InetSocketAddress xclientDestAddr = null; // Overridden server address (DESTADDR/DESTPORT)
    private String xclientName = null;                // Overridden client hostname (NAME)
    private String xclientHelo = null;                // Overridden HELO name (HELO)
    private String xclientProto = null;               // Overridden protocol SMTP/ESMTP (PROTO)
    private String xclientLogin = null;               // Overridden authenticated user (LOGIN)
    
    // Buffer management for command decoding and DATA control sequences
    private CharBuffer charBuffer; // Character buffer for decoding command lines
    private ByteBuffer controlBuffer; // Small buffer for partial control sequences (CRLF, dots, termination)
    
    // DATA state tracking for control sequences only
    enum DataState {
        NORMAL,     // Processing normal message data
        SAW_CR,     // Saw CR, waiting for LF  
        SAW_CRLF,   // Saw CRLF, checking for dot
        SAW_DOT,    // Saw CRLF., checking if termination or stuffing
        SAW_DOT_CR  // Saw CRLF.CR, checking for final LF of termination
    }
    private DataState dataState = DataState.NORMAL;
    private long dataBytesReceived = 0L; // Total message bytes received for current DATA/BDAT
    private boolean sizeExceeded = false; // True if message exceeded SIZE limit (avoid multiple errors)
    
    // BDAT (RFC 3030 CHUNKING) state tracking
    private long bdatBytesRemaining = 0L;  // Bytes remaining in current BDAT chunk
    private boolean bdatLast = false;      // True if current BDAT has LAST parameter
    private boolean bdatStarted = false;   // True if any BDAT has been received for this transaction

    // Telemetry tracking
    private Span sessionSpan;      // Current session span (reset on RSET)
    private int sessionNumber = 0; // Session counter for span naming

    /**
     * Creates a new SMTP connection.
     * @param server the SMTP server that created this connection
     * @param channel the socket channel for this connection
     * @param engine the SSL engine if this is a secure connection, null for plaintext
     * @param secure true if this connection should use TLS encryption
     * @param handler the application handler for SMTP events
     */
    protected SMTPConnection(SMTPServer server, SocketChannel channel, SSLEngine engine, boolean secure, ClientConnected handler) {
        super(engine, secure);
        this.server = server;
        this.channel = channel;
        this.connectedHandler = handler;
        this.connectionTimeMillis = System.currentTimeMillis();
        this.recipients = new ArrayList<>();
        this.dsnRecipients = new HashMap<>();
        this.charBuffer = CharBuffer.allocate(MAX_COMMAND_LINE_LENGTH);
        this.controlBuffer = ByteBuffer.allocate(MAX_CONTROL_BUFFER_SIZE);
    }

    /**
     * Returns the realm for authentication, bound to this connection's SelectorLoop.
     * Lazily initializes the bound realm on first access.
     *
     * @return the realm, or null if none configured
     */
    private Realm getRealm() {
        if (realm == null) {
            Realm serverRealm = server.getRealm();
            if (serverRealm != null) {
                SelectorLoop loop = getSelectorLoop();
                if (loop != null) {
                    realm = serverRealm.forSelectorLoop(loop);
                } else {
                    realm = serverRealm;
                }
            }
        }
        return realm;
    }

    @Override
    public void init() throws IOException {
        super.init();

        // Initialize telemetry trace for this connection
        initConnectionTrace();

        // Record connection opened metric
        SMTPServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.connectionOpened();
        }
        
        // Notify the handler that a client has connected
        // The handler will call acceptConnection() or rejectConnection() on this (the ConnectedState)
        if (connectedHandler != null) {
            ConnectionInfo info = createConnectionInfo();
            connectedHandler.connected(info, this);
        } else {
            // No handler configured - accept with default greeting
            startSessionSpan();
            reply(220, getLocalSocketAddress().toString() + " ESMTP Service ready");
        }
    }

    /**
     * Initializes the connection-level telemetry trace.
     */
    private void initConnectionTrace() {
        if (!isTelemetryEnabled()) {
            return;
        }

        TelemetryConfig telemetryConfig = getTelemetryConfig();
        String spanName = L10N.getString("telemetry.smtp_connection");
        Trace trace = telemetryConfig.createTrace(spanName, SpanKind.SERVER);
        setTrace(trace);

        if (trace != null) {
            Span rootSpan = trace.getRootSpan();
            // Add connection-level attributes
            rootSpan.addAttribute("net.transport", "ip_tcp");
            rootSpan.addAttribute("net.peer.ip", getRemoteSocketAddress().toString());
            rootSpan.addAttribute("rpc.system", "smtp");
        }
    }

    /**
     * Gets the SMTP server metrics from the server.
     */
    private SMTPServerMetrics getServerMetrics() {
        return server != null ? server.getMetrics() : null;
    }

    /**
     * Starts a new session span for the current SMTP session.
     * A session is the sequence of commands from connection/RSET to QUIT/RSET.
     */
    private void startSessionSpan() {
        Trace trace = getTrace();
        if (trace == null) {
            return;
        }

        // End previous session span if exists
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.end();
        }

        sessionNumber++;
        String spanName = MessageFormat.format(
                L10N.getString("telemetry.smtp_session"), sessionNumber);
        sessionSpan = trace.startSpan(spanName, SpanKind.SERVER);

        // Add session-level attributes
        sessionSpan.addAttribute("smtp.session_number", sessionNumber);
    }

    /**
     * Ends the current session span with a success status.
     *
     * @param message success message or null
     */
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

    /**
     * Ends the current session span with an error status.
     *
     * @param message error message
     */
    private void endSessionSpanError(String message) {
        if (sessionSpan == null || sessionSpan.isEnded()) {
            return;
        }

        sessionSpan.setStatusError(message);
        sessionSpan.end();
    }

    /**
     * Invoked when application data is received from the client.
     * The connection framework handles TLS decryption transparently,
     * so this method receives plain SMTP protocol data.
     * Routes data to appropriate handler based on current protocol state.
     * 
     * @param buf the application data received from the client
     */
    @Override
    public void receive(ByteBuffer buf) {
        try {
            if (state == SMTPState.BDAT) {
                // BDAT state - count exact bytes, no dot-stuffing
                handleBdatContent(buf);
            } else if (state == SMTPState.DATA) {
                // DATA state - work with bytes, handle dot-stuffing
                handleDataContent(buf);
            } else {
                // Command mode - process line-based commands
                receiveLine(buf);
                
                // If state changed during line processing and there's remaining data
                if (buf.hasRemaining()) {
                    if (state == SMTPState.BDAT) {
                        handleBdatContent(buf);
                    } else if (state == SMTPState.DATA) {
                        handleDataContent(buf);
                    }
                }
            }
        } catch (IOException e) {
            try {
                reply(500, L10N.getString("smtp.err.syntax_error"));
                String msg = L10N.getString("log.error_processing_data");
                LOGGER.log(Level.WARNING, msg, e);
            } catch (IOException e2) {
                String msg = L10N.getString("log.error_sending_response");
                LOGGER.log(Level.SEVERE, msg, e2);
            }
        }
    }

    /**
     * Returns false when transitioning to DATA or BDAT mode to stop line processing.
     */
    @Override
    protected boolean continueLineProcessing() {
        return state != SMTPState.DATA && state != SMTPState.BDAT;
    }

    /**
     * Called when a complete CRLF-terminated line has been received.
     * Decodes the line and dispatches the command.
     *
     * <p>Encoding handling per RFC 6531 (SMTPUTF8):
     * <ul>
     *   <li>Before MAIL FROM: decode as US-ASCII, reject non-ASCII</li>
     *   <li>MAIL FROM command: decode as UTF-8 (may contain internationalized address),
     *       then post-verify ASCII-only if SMTPUTF8 parameter was not specified</li>
     *   <li>After MAIL FROM with SMTPUTF8: decode as UTF-8</li>
     *   <li>After MAIL FROM without SMTPUTF8: decode as US-ASCII, reject non-ASCII</li>
     * </ul>
     *
     * @param line buffer containing the complete line including CRLF
     */
    @Override
    protected void lineReceived(ByteBuffer line) {
        try {
            // Check line length (excluding CRLF)
            int lineLength = line.remaining();
            if (lineLength > MAX_COMMAND_LINE_LENGTH) {
                reply(500, L10N.getString("smtp.err.line_too_long"));
                return;
            }

            // Determine if this might be a MAIL command (needs UTF-8 for potential i18n address)
            // We peek at the first 4 bytes to check for "MAIL" before choosing decoder
            boolean mightBeMailCommand = (state == SMTPState.READY && lineLength >= 4 &&
                    isMailCommandPrefix(line));

            // Choose decoder based on current state and SMTPUTF8 mode
            // - MAIL command: always UTF-8 (may contain i18n address)
            // - After MAIL with SMTPUTF8: UTF-8
            // - Otherwise: US-ASCII
            CharsetDecoder decoder;
            boolean requireAscii;
            if (mightBeMailCommand) {
                decoder = UTF_8_DECODER;
                requireAscii = false; // Post-verify after parsing SMTPUTF8 param
            } else if (smtputf8) {
                decoder = UTF_8_DECODER;
                requireAscii = false;
            } else {
                decoder = US_ASCII_DECODER;
                requireAscii = true;
            }

            // Decode line from buffer
            charBuffer.clear();
            decoder.reset();
            CoderResult result = decoder.decode(line, charBuffer, true);
            if (result.isError()) {
                reply(500, L10N.getString("smtp.err.invalid_encoding"));
                return;
            }
            charBuffer.flip();

            // For strict ASCII mode, verify no non-ASCII characters
            if (requireAscii && containsNonAscii(charBuffer)) {
                reply(500, L10N.getString("smtp.err.invalid_encoding"));
                return;
            }

            // Adjust limit to exclude CRLF terminator
            int len = charBuffer.limit();
            if (len >= 2 && charBuffer.get(len - 2) == '\r' && charBuffer.get(len - 1) == '\n') {
                charBuffer.limit(len - 2);
                len -= 2;
            }

            // Handle authentication data if auth is in progress
            if (authState != AuthState.NONE) {
                String lineStr = charBuffer.toString();
                handleAuthData(lineStr);
                return;
            }

            // Find space separator to split command and arguments
            int spaceIndex = -1;
            for (int i = 0; i < len; i++) {
                if (charBuffer.get(i) == ' ') {
                    spaceIndex = i;
                    break;
                }
            }

            // Extract command and arguments directly from CharBuffer
            String command;
            String args;
            if (spaceIndex > 0) {
                charBuffer.limit(spaceIndex);
                command = charBuffer.toString().toUpperCase(Locale.ENGLISH);
                charBuffer.limit(len);
                charBuffer.position(spaceIndex + 1);
                args = charBuffer.toString();
            } else {
                command = charBuffer.toString().toUpperCase(Locale.ENGLISH);
                args = null;
            }

            if (LOGGER.isLoggable(Level.FINEST)) {
                String msg = L10N.getString("log.smtp_command");
                msg = MessageFormat.format(msg, command, args != null ? args : "");
                LOGGER.finest(msg);
            }

            // Dispatch command
            dispatchCommand(command, args);

            // Post-verify MAIL command: if SMTPUTF8 was not specified but line
            // contained non-ASCII characters, that's an error
            if (mightBeMailCommand && "MAIL".equals(command) && !smtputf8) {
                // Reset charBuffer to check entire line for non-ASCII
                charBuffer.position(0);
                charBuffer.limit(len);
                if (containsNonAscii(charBuffer)) {
                    // MAIL command contained non-ASCII but SMTPUTF8 wasn't specified
                    // Reset transaction and return error
                    resetTransaction();
                    reply(553, L10N.getString("smtp.err.smtputf8_required"));
                    return;
                }
            }

        } catch (IOException e) {
            String msg = L10N.getString("log.error_processing_data");
            LOGGER.log(Level.WARNING, msg, e);
            try {
                reply(500, L10N.getString("smtp.err.internal_error"));
            } catch (IOException e2) {
                String errMsg = L10N.getString("log.error_sending_response");
                LOGGER.log(Level.SEVERE, errMsg, e2);
            }
        }
    }

    /**
     * Checks if the buffer starts with "MAIL" (case-insensitive).
     * Used to detect MAIL command for SMTPUTF8 handling.
     */
    private boolean isMailCommandPrefix(ByteBuffer buf) {
        int pos = buf.position();
        if (buf.remaining() < 4) {
            return false;
        }
        byte b0 = buf.get(pos);
        byte b1 = buf.get(pos + 1);
        byte b2 = buf.get(pos + 2);
        byte b3 = buf.get(pos + 3);
        // Check for "MAIL" or "mail" (case-insensitive)
        return (b0 == 'M' || b0 == 'm') &&
               (b1 == 'A' || b1 == 'a') &&
               (b2 == 'I' || b2 == 'i') &&
               (b3 == 'L' || b3 == 'l');
    }

    /**
     * Checks if the CharBuffer contains any non-ASCII characters (code points > 127).
     */
    private boolean containsNonAscii(CharBuffer buf) {
        int pos = buf.position();
        int lim = buf.limit();
        for (int i = pos; i < lim; i++) {
            if (buf.get(i) > 127) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dispatches a parsed SMTP command to the appropriate handler.
     *
     * @param command the command verb (uppercase)
     * @param args the command arguments, or null if none
     */
    private void dispatchCommand(String command, String args) throws IOException {
        if (state == SMTPState.REJECTED) {
            if ("QUIT".equals(command)) {
                quit(args);
            } else {
                reply(554, L10N.getString("smtp.err.connection_rejected"));
            }
            return;
        }

        if ("HELO".equals(command)) {
            helo(args);
        } else if ("EHLO".equals(command)) {
            ehlo(args);
        } else if ("MAIL".equals(command)) {
            mail(args);
        } else if ("RCPT".equals(command)) {
            rcpt(args);
        } else if ("DATA".equals(command)) {
            data(args);
        } else if ("BDAT".equals(command)) {
            bdat(args);
        } else if ("RSET".equals(command)) {
            rset(args);
        } else if ("QUIT".equals(command)) {
            quit(args);
        } else if ("NOOP".equals(command)) {
            noop(args);
        } else if ("HELP".equals(command)) {
            help(args);
        } else if ("VRFY".equals(command)) {
            vrfy(args);
        } else if ("EXPN".equals(command)) {
            expn(args);
        } else if ("STARTTLS".equals(command) && !secure && engine != null && !starttlsUsed) {
            starttls(args);
        } else if ("AUTH".equals(command)) {
            auth(args);
        } else if ("XCLIENT".equals(command)) {
            xclient(args);
        } else {
            String msg = L10N.getString("smtp.err.command_unrecognized");
            reply(500, MessageFormat.format(msg, command));
        }
    }

    /**
     * Handles message content during DATA state with streaming, memory-efficient processing.
     * Processes bytes with proper dot stuffing and termination detection.
     * Only chunks when necessary (dot stuffing or buffer boundaries).
     * 
     * CRITICAL: Works with raw bytes only - no character conversion is performed during DATA state.
     * This is essential because message content may use "8bit" or "binary" Content-Transfer-Encoding
     * which would be corrupted by any character set conversion. All processing must be byte-based.
     */
    private void handleDataContent(ByteBuffer buf) throws IOException {
        // First, handle any buffered control sequence from previous call
        if (controlBuffer.position() > 0) {
            handleControlSequenceWithNewData(buf);
            if (state != SMTPState.DATA) {
                // Terminated - check for pipelined commands after CRLF.CRLF
                if (buf.hasRemaining()) {
                    handlePipelinedCommands(buf);
                }
                return;
            }
        }
        
        // Process the main buffer iteratively
        processDataBuffer(buf);
        
        // PIPELINING support: If DATA terminated and there's remaining data, 
        // it's pipelined commands (e.g., MAIL FROM after the message)
        if (state != SMTPState.DATA && buf.hasRemaining()) {
            handlePipelinedCommands(buf);
        }
    }
    
    /**
     * Handles pipelined commands after DATA/BDAT content.
     * Routes remaining buffer data back through the receive method.
     */
    private void handlePipelinedCommands(ByteBuffer buf) throws IOException {
        // Process remaining data as commands
        receiveLine(buf);
        
        // If state changed during line processing and there's remaining data
        if (buf.hasRemaining()) {
            if (state == SMTPState.BDAT) {
                handleBdatContent(buf);
            } else if (state == SMTPState.DATA) {
                handleDataContent(buf);
            }
        }
    }
    
    /**
     * Processes data buffer iteratively (no recursion).
     * Handles dot stuffing and termination detection.
     */
    private void processDataBuffer(ByteBuffer buf) throws IOException {
        // Process the buffer, chunking only when necessary
        int chunkStart = buf.position();
        
        while (buf.hasRemaining()) {
            int currentPos = buf.position();
            byte b = buf.get();
            
            switch (dataState) {
                case NORMAL:
                    if (b == '\r') {
                        dataState = DataState.SAW_CR;
                    }
                    // Continue accumulating - no chunking needed for normal CRLF
                    break;
                    
                case SAW_CR:
                    if (b == '\n') {
                        dataState = DataState.SAW_CRLF;
                    } else if (b == '\r') {
                        // Stay in SAW_CR state - another CR
                    } else {
                        // Regular byte after lone CR
                        dataState = DataState.NORMAL;
                    }
                    // Continue accumulating - no chunking needed
                    break;
                    
                case SAW_CRLF:
                    if (b == '.') {
                        // Potential dot stuffing or termination - need to chunk here
                        // Send everything up to (but not including) the dot
                        if (currentPos > chunkStart) {
                            sendChunk(buf, chunkStart, currentPos);
                        }
                        dataState = DataState.SAW_DOT;
                        chunkStart = buf.position(); // Start after the dot
                    } else {
                        // Regular byte after CRLF - continue normal processing
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
                        // Dot stuffing confirmed - skip the dot, continue with this byte
                        dataState = DataState.NORMAL;
                        if (b == '\r') {
                            dataState = DataState.SAW_CR;
                        }
                        // chunkStart already positioned after the skipped dot
                    }
                    break;
                    
                case SAW_DOT_CR:
                    if (b == '\n') {
                        // Complete termination: CRLF.CRLF
                        // Send any remaining chunk before termination
                        if (chunkStart < currentPos - 3) { // Account for .CRLF we're not sending
                            sendChunk(buf, chunkStart, currentPos - 3);
                        }
                        
                        // Message complete
                        long messageSize = dataBytesReceived;
                        int recipientCount = recipients != null ? recipients.size() : 0;
                        boolean exceeded = sizeExceeded;
                        long maxSize = server.getMaxMessageSize();
                        resetDataState();
                        state = SMTPState.READY;
                        
                        // Check if size limit was exceeded
                        if (exceeded) {
                            addSessionEvent("DATA rejected (size exceeded)");
                            reply(552, "5.3.4 Message size exceeds maximum (" + maxSize + " bytes)");
                            return;
                        }
                        
                        addSessionEvent("DATA complete");

                        // Record message received metric
                        SMTPServerMetrics metrics = getServerMetrics();
                        if (metrics != null) {
                            metrics.messageReceived(messageSize, recipientCount);
                        }

                        // Notify handler that message is complete
                        // Handler will call acceptMessageDelivery() or reject methods
                        if (messageHandler != null) {
                            messageHandler.messageComplete(this);
                            // Response is sent by handler via MessageEndState
                            return;
                        }

                        // No handler - auto-accept
                        reply(250, "2.0.0 Message accepted for delivery");
                        return;
                    } else {
                        // False alarm - was dot stuffing followed by CR and something else
                        dataState = DataState.NORMAL;
                        if (b == '\r') {
                            dataState = DataState.SAW_CR;
                        }
                        // Continue processing - the dot was already skipped
                    }
                    break;
            }
        }
        
        // Handle end of buffer
        if (needsControlBuffering()) {
            // We're in a control sequence that spans buffer boundary
            // First, send any data before the control sequence started
            int controlStart = getControlSequenceStart(buf.position());
            if (controlStart > chunkStart) {
                sendChunk(buf, chunkStart, controlStart);
            }
            // Now save just the control sequence bytes
            saveControlSequence(buf, controlStart);
        } else {
            // Send any remaining data - can include multiple lines
            if (buf.position() > chunkStart) {
                sendChunk(buf, chunkStart, buf.position());
            }
        }
    }
    
    /**
     * Calculates where the current partial control sequence started.
     * @param currentPos current buffer position
     * @return position where the control sequence started
     */
    private int getControlSequenceStart(int currentPos) {
        switch (dataState) {
            case SAW_CR:
                return currentPos - 1;  // Just the \r
            case SAW_CRLF:
                return currentPos - 2;  // The \r\n
            case SAW_DOT:
                return currentPos - 1;  // Just the .
            case SAW_DOT_CR:
                return currentPos - 2;  // The .\r
            default:
                return currentPos;
        }
    }

    /**
     * Determines if we need to buffer a partial control sequence.
     * Only buffer if we're in the middle of a potential dot stuffing or termination sequence.
     */
    private boolean needsControlBuffering() {
        return dataState == DataState.SAW_CR || 
               dataState == DataState.SAW_CRLF || 
               dataState == DataState.SAW_DOT || 
               dataState == DataState.SAW_DOT_CR;
    }

    /**
     * Sends an SMTP response to the client.
     * @param code the SMTP response code
     * @param message the response message
     */
    protected void reply(int code, String message) throws IOException {
        sendResponse(code, message);
    }

    /**
     * Sends a multiline SMTP response (continuation line with hyphen).
     * @param code the SMTP response code
     * @param message the response message
     */
    protected void replyMultiline(int code, String message) throws IOException {
        String response = String.format("%d-%s\r\n", code, message);
        ByteBuffer buffer = ByteBuffer.wrap(response.getBytes(US_ASCII));
        send(buffer);
        
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("SMTP multiline response sent: " + code + "-" + message);
        }
    }

    /**
     * Sends the final line of an SMTP response (ends with space, not hyphen).
     * @param code the SMTP response code
     * @param message the response message
     */
    protected void sendResponse(int code, String message) throws IOException {
        String response = String.format("%d %s\r\n", code, message);
        ByteBuffer buffer = ByteBuffer.wrap(response.getBytes(US_ASCII));
        send(buffer);
        
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("SMTP response sent: " + code + " " + message);
        }
    }

    /**
     * Processes RFC822 message content received during DATA command.
     * Delivers to the handler's messageContent method and also writes to
     * the pipeline's channel if one is configured.
     * @param messageBuffer the message content as bytes
     */
    private void messageContent(ByteBuffer messageBuffer) {
        // Always deliver to handler if available
        if (messageHandler != null) {
            // Mark position so we can reset after handler consumes
            int originalPosition = messageBuffer.position();
            messageHandler.messageContent(messageBuffer);
            
            // Reset buffer position for pipeline if needed
            if (currentPipeline != null) {
                messageBuffer.position(originalPosition);
            }
        }
        
        // Also deliver to pipeline if configured
        if (currentPipeline != null) {
            try {
                java.nio.channels.WritableByteChannel channel = currentPipeline.getMessageChannel();
                if (channel != null) {
                    channel.write(messageBuffer);
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error writing to pipeline", e);
            }
        }
    }

    // ===== Buffer Management Helper Methods =====

    // ===== Streaming DATA Processing Helper Methods =====

    /**
     * Appends new data from source buffer to control buffer, expanding if necessary.
     * @param source the source buffer containing new data
     */
    private void appendToControlBuffer(ByteBuffer source) {
        // Ensure control buffer has enough capacity
        if (controlBuffer.remaining() < source.remaining()) {
            // Need to expand the buffer
            int newCapacity = controlBuffer.capacity() + Math.max(source.remaining(), MAX_CONTROL_BUFFER_SIZE);
            ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
            
            // Copy existing data
            controlBuffer.flip();
            newBuffer.put(controlBuffer);
            
            controlBuffer = newBuffer;
        }
        
        // Append source data
        controlBuffer.put(source);
    }

    /**
     * Sends a chunk of data immediately to messageContent().
     * Checks SIZE limit and sets sizeExceeded flag if exceeded.
     * @param source the source buffer
     * @param start the start position (inclusive)
     * @param end the end position (exclusive)
     */
    private void sendChunk(ByteBuffer source, int start, int end) throws IOException {                                                                      
        if (end > start) {
            int savedPosition = source.position();
            int savedLimit = source.limit();
            
            // Ensure start and end are within buffer bounds
            int actualStart = Math.max(0, Math.min(start, source.limit()));
            int actualEnd = Math.max(actualStart, Math.min(end, source.limit()));
            
            // Track bytes received for metrics
            dataBytesReceived += (actualEnd - actualStart);
            
            // Check SIZE limit
            long maxSize = server.getMaxMessageSize();
            if (maxSize > 0 && dataBytesReceived > maxSize) {
                sizeExceeded = true;
                // Don't send to handler if size exceeded - continue consuming until termination
                // Restore buffer state and return
                source.limit(savedLimit);
                source.position(Math.min(savedPosition, source.limit()));
                return;
            }
            
            // Create a view of just the chunk we want to send
            source.position(actualStart);
            source.limit(actualEnd);
            ByteBuffer chunk = source.slice();
            
            // Restore the source buffer state with bounds checking
            source.limit(savedLimit);
            // Ensure savedPosition doesn't exceed the buffer limit
            int safePosition = Math.min(savedPosition, source.limit());
            source.position(safePosition);
            
            // Send the chunk
            messageContent(chunk);
        }
    }

    /**
     * Handles partial control sequences buffered from previous received() calls.
     * @param newData new data to append to the control sequence
     */
    private void handleControlSequenceWithNewData(ByteBuffer newData) throws IOException {                                                                  
        // Append new data to control buffer
        appendToControlBuffer(newData);
        
        // Try to process the control sequence
        controlBuffer.flip();
        
        // Only process if there's actually data in the control buffer
        if (controlBuffer.hasRemaining()) {
            // Process as much as we can from the control buffer NON-RECURSIVELY
            ByteBuffer tempBuf = controlBuffer.slice();
            controlBuffer.clear(); // Clear immediately after slice
            
            // Process the combined data iteratively (NO RECURSION)
            if (tempBuf.hasRemaining()) {
                processDataBuffer(tempBuf);
            }
        } else {
            controlBuffer.clear();
        }
    }

    /**
     * Saves a partial control sequence for processing in the next received() call.
     * Control sequences are limited to MAX_CONTROL_BUFFER_SIZE bytes (max termination sequence is 4 bytes).
     * @param source the source buffer
     * @param start the start position of the partial sequence
     */
    private void saveControlSequence(ByteBuffer source, int start) {
        controlBuffer.clear();
        
        int savedPosition = source.position();
        
        // Ensure start position is within buffer bounds
        int safeStart = Math.max(0, Math.min(start, source.limit()));
        source.position(safeStart);
        
        // Copy the partial sequence to control buffer (bounded by MAX_CONTROL_BUFFER_SIZE)
        int bytesToCopy = 0;
        while (source.hasRemaining() && controlBuffer.hasRemaining() && bytesToCopy < MAX_CONTROL_BUFFER_SIZE) {
            controlBuffer.put(source.get());
            bytesToCopy++;
        }
        
        // If we hit the limit, this is likely an attack or malformed data
        if (bytesToCopy >= MAX_CONTROL_BUFFER_SIZE) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("Control sequence buffer limit exceeded, possible attack from " + 
                             getRemoteSocketAddress());
            }
        }
        
        // Restore position safely
        int safeRestorePosition = Math.max(0, Math.min(savedPosition, source.limit()));
        source.position(safeRestorePosition);
    }

    /**
     * Resets DATA state tracking variables.
     */
    private void resetDataState() {
        controlBuffer.clear();
        dataState = DataState.NORMAL;
        dataBytesReceived = 0L;
        sizeExceeded = false;
        resetBdatState();
    }

    /**
     * Resets BDAT state tracking variables.
     */
    private void resetBdatState() {
        bdatBytesRemaining = 0L;
        bdatLast = false;
        bdatStarted = false;
    }

    /**
     * Handles BDAT content - simply counts bytes and writes to pipeline.
     * Unlike DATA, BDAT has no dot-stuffing or special termination sequence.
     * The exact number of bytes specified in the BDAT command is expected.
     */
    private void handleBdatContent(ByteBuffer buf) throws IOException {
        while (buf.hasRemaining() && bdatBytesRemaining > 0) {
            // Calculate how many bytes we can process in this iteration
            int available = buf.remaining();
            int toProcess = (int) Math.min(available, bdatBytesRemaining);
            
            // Save position for later advancement
            int startPos = buf.position();
            int oldLimit = buf.limit();
            
            // Create a view for this chunk
            buf.limit(startPos + toProcess);
            
            // Pass to handler for processing
            messageContent(buf);
            
            // Update counters
            dataBytesReceived += toProcess;
            bdatBytesRemaining -= toProcess;
            
            // Restore limit and advance position from saved start
            buf.limit(oldLimit);
            buf.position(startPos + toProcess);
        }
        
        // Check if we've received all bytes for this chunk
        if (bdatBytesRemaining == 0) {
            handleBdatChunkComplete();
            
            // If there's more data in the buffer and we're back in command mode, process it
            if (buf.hasRemaining() && state != SMTPState.BDAT) {
                handlePipelinedCommands(buf);
            }
        }
    }

    /**
     * Called when a BDAT chunk has been completely received.
     * Sends appropriate response and handles transaction completion if this was LAST chunk.
     */
    private void handleBdatChunkComplete() throws IOException {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("BDAT chunk complete, total bytes: " + dataBytesReceived + 
                       (bdatLast ? " (LAST)" : ""));
        }

        if (bdatLast) {
            // This was the final chunk - message is complete
            long messageSize = dataBytesReceived;
            int recipientCount = recipients != null ? recipients.size() : 0;
            
            addSessionEvent("BDAT LAST complete");

            // Record message received metric
            SMTPServerMetrics metrics = getServerMetrics();
            if (metrics != null) {
                metrics.messageReceived(messageSize, recipientCount);
            }

            // End pipeline data if set
            if (currentPipeline != null) {
                currentPipeline.endData();
            }

            // Notify handler that message is complete
            // Handler will call acceptMessageDelivery() or reject methods
            if (messageHandler != null) {
                messageHandler.messageComplete(this);
                // Response is sent by handler via MessageEndState
                return;
            }

            // No handler - auto-accept
            // Reset state for next transaction
            resetDataState();
            state = SMTPState.READY;
            
            reply(250, "2.0.0 Message accepted for delivery (" + messageSize + " bytes)");
        } else {
            // Not the last chunk - acknowledge and wait for more
            state = SMTPState.RCPT; // Go back to RCPT state to accept more BDAT commands
            reply(250, "2.0.0 " + dataBytesReceived + " bytes received");
        }
    }

    // ===== SMTP Command Handlers =====

    /**
     * HELO command - Simple SMTP greeting.
     * @param hostname the client's hostname
     */
    private void helo(String hostname) throws IOException {
        if (hostname == null || hostname.trim().isEmpty()) {
            reply(501, "5.0.0 Syntax: HELO hostname");
            return;
        }

        this.heloName = hostname.trim();
        this.extendedSMTP = false;

        // Add telemetry attributes
        addSessionAttribute("smtp.helo", this.heloName);
        addSessionEvent("HELO");
        
        // Delegate to handler - it will call acceptHello() to transition state
        if (helloHandler != null) {
            helloHandler.hello(false, this.heloName, this);
        } else {
            // No handler - auto-accept
            this.state = SMTPState.READY;
            String localHostname = getLocalSocketAddress().toString();
            reply(250, localHostname + " Hello " + hostname);
        }
    }

    /**
     * EHLO command - Extended SMTP greeting.
     * @param hostname the client's hostname  
     */
    private void ehlo(String hostname) throws IOException {
        if (hostname == null || hostname.trim().isEmpty()) {
            reply(501, "5.0.0 Syntax: EHLO hostname");
            return;
        }

        this.heloName = hostname.trim();
        this.extendedSMTP = true;

        // Add telemetry attributes
        addSessionAttribute("smtp.ehlo", this.heloName);
        addSessionAttribute("smtp.esmtp", true);
        addSessionEvent("EHLO");
        
        // Delegate to handler - it will call acceptHello() to transition state
        if (helloHandler != null) {
            helloHandler.hello(true, this.heloName, this);
        } else {
            // No handler - auto-accept
            this.state = SMTPState.READY;
            sendEhloResponse();
        }
    }
    
    /**
     * Sends the full EHLO response with capabilities.
     */
    private void sendEhloResponse() throws IOException {
        String localHostname = getLocalSocketAddress().toString(); // TODO: get proper hostname
        
        // Send multiline EHLO response with capabilities
        // All lines except the last use hyphen (replyMultiline), last line uses space (sendResponse)
        replyMultiline(250, localHostname + " Hello " + heloName);
        
        // Standard capabilities (using replyMultiline for continuation lines)
        replyMultiline(250, "SIZE " + server.getMaxMessageSize()); // Configurable maximum message size
        replyMultiline(250, "PIPELINING");   // Support command pipelining
        replyMultiline(250, "8BITMIME");     // Support 8-bit MIME
        replyMultiline(250, "SMTPUTF8");     // Support internationalized email (RFC 6531)
        replyMultiline(250, "ENHANCEDSTATUSCODES"); // Enhanced status codes (already using them)
        replyMultiline(250, "CHUNKING");     // Support BDAT command (RFC 3030)
        replyMultiline(250, "BINARYMIME");   // Support binary message content (RFC 3030)
        replyMultiline(250, "DSN");          // Delivery Status Notifications (RFC 3461)
        
        // LIMITS extension (RFC 9422) - advertise operational limits
        StringBuilder limits = new StringBuilder("LIMITS");
        int maxRecipients = server.getMaxRecipients();
        if (maxRecipients > 0) {
            limits.append(" RCPTMAX=").append(maxRecipients);
        }
        int maxTransactions = server.getMaxTransactionsPerSession();
        if (maxTransactions > 0) {
            limits.append(" MAILMAX=").append(maxTransactions);
        }
        replyMultiline(250, limits.toString());
        
        // REQUIRETLS (RFC 8689) - only advertise if TLS is available
        if (secure || engine != null) {
            replyMultiline(250, "REQUIRETLS");
        }
        
        // Message delivery options
        replyMultiline(250, "MT-PRIORITY MIXER STANAG4406 NSEP"); // RFC 6710 - all priority levels
        replyMultiline(250, "FUTURERELEASE 604800 2012-01-01T00:00:00Z"); // RFC 4865 - max 7 days
        replyMultiline(250, "DELIVERBY 604800"); // RFC 2852 - max 7 days
        
        // XCLIENT capability (Postfix extension) - only advertise to authorized clients
        if (isXclientAuthorized()) {
            replyMultiline(250, "XCLIENT NAME ADDR PORT PROTO HELO LOGIN DESTADDR DESTPORT");
        }
        
        // STARTTLS capability (only if TLS is not already active and SSL context available)
        if (!isSecure() && server.isSTARTTLSAvailable()) {
            replyMultiline(250, "STARTTLS");
        }
        
        // Authentication capability
        // Advertise AUTH if we have a realm configured and either:
        // - Connection is already secure, OR
        // - STARTTLS is available for securing the connection
        Realm realm = getRealm();
        if (realm != null && (isSecure() || server.isSTARTTLSAvailable())) {
            java.util.Set<SASLMechanism> supported = realm.getSupportedSASLMechanisms();
            if (!supported.isEmpty()) {
                StringBuilder authLine = new StringBuilder("AUTH");
                for (SASLMechanism mech : supported) {
                    // Skip mechanisms that require TLS if not secure
                    if (!isSecure() && mech.requiresTLS()) {
                        continue;
                    }
                    // Skip EXTERNAL if not secure (needs client certificate)
                    if (mech == SASLMechanism.EXTERNAL && !isSecure()) {
                        continue;
                    }
                    authLine.append(" ").append(mech.getMechanismName());
                }
                replyMultiline(250, authLine.toString());
            }
        }
        
        // Final capability line (required to end with space, not hyphen)
        sendResponse(250, "HELP"); // Last line uses space, not hyphen
    }

    /**
     * MAIL command - Begin mail transaction.
     * @param args the arguments (FROM:<email> [SIZE=n])
     */
    private void mail(String args) throws IOException {
        if (state != SMTPState.READY) {
            reply(503, "5.0.0 Bad sequence of commands");
            return;
        }

        // Check transaction limit (MAILMAX from RFC 9422 LIMITS)
        int maxTransactions = server.getMaxTransactionsPerSession();
        if (maxTransactions > 0 && sessionNumber >= maxTransactions) {
            reply(421, "4.7.0 Too many transactions, closing connection");
            close();
            return;
        }

        // Check authentication requirement (typically for MSA on port 587)
        if (server.isAuthRequired() && !authenticated) {
            reply(530, "5.7.0 Authentication required");
            return;
        }

        if (args == null || !args.toUpperCase().startsWith("FROM:")) {
            reply(501, "5.0.0 Syntax: MAIL FROM:<address>");
            return;
        }

        // Parse email address and optional parameters from FROM:<email> [SIZE=n] [BODY=...]
        String fromArg = args.substring(5).trim();
        
        // Extract address (may be bracketed) and parameters
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
            // Non-bracketed address (legacy format) - space separates address from params
            int spaceIdx = fromArg.indexOf(' ');
            if (spaceIdx > 0) {
                addressPart = fromArg.substring(0, spaceIdx);
                paramsPart = fromArg.substring(spaceIdx + 1).trim();
            } else {
                addressPart = fromArg;
            }
        }
        String fromAddrStr = addressPart;
        
        // Parse MAIL FROM parameters (SIZE, SMTPUTF8, BODY, RET, ENVID, etc.)
        long declaredSize = -1;
        boolean useSmtputf8 = false;
        if (paramsPart != null && !paramsPart.isEmpty()) {
            for (String param : paramsPart.split("\\s+")) {
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
                    // RFC 6531 - Internationalized Email
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 SMTPUTF8 requires EHLO");
                        return;
                    }
                    useSmtputf8 = true;
                } else if (upperParam.startsWith("RET=")) {
                    // RFC 3461 - DSN return type
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 RET requires EHLO");
                        return;
                    }
                    try {
                        DSNReturn dsnRet = DSNReturn.parse(param.substring(4));
                        if (deliveryRequirements == null) {
                            deliveryRequirements = new DefaultDeliveryRequirements();
                        }
                        deliveryRequirements.setDsnReturn(dsnRet);
                    } catch (IllegalArgumentException e) {
                        reply(501, "5.5.4 Invalid RET parameter (must be FULL or HDRS)");
                        return;
                    }
                } else if (upperParam.startsWith("ENVID=")) {
                    // RFC 3461 - DSN envelope ID
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 ENVID requires EHLO");
                        return;
                    }
                    // ENVID value is encoded as xtext - decode it
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
                    // RFC 6152 (8BITMIME) and RFC 3030 (BINARYMIME)
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
                    // RFC 8689 - Require TLS for message transmission
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 REQUIRETLS requires EHLO");
                        return;
                    }
                    // REQUIRETLS only valid on TLS-protected connections
                    if (!isSecure()) {
                        reply(530, "5.7.10 REQUIRETLS requires TLS connection");
                        return;
                    }
                    if (deliveryRequirements == null) {
                        deliveryRequirements = new DefaultDeliveryRequirements();
                    }
                    deliveryRequirements.setRequireTls(true);
                } else if (upperParam.startsWith("MT-PRIORITY=")) {
                    // RFC 6710 - Message Transfer Priority
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
                    // RFC 4865 - FUTURERELEASE HOLDFOR
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
                    // RFC 4865 - FUTURERELEASE HOLDUNTIL
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 HOLDUNTIL requires EHLO");
                        return;
                    }
                    try {
                        // ISO 8601 format
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
                    // RFC 2852 - DELIVERBY
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 BY requires EHLO");
                        return;
                    }
                    String byValue = param.substring(3);
                    // Format: <seconds>;R or <seconds>;N
                    boolean returnOnFail = true; // Default to return
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
                // Other parameters can be parsed here if needed
            }
        }
        
        // Store SMTPUTF8 flag for this transaction
        this.smtputf8 = useSmtputf8;
        
        // Check declared SIZE against maximum (early rejection per RFC 1870)
        long maxSize = server.getMaxMessageSize();
        if (maxSize > 0 && declaredSize > maxSize) {
            reply(552, "5.3.4 Message size exceeds maximum (" + maxSize + " bytes)");
            return;
        }

        // Handle null sender (bounce messages per RFC 5321)
        EmailAddress sender = null;
        if (!fromAddrStr.isEmpty()) {
            sender = EmailAddressParser.parseEnvelopeAddress(fromAddrStr, smtputf8);
            if (sender == null) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Invalid MAIL FROM address: " + fromAddrStr);
                }
                reply(501, "5.1.7 Invalid sender address syntax");
                return;
            }
        }

        // For authenticated sessions, verify sender authorization
        String fromAddr = (sender != null) ? sender.getEnvelopeAddress() : "";
        if (authenticated && !isAuthorizedSender(fromAddr, authenticatedUser)) {
            reply(550, "5.7.1 Not authorized to send from this address");
            return;
        }

        // Store sender information for use in callback
        this.mailFrom = sender;
        this.recipients.clear();
        this.dsnRecipients.clear();
        
        // Delegate to handler for asynchronous policy evaluation
        // The handler will call acceptSender() or rejectSender*() on this (the MailFromState)
        if (mailFromHandler != null) {
            DeliveryRequirements delivery = deliveryRequirements != null ? deliveryRequirements : DefaultDeliveryRequirements.EMPTY;
            mailFromHandler.mailFrom(sender, smtputf8, delivery, this);
        } else {
            // No handler configured - accept all senders (blackhole mode)
            this.state = SMTPState.MAIL;
            reply(250, "2.1.0 Sender ok");
        }
    }

    /**
     * RCPT command - Specify message recipient.
     * @param args the arguments (TO:<email>)
     */
    private void rcpt(String args) throws IOException {
        if (state != SMTPState.MAIL && state != SMTPState.RCPT) {
            reply(503, "5.0.0 Bad sequence of commands");
            return;
        }

        // Check authentication requirement (typically for MSA on port 587)
        if (server.isAuthRequired() && !authenticated) {
            reply(530, "5.7.0 Authentication required");
            return;
        }

        if (args == null || !args.toUpperCase().startsWith("TO:")) {
            reply(501, "5.0.0 Syntax: RCPT TO:<address>");
            return;
        }

        // Check recipient limit (RCPTMAX from RFC 9422 LIMITS)
        int maxRecipients = server.getMaxRecipients();
        if (recipients.size() >= maxRecipients) {
            reply(452, "4.5.3 Too many recipients (maximum " + maxRecipients + ")");
            return;
        }

        // Parse email address and optional parameters from TO:<email> [NOTIFY=...] [ORCPT=...]
        String toArg = args.substring(3).trim();
        
        // Extract address (may be bracketed) and parameters
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
            // Non-bracketed address (legacy format) - space separates address from params
            int spaceIdx = toArg.indexOf(' ');
            if (spaceIdx > 0) {
                addressPart = toArg.substring(0, spaceIdx);
                paramsPart = toArg.substring(spaceIdx + 1).trim();
            } else {
                addressPart = toArg;
            }
        }
        
        // Parse RCPT TO parameters (NOTIFY, ORCPT per RFC 3461)
        Set<DSNNotify> dsnNotify = null;
        String orcptType = null;
        String orcptAddress = null;
        if (paramsPart != null && !paramsPart.isEmpty()) {
            for (String param : paramsPart.split("\\s+")) {
                String upperParam = param.toUpperCase();
                if (upperParam.startsWith("NOTIFY=")) {
                    // RFC 3461 - DSN notify conditions
                    if (!extendedSMTP) {
                        reply(503, "5.5.1 NOTIFY requires EHLO");
                        return;
                    }
                    String notifyValue = param.substring(7);
                    dsnNotify = EnumSet.noneOf(DSNNotify.class);
                    try {
                        for (String keyword : notifyValue.split(",")) {
                            dsnNotify.add(DSNNotify.parse(keyword.trim()));
                        }
                    } catch (IllegalArgumentException e) {
                        reply(501, "5.5.4 Invalid NOTIFY parameter");
                        return;
                    }
                    // Validate: NEVER cannot be combined with other values
                    if (dsnNotify.contains(DSNNotify.NEVER) && dsnNotify.size() > 1) {
                        reply(501, "5.5.4 NOTIFY=NEVER cannot be combined with other values");
                        return;
                    }
                } else if (upperParam.startsWith("ORCPT=")) {
                    // RFC 3461 - Original recipient
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

        // Parse and validate the recipient address
        if (addressPart.isEmpty()) {
            reply(501, "5.1.3 Invalid recipient address - empty");
            return;
        }

        EmailAddress recipient = EmailAddressParser.parseEnvelopeAddress(addressPart, smtputf8);
        if (recipient == null) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Invalid RCPT TO address: " + addressPart);
            }
            reply(501, "5.1.3 Invalid recipient address syntax");
            return;
        }
        
        // Store DSN recipient parameters for this recipient (will be associated after acceptance)
        DSNRecipientParameters pendingDsnParams = null;
        if (dsnNotify != null || orcptType != null) {
            pendingDsnParams = new DSNRecipientParameters(dsnNotify, orcptType, orcptAddress);
        }
        
        // Store temporarily for acceptRecipient callback
        this.pendingRecipientDSN = pendingDsnParams;
        this.pendingRecipient = recipient;

        // Delegate to handler for asynchronous policy evaluation
        // The handler will call acceptRecipient() or rejectRecipient*() on this (the RecipientState)
        if (recipientHandler != null) {
            recipientHandler.rcptTo(recipient, server.getMailboxFactory(), this);
        } else {
            // No handler configured - accept all recipients (blackhole mode)
            this.recipients.add(recipient);
            this.state = SMTPState.RCPT;
            reply(250, "2.1.5 " + recipient.getEnvelopeAddress() + "... Recipient ok");
        }
    }

    /**
     * DATA command - Begin message content transmission.
     * @param args should be null or empty for DATA command
     */
    private void data(String args) throws IOException {
        if (state != SMTPState.RCPT) {
            reply(503, "5.0.0 Bad sequence of commands");
            return;
        }

        // Check authentication requirement (typically for MSA on port 587)
        if (server.isAuthRequired() && !authenticated) {
            reply(530, "5.7.0 Authentication required");
            return;
        }

        if (recipients.isEmpty()) {
            reply(503, "5.0.0 Need RCPT (recipient)");
            return;
        }

        // BINARYMIME content cannot use DATA (dot-stuffing could corrupt binary)
        if (bodyType.requiresBdat()) {
            reply(503, "5.6.1 BODY=BINARYMIME requires BDAT, not DATA");
            return;
        }

        // Notify handler that message transfer is starting
        // Handler will call acceptMessage() to provide the MessageDataHandler
        if (recipientHandler != null) {
            recipientHandler.startMessage(this);
        } else {
            // No handler - just accept
            doAcceptMessage();
        }
    }
    
    /**
     * Completes the acceptance of a message and sends the 354 response.
     * Called from MessageStartState.acceptMessage() or directly if no handler.
     */
    private void doAcceptMessage() {
        // Initialize DATA state with proper buffer management
        resetDataState();
        this.state = SMTPState.DATA;
        
        try {
            reply(354, "Start mail input; end with <CRLF>.<CRLF>");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending 354 response", e);
            close();
        }
    }

    /**
     * BDAT command - Begin binary/chunked message content transmission (RFC 3030).
     * 
     * <p>BDAT allows sending message data in chunks with explicit byte counts,
     * avoiding the need for dot-stuffing. Multiple BDAT commands can be used
     * to send a message in parts, with the final chunk marked with LAST.
     * 
     * <p>Syntax: BDAT &lt;size&gt; [LAST]
     * 
     * @param args the arguments (size and optional LAST keyword)
     */
    private void bdat(String args) throws IOException {
        // BDAT requires ESMTP (EHLO must have been used)
        if (!extendedSMTP) {
            reply(503, "5.0.0 BDAT requires EHLO");
            return;
        }

        // BDAT only valid after RCPT TO
        if (state != SMTPState.RCPT) {
            reply(503, "5.0.0 Bad sequence of commands");
            return;
        }

        // Check authentication requirement (typically for MSA on port 587)
        if (server.isAuthRequired() && !authenticated) {
            reply(530, "5.7.0 Authentication required");
            return;
        }

        if (recipients.isEmpty()) {
            reply(503, "5.0.0 Need RCPT (recipient)");
            return;
        }

        // Parse BDAT arguments: <size> [LAST]
        if (args == null || args.trim().isEmpty()) {
            reply(501, "5.5.4 Syntax: BDAT size [LAST]");
            return;
        }

        String[] parts = args.trim().split("\\s+");
        if (parts.length < 1 || parts.length > 2) {
            reply(501, "5.5.4 Syntax: BDAT size [LAST]");
            return;
        }

        // Parse chunk size
        long chunkSize;
        try {
            chunkSize = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            reply(501, "5.5.4 Invalid BDAT size: " + parts[0]);
            return;
        }

        if (chunkSize < 0) {
            reply(501, "5.5.4 Invalid BDAT size: negative value");
            return;
        }

        // Check against maximum message size
        long maxSize = server.getMaxMessageSize();
        if (maxSize > 0 && dataBytesReceived + chunkSize > maxSize) {
            reply(552, "5.3.4 Message size exceeds maximum permitted");
            resetBdatState();
            return;
        }

        // Parse LAST parameter
        boolean last = false;
        if (parts.length == 2) {
            if ("LAST".equalsIgnoreCase(parts[1])) {
                last = true;
            } else {
                reply(501, "5.5.4 Invalid BDAT parameter: " + parts[1]);
                return;
            }
        }

        // If this is the first BDAT for this transaction, notify handler
        if (!bdatStarted) {
            bdatStarted = true;
            // For BDAT, we need to set up the message handler but NOT send the 354 response
            // (354 is only for DATA command). Use the special BDAT start message method.
            if (recipientHandler != null) {
                recipientHandler.startMessage(new BdatStartState());
            }
        }

        // Store BDAT state
        bdatBytesRemaining = chunkSize;
        bdatLast = last;
        state = SMTPState.BDAT;

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("BDAT: expecting " + chunkSize + " bytes" + (last ? " (LAST)" : ""));
        }

        // If chunk size is 0, handle immediately
        if (chunkSize == 0) {
            handleBdatChunkComplete();
        }
        // Otherwise, bytes will be processed in receive() -> handleBdatContent()
    }

    /**
     * RSET command - Reset the current mail transaction.
     * @param args should be null or empty for RSET command
     */
    private void rset(String args) throws IOException {
        // End current session span
        endSessionSpan("RSET");
        resetDataState();
        
        // Delegate to handler - it will call acceptReset()
        if (mailFromHandler != null) {
            mailFromHandler.reset(this);
        } else if (recipientHandler != null) {
            recipientHandler.reset(this);
        } else {
            // No handler - auto-accept reset
            resetTransaction();
            reply(250, "2.0.0 Reset state");
        }
    }

    /**
     * QUIT command - Close the connection.
     * @param args should be null or empty for QUIT command  
     */
    private void quit(String args) throws IOException {
        // End current session span
        endSessionSpan("QUIT");

        this.state = SMTPState.QUIT;
        reply(221, "2.0.0 Goodbye");
        close(); // Close the connection
        
        // Notify handler of disconnect
        if (connectedHandler != null) {
            connectedHandler.disconnected();
        }
    }

    /**
     * NOOP command - No operation.
     * @param args ignored
     */
    private void noop(String args) throws IOException {
        reply(250, "2.0.0 Ok");
    }

    /**
     * HELP command - Provide help information.
     * @param args optional command to get help for
     */
    private void help(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            // List supported commands
            replyMultiline(214, "2.0.0 Gumdrop SMTP server - supported commands:");
            replyMultiline(214, "  HELO EHLO MAIL RCPT DATA BDAT RSET");
            replyMultiline(214, "  VRFY NOOP QUIT HELP");
            if (!secure && engine != null && !starttlsUsed) {
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
            // Help for specific command
            String cmd = args.trim().toUpperCase();
            switch (cmd) {
                case "HELO":
                    reply(214, "2.0.0 HELO <hostname> - Identify client to server");
                    break;
                case "EHLO":
                    reply(214, "2.0.0 EHLO <hostname> - Extended HELO with capability negotiation");
                    break;
                case "MAIL":
                    replyMultiline(214, "2.0.0 MAIL FROM:<sender> [parameters...]");
                    replyMultiline(214, "  SIZE=<n> BODY=7BIT|8BITMIME|BINARYMIME SMTPUTF8");
                    replyMultiline(214, "  REQUIRETLS MT-PRIORITY=<-9..9> HOLDFOR=<s> HOLDUNTIL=<time>");
                    reply(214, "  BY=<s>;R|N RET=FULL|HDRS ENVID=<id>");
                    break;
                case "RCPT":
                    reply(214, "2.0.0 RCPT TO:<recipient> [NOTIFY=NEVER|SUCCESS|FAILURE|DELAY] [ORCPT=<type>;<addr>]");
                    break;
                case "DATA":
                    reply(214, "2.0.0 DATA - Start message content (end with <CRLF>.<CRLF>)");
                    break;
                case "BDAT":
                    reply(214, "2.0.0 BDAT <size> [LAST] - Send message chunk (RFC 3030 CHUNKING)");
                    break;
                case "RSET":
                    reply(214, "2.0.0 RSET - Reset transaction state");
                    break;
                case "VRFY":
                    reply(214, "2.0.0 VRFY <address> - Verify address (limited support)");
                    break;
                case "NOOP":
                    reply(214, "2.0.0 NOOP - No operation");
                    break;
                case "QUIT":
                    reply(214, "2.0.0 QUIT - Close connection");
                    break;
                case "STARTTLS":
                    reply(214, "2.0.0 STARTTLS - Upgrade to TLS encryption");
                    break;
                case "AUTH":
                    reply(214, "2.0.0 AUTH <mechanism> [initial-response] - Authenticate");
                    break;
                case "XCLIENT":
                    reply(214, "2.0.0 XCLIENT attr=value [...] - Override connection attributes (Postfix extension)");
                    break;
                default:
                    reply(504, "5.5.1 HELP not available for: " + args);
            }
        }
    }

    /**
     * VRFY command - Verify an address (not implemented).
     * @param args the address to verify
     */
    private void vrfy(String args) throws IOException {
        reply(252, "2.5.2 Cannot VRFY user, but will accept message and attempt delivery");
    }

    /**
     * EXPN command - Expand a mailing list (not implemented).
     * @param args the list to expand
     */
    private void expn(String args) throws IOException {
        reply(502, "5.5.1 EXPN not implemented");
    }

    /**
     * XCLIENT command - Override connection attributes (Postfix extension).
     * This allows authorized proxies/filters to pass original client information.
     * @param args attribute=value pairs
     * @see <a href="https://www.postfix.org/XCLIENT_README.html">Postfix XCLIENT</a>
     */
    private void xclient(String args) throws IOException {
        // Check authorization
        if (!isXclientAuthorized()) {
            reply(550, "5.7.0 XCLIENT not authorized");
            return;
        }

        // XCLIENT not allowed during mail transaction (between MAIL and end of DATA/BDAT)
        if (state == SMTPState.MAIL || state == SMTPState.RCPT || 
            state == SMTPState.DATA || state == SMTPState.BDAT) {
            reply(503, "5.5.1 Mail transaction in progress");
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, "5.5.4 Syntax: XCLIENT attribute=value [...]");
            return;
        }

        // Parse attribute=value pairs
        String[] pairs = args.trim().split("\\s+");
        for (String pair : pairs) {
            int eqIdx = pair.indexOf('=');
            if (eqIdx <= 0) {
                reply(501, "5.5.4 Invalid XCLIENT attribute syntax: " + pair);
                return;
            }

            String attr = pair.substring(0, eqIdx).toUpperCase();
            String value = decodeXtext(pair.substring(eqIdx + 1));

            // Handle [UNAVAILABLE] and [TEMPUNAVAIL] special values
            if ("[UNAVAILABLE]".equalsIgnoreCase(value) || "[TEMPUNAVAIL]".equalsIgnoreCase(value)) {
                value = null;
            }

            switch (attr) {
                case "NAME":
                    xclientName = value;
                    break;
                case "ADDR":
                    if (value != null) {
                        try {
                            // Parse IP address, keeping existing port if set
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
                    break;
                case "PORT":
                    if (value != null) {
                        try {
                            int port = Integer.parseInt(value);
                            if (port < 0 || port > 65535) {
                                reply(501, "5.5.4 Invalid PORT value: " + value);
                                return;
                            }
                            // Update port, keeping existing address
                            InetAddress addr = xclientAddr != null ? 
                                xclientAddr.getAddress() : InetAddress.getLoopbackAddress();
                            xclientAddr = new InetSocketAddress(addr, port);
                        } catch (NumberFormatException e) {
                            reply(501, "5.5.4 Invalid PORT value: " + value);
                            return;
                        }
                    }
                    break;
                case "PROTO":
                    if (value != null && !"SMTP".equalsIgnoreCase(value) && !"ESMTP".equalsIgnoreCase(value)) {
                        reply(501, "5.5.4 Invalid PROTO value: " + value);
                        return;
                    }
                    xclientProto = value;
                    if ("ESMTP".equalsIgnoreCase(value)) {
                        extendedSMTP = true;
                    }
                    break;
                case "HELO":
                    xclientHelo = value;
                    if (value != null) {
                        heloName = value;
                    }
                    break;
                case "LOGIN":
                    xclientLogin = value;
                    if (value != null) {
                        authenticated = true;
                        authenticatedUser = value;
                    } else {
                        authenticated = false;
                        authenticatedUser = null;
                    }
                    break;
                case "DESTADDR":
                    if (value != null) {
                        try {
                            InetAddress addr = InetAddress.getByName(value);
                            int port = xclientDestAddr != null ? xclientDestAddr.getPort() : 25;
                            xclientDestAddr = new InetSocketAddress(addr, port);
                        } catch (UnknownHostException e) {
                            reply(501, "5.5.4 Invalid DESTADDR value: " + value);
                            return;
                        }
                    } else {
                        xclientDestAddr = null;
                    }
                    break;
                case "DESTPORT":
                    if (value != null) {
                        try {
                            int port = Integer.parseInt(value);
                            if (port < 0 || port > 65535) {
                                reply(501, "5.5.4 Invalid DESTPORT value: " + value);
                                return;
                            }
                            InetAddress addr = xclientDestAddr != null ?
                                xclientDestAddr.getAddress() : InetAddress.getLoopbackAddress();
                            xclientDestAddr = new InetSocketAddress(addr, port);
                        } catch (NumberFormatException e) {
                            reply(501, "5.5.4 Invalid DESTPORT value: " + value);
                            return;
                        }
                    }
                    break;
                default:
                    reply(501, "5.5.4 Unknown XCLIENT attribute: " + attr);
                    return;
            }
        }

        // Reset session state to post-greeting (client must re-EHLO)
        state = SMTPState.INITIAL;
        heloName = xclientHelo; // Use XCLIENT HELO if set, otherwise cleared
        mailFrom = null;
        recipients.clear();
        dsnRecipients.clear();
        smtputf8 = false;
        bodyType = BodyType.SEVEN_BIT;
        deliveryRequirements = null;
        resetDataState();

        // Log XCLIENT usage
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("XCLIENT from " + getRemoteSocketAddress() + 
                       ": NAME=" + xclientName +
                       " ADDR=" + (xclientAddr != null ? xclientAddr.getAddress().getHostAddress() : "null"));
        }

        // Send 220 greeting as per XCLIENT spec
        String localHostname = ((InetSocketAddress) getLocalSocketAddress()).getHostName();
        reply(220, localHostname + " ESMTP Gumdrop");
    }

    /**
     * Decodes xtext encoding per RFC 1891.
     * In xtext, '+' followed by two hex digits represents a character.
     */
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

    /**
     * Checks if the current client is authorized to use XCLIENT.
     * Checks if the client address is in the server's XCLIENT authorized list.
     */
    private boolean isXclientAuthorized() {
        if (channel == null) {
            return false;
        }
        try {
            InetSocketAddress clientAddr = (InetSocketAddress) channel.getRemoteAddress();
            if (clientAddr != null && server != null) {
                return server.isXclientAuthorized(clientAddr.getAddress());
            }
        } catch (IOException e) {
            // Ignore
        }
        return false;
    }

    /**
     * STARTTLS command - Start TLS encryption.
     * 
     * <p>STARTTLS is handled automatically by the connection when available.
     * After successful TLS upgrade, the handler is notified via
     * {@link HelloHandler#tlsEstablished}.
     * 
     * @param args should be null or empty for STARTTLS command
     */
    private void starttls(String args) throws IOException {
        // Check if TLS is already active
        if (isSecure()) {
            reply(454, "4.7.0 TLS already active");
            return;
        }
        
        // Check if STARTTLS is available (connector must have SSL context)
        if (!server.isSTARTTLSAvailable()) {
            reply(454, "4.7.0 TLS not available");
            return;
        }
        
        // STARTTLS must be issued before authentication
        if (state != SMTPState.INITIAL && state != SMTPState.READY) {
            reply(503, "5.0.0 Bad sequence of commands");
            return;
        }
        
        // STARTTLS is automatically accepted - no handler decision needed
        doStarttls();
    }
    
    /**
     * Performs the actual STARTTLS upgrade after handler acceptance.
     */
    private void doStarttls() {
        try {
            // Send success response - after this, client expects TLS handshake
            reply(220, "2.0.0 Ready to start TLS");

            // Initialize SSL state using the existing engine
            initializeSSLState();
            
            // Reset SMTP state after TLS upgrade
            state = SMTPState.INITIAL;
            heloName = null;
            extendedSMTP = false;
            starttlsUsed = true;
            addSessionAttribute("smtp.starttls", true);
            addSessionEvent("STARTTLS");

            // Record STARTTLS metric
            SMTPServerMetrics metrics = getServerMetrics();
            if (metrics != null) {
                metrics.starttlsUpgraded();
            }
            
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info("STARTTLS upgrade initiated for " + getRemoteSocketAddress());
            }
            
        } catch (Exception e) {
            try {
                reply(454, "4.3.0 TLS not available due to temporary reason");
            } catch (IOException ioe) {
                // Ignore
            }
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "STARTTLS failed", e);
            }
        }
    }
    
    /**
     * Called when TLS handshake completes.
     * Notifies the handler that TLS is now established.
     */
    @Override
    protected void handshakeComplete(String protocol) {
        super.handshakeComplete(protocol);
        
        // Notify handler of TLS establishment
        if (helloHandler != null && starttlsUsed) {
            TLSInfo tlsInfo = createTLSInfo();
            helloHandler.tlsEstablished(tlsInfo);
        }
    }

    /**
     * AUTH command - SMTP authentication.
     * Supports PLAIN and LOGIN mechanisms using the configured Realm.
     * @param args authentication mechanism and optional initial response
     */
    private void auth(String args) throws IOException {
        // Check if realm is configured
        if (getRealm() == null) {
            reply(502, "5.5.1 Authentication not available");
            return;
        }

        // AUTH only allowed after EHLO
        if (!extendedSMTP) {
            reply(503, "5.0.0 AUTH requires EHLO");
            return;
        }

        // Can't authenticate when already authenticated
        if (authenticated) {
            reply(503, "5.0.0 Already authenticated");
            return;
        }

        // AUTH requires secure connection or STARTTLS
        if (!isSecure() && !server.isSTARTTLSAvailable()) {
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
            mechanism = trimmedArgs.substring(0, spaceIdx).toUpperCase();
            initialResponse = trimmedArgs.substring(spaceIdx + 1).trim();
            if (initialResponse.isEmpty()) {
                initialResponse = null;
            }
        } else {
            mechanism = trimmedArgs.toUpperCase();
        }

        switch (mechanism) {
            case "PLAIN":
                handleAuthPlain(initialResponse);
                break;
            case "LOGIN":
                handleAuthLogin(initialResponse);
                break;
            case "CRAM-MD5":
                handleAuthCramMD5(initialResponse);
                break;
            case "DIGEST-MD5":
                handleAuthDigestMD5(initialResponse);
                break;
            case "SCRAM-SHA-256":
                handleAuthScramSHA256(initialResponse);
                break;
            case "OAUTHBEARER":
                handleAuthOAuthBearer(initialResponse);
                break;
            case "GSSAPI":
                handleAuthGSSAPI(initialResponse);
                break;
            case "EXTERNAL":
                handleAuthExternal(initialResponse);
                break;
            case "NTLM":
                handleAuthNTLM(initialResponse);
                break;
            default:
                reply(504, "5.5.4 Authentication mechanism not supported");
        }
    }

    /**
     * Handles AUTH PLAIN mechanism.
     * Format: base64(authzid\0username\0password)
     * @param initialResponse optional base64-encoded credentials
     */
    private void handleAuthPlain(String initialResponse) throws IOException {
        try {
            String credentials;
            if (initialResponse != null && !initialResponse.equals("=")) {
                // Initial response provided
                credentials = initialResponse;
            } else {
                // Request credentials
                reply(334, ""); // Empty challenge for PLAIN
                authState = AuthState.PLAIN_RESPONSE;
                authMechanism = "PLAIN";
                return;
            }

            // Decode base64 credentials
            byte[] decoded = Base64.getDecoder().decode(credentials);
            String authString = new String(decoded, US_ASCII);

            // Parse authzid\0username\0password
            String[] parts = authString.split("\0", -1);
            if (parts.length != 3) {
                reply(535, "5.7.8 Authentication credentials invalid");
                resetAuthState();
                return;
            }

            String authzid = parts[0]; // Authorization identity (can be empty)
            String username = parts[1];
            String password = parts[2];

            if (username.isEmpty() || password.isEmpty()) {
                reply(535, "5.7.8 Authentication credentials invalid");
                resetAuthState();
                return;
            }

            // Authenticate against realm
            if (authenticateUser(username, password)) {
                notifyAuthenticationSuccess(username, "PLAIN");
            } else {
                notifyAuthenticationFailure(username, "PLAIN");
            }
            resetAuthState();

        } catch (Exception e) {
            reply(535, "5.7.8 Authentication credentials invalid");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "AUTH PLAIN error", e);
            }
        }
    }

    /**
     * Handles AUTH LOGIN mechanism.
     * Interactive: server challenges for username, then password.
     * @param initialResponse optional base64-encoded username
     */
    private void handleAuthLogin(String initialResponse) throws IOException {
        try {
            if (initialResponse != null && !initialResponse.equals("=")) {
                // Initial response is username
                byte[] decoded = Base64.getDecoder().decode(initialResponse);
                String username = new String(decoded, US_ASCII);
                
                if (username.isEmpty()) {
                    reply(535, "5.7.8 Authentication credentials invalid");
                    resetAuthState();
                    return;
                }

                pendingAuthUsername = username;
                authState = AuthState.LOGIN_PASSWORD;
                authMechanism = "LOGIN";
                
                // Challenge for password
                String passwordPrompt = Base64.getEncoder().encodeToString("Password:".getBytes(US_ASCII));
                reply(334, passwordPrompt);
            } else {
                // Start LOGIN sequence - challenge for username
                authState = AuthState.LOGIN_USERNAME;
                authMechanism = "LOGIN";
                
                String usernamePrompt = Base64.getEncoder().encodeToString("Username:".getBytes(US_ASCII));
                reply(334, usernamePrompt);
            }

        } catch (Exception e) {
            reply(535, "5.7.8 Authentication credentials invalid");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "AUTH LOGIN error", e);
            }
        }
    }

    /**
     * Handles authentication data during AUTH LOGIN sequence.
     * @param data the base64-encoded authentication data
     */
    private void handleAuthData(String data) throws IOException {
        try {
            switch (authState) {
                case PLAIN_RESPONSE:
                    // Handle PLAIN credentials that were requested with empty challenge
                    handleAuthPlain(data);
                    break;

                case LOGIN_USERNAME:
                    // Decode username
                    byte[] decoded = Base64.getDecoder().decode(data);
                    String username = new String(decoded, US_ASCII);
                    
                    if (username.isEmpty()) {
                        reply(535, "5.7.8 Authentication credentials invalid");
                        resetAuthState();
                        return;
                    }

                    pendingAuthUsername = username;
                    authState = AuthState.LOGIN_PASSWORD;
                    
                    // Challenge for password
                    String passwordPrompt = Base64.getEncoder().encodeToString("Password:".getBytes(US_ASCII));
                    reply(334, passwordPrompt);
                    break;

                case LOGIN_PASSWORD:
                    // Decode password and authenticate
                    decoded = Base64.getDecoder().decode(data);
                    String password = new String(decoded, US_ASCII);

                    if (password.isEmpty()) {
                        reply(535, "5.7.8 Authentication credentials invalid");
                        resetAuthState();
                        return;
                    }

                    // Authenticate against realm
                    if (authenticateUser(pendingAuthUsername, password)) {
                        authenticated = true;
                        authenticatedUser = pendingAuthUsername;
                        reply(235, "2.7.0 Authentication successful");
                        if (LOGGER.isLoggable(Level.INFO)) {
                            LOGGER.info("SMTP AUTH LOGIN successful for user: " + pendingAuthUsername);
                        }
                        
                        // Authentication state is tracked internally
                    } else {
                        reply(535, "5.7.8 Authentication credentials invalid");
                        if (LOGGER.isLoggable(Level.WARNING)) {
                            LOGGER.warning("SMTP AUTH LOGIN failed for user: " + pendingAuthUsername);
                        }
                    }
                    resetAuthState();
                    break;

                case CRAM_MD5_RESPONSE:
                    // Handle CRAM-MD5 response using shared SASL utilities
                    String cramResponse = SASLUtils.decodeBase64ToString(data);
                    
                    int spaceIndex = cramResponse.indexOf(' ');
                    if (spaceIndex <= 0) {
                        reply(535, "5.7.8 Invalid CRAM-MD5 response format");
                        resetAuthState();
                        return;
                    }
                    
                    String cramUsername = cramResponse.substring(0, spaceIndex);
                    String expectedHmac = cramResponse.substring(spaceIndex + 1);
                    
                    // Validate against realm using getCramMD5Response
                    if (getRealm() != null) {
                        try {
                            String computedHmac = getRealm().getCramMD5Response(cramUsername, authChallenge);
                            if (computedHmac != null && expectedHmac.equalsIgnoreCase(computedHmac)) {
                                authenticated = true;
                                authenticatedUser = cramUsername;
                                reply(235, "2.7.0 Authentication successful");
                                
                                if (LOGGER.isLoggable(Level.INFO)) {
                                    LOGGER.info("SMTP CRAM-MD5 successful for user: " + cramUsername);
                                }
                                
                                // Authentication state is tracked internally
                            } else {
                                reply(535, "5.7.8 Authentication credentials invalid");
                                if (LOGGER.isLoggable(Level.WARNING)) {
                                    LOGGER.warning("SMTP CRAM-MD5 failed for user: " + cramUsername);
                                }
                            }
                        } catch (UnsupportedOperationException e) {
                            // Realm doesn't support CRAM-MD5
                            reply(535, "5.7.8 CRAM-MD5 authentication not supported by this realm");
                            if (LOGGER.isLoggable(Level.WARNING)) {
                                LOGGER.warning("SMTP CRAM-MD5 not supported: " + e.getMessage());
                            }
                        }
                    } else {
                        reply(535, "5.7.8 Authentication credentials invalid");
                    }
                    resetAuthState();
                    break;

                case DIGEST_MD5_RESPONSE:
                    // Handle DIGEST-MD5 response using shared SASL utilities
                    String digestResponse = SASLUtils.decodeBase64ToString(data);
                    
                    // Parse digest response parameters using shared utility
                    Map<String, String> params = SASLUtils.parseDigestParams(digestResponse);
                    String digestUsername = params.get("username");
                    String responseHash = params.get("response");
                    
                    if (digestUsername != null && responseHash != null) {
                        // Simplified validation for DIGEST-MD5 - check if user exists by trying to get HA1
                        if (getRealm() != null) {
                            String hostname = ((InetSocketAddress) getLocalSocketAddress()).getHostName();
                            String ha1 = getRealm().getDigestHA1(digestUsername, hostname);
                            if (ha1 != null) {
                                // User exists - for simplified implementation, accept the response
                                // A full implementation would validate the computed response hash
                                authenticated = true;
                                authenticatedUser = digestUsername;
                                reply(235, "2.7.0 Authentication successful");
                                
                                if (LOGGER.isLoggable(Level.INFO)) {
                                    LOGGER.info("SMTP DIGEST-MD5 successful for user: " + digestUsername);
                                }
                                
                                // Authentication state is tracked internally
                            } else {
                                reply(535, "5.7.8 Authentication credentials invalid");
                                if (LOGGER.isLoggable(Level.WARNING)) {
                                    LOGGER.warning("SMTP DIGEST-MD5 failed: user not found: " + digestUsername);
                                }
                            }
                        } else {
                            reply(535, "5.7.8 Authentication credentials invalid");
                        }
                    } else {
                        reply(535, "5.7.8 Invalid DIGEST-MD5 response format");
                    }
                    resetAuthState();
                    break;

                case SCRAM_INITIAL:
                    // Handle SCRAM client first message
                    processScramClientFirst(data);
                    break;

                case SCRAM_FINAL:
                    // Handle SCRAM client final message using getScramCredentials
                    decoded = Base64.getDecoder().decode(data);
                    String scramFinal = new String(decoded, US_ASCII);
                    
                    if (pendingAuthUsername != null && getRealm() != null) {
                        try {
                            Realm.ScramCredentials creds = getRealm().getScramCredentials(pendingAuthUsername);
                            if (creds != null) {
                                // User exists and SCRAM credentials available
                                // TODO: Validate SCRAM proof using creds.storedKey
                                // For now, accept if credentials exist (simplified implementation)
                                authenticated = true;
                                authenticatedUser = pendingAuthUsername;
                                reply(235, "2.7.0 Authentication successful");
                                
                                if (LOGGER.isLoggable(Level.INFO)) {
                                    LOGGER.info("SMTP SCRAM-SHA-256 successful for user: " + pendingAuthUsername);
                                }
                                
                                // Authentication state is tracked internally
                            } else {
                                reply(535, "5.7.8 Authentication credentials invalid");
                                if (LOGGER.isLoggable(Level.WARNING)) {
                                    LOGGER.warning("SMTP SCRAM-SHA-256 failed: user not found: " + pendingAuthUsername);
                                }
                            }
                        } catch (UnsupportedOperationException e) {
                            // Realm doesn't support SCRAM
                            reply(535, "5.7.8 SCRAM-SHA-256 authentication not supported by this realm");
                            if (LOGGER.isLoggable(Level.WARNING)) {
                                LOGGER.warning("SMTP SCRAM-SHA-256 not supported: " + e.getMessage());
                            }
                        }
                    } else {
                        reply(535, "5.7.8 Authentication credentials invalid");
                    }
                    resetAuthState();
                    break;

                case OAUTH_RESPONSE:
                    // Handle OAuth bearer token
                    processOAuthBearer(data);
                    break;

                case GSSAPI_EXCHANGE:
                    // Handle GSSAPI token exchange
                    decoded = Base64.getDecoder().decode(data);
                    
                    try {
                        byte[] challenge = saslServer.evaluateResponse(decoded);
                        
                        if (saslServer.isComplete()) {
                            // Authentication successful
                            String authzid = saslServer.getAuthorizationID();
                            authenticated = true;
                            authenticatedUser = authzid;
                            reply(235, "2.7.0 Authentication successful");
                            
                            if (LOGGER.isLoggable(Level.INFO)) {
                                LOGGER.info("SMTP GSSAPI successful for user: " + authzid);
                            }
                            
                            // Authentication state is tracked internally
                            resetAuthState();
                        } else {
                            // Continue the exchange
                            String challengeB64 = Base64.getEncoder().encodeToString(challenge);
                            reply(334, challengeB64);
                        }
                    } catch (SaslException e) {
                        reply(535, "5.7.8 GSSAPI authentication failed");
                        resetAuthState();
                        if (LOGGER.isLoggable(Level.WARNING)) {
                            LOGGER.log(Level.WARNING, "GSSAPI token exchange error", e);
                        }
                    }
                    break;

                case NTLM_TYPE1:
                    // Handle NTLM Type 1 message
                    decoded = Base64.getDecoder().decode(data);
                    
                    // Parse Type 1 message (simplified validation)
                    if (decoded.length < 12 || 
                        !new String(decoded, 0, 8, US_ASCII).equals("NTLMSSP\0")) {
                        reply(535, "5.7.8 Invalid NTLM Type 1 message");
                        resetAuthState();
                        return;
                    }
                    
                    // Generate Type 2 challenge message
                    try {
                        byte[] type2Message = generateNTLMType2Challenge();
                        String challengeB64 = Base64.getEncoder().encodeToString(type2Message);
                        
                        reply(334, challengeB64);
                        authState = AuthState.NTLM_TYPE3;
                    } catch (Exception e) {
                        reply(535, "5.7.8 NTLM challenge generation failed");
                        resetAuthState();
                        if (LOGGER.isLoggable(Level.WARNING)) {
                            LOGGER.log(Level.WARNING, "NTLM Type 2 generation error", e);
                        }
                    }
                    break;

                case NTLM_TYPE3:
                    // Handle NTLM Type 3 response
                    decoded = Base64.getDecoder().decode(data);
                    
                    try {
                        // Parse Type 3 message to extract username (simplified)
                        String ntlmUsername = parseNTLMType3Username(decoded);
                        
                        if (ntlmUsername != null && getRealm() != null) {
                            // Check if user exists using the new userExists() method
                            // A full implementation would validate the NTLM response hash
                            if (getRealm().userExists(ntlmUsername)) {
                                authenticated = true;
                                authenticatedUser = ntlmUsername;
                                reply(235, "2.7.0 Authentication successful");
                                
                                if (LOGGER.isLoggable(Level.INFO)) {
                                    LOGGER.info("SMTP NTLM successful for user: " + ntlmUsername);
                                }
                                
                                // Authentication state is tracked internally
                            } else {
                                reply(535, "5.7.8 Authentication credentials invalid");
                                if (LOGGER.isLoggable(Level.WARNING)) {
                                    LOGGER.warning("SMTP NTLM failed: user not found: " + ntlmUsername);
                                }
                            }
                        } else {
                            reply(535, "5.7.8 Authentication credentials invalid");
                        }
                    } catch (Exception e) {
                        reply(535, "5.7.8 NTLM Type 3 processing failed");
                        if (LOGGER.isLoggable(Level.WARNING)) {
                            LOGGER.log(Level.WARNING, "NTLM Type 3 parsing error", e);
                        }
                    }
                    resetAuthState();
                    break;

                default:
                    // Shouldn't happen
                    reply(503, "5.0.0 Bad sequence of commands");
                    resetAuthState();
            }

        } catch (Exception e) {
            reply(535, "5.7.8 Authentication credentials invalid");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "AUTH data handling error", e);
            }
        }
    }

    /**
     * Handles AUTH CRAM-MD5 mechanism (RFC 2195).
     * Uses HMAC-MD5 challenge-response authentication.
     * @param initialResponse should be null for CRAM-MD5
     */
    private void handleAuthCramMD5(String initialResponse) throws IOException {
        if (initialResponse != null) {
            reply(501, "5.5.4 CRAM-MD5 does not support initial response");
            return;
        }

        try {
            // Generate challenge: timestamp.pid@hostname
            long timestamp = System.currentTimeMillis();
            String hostname = ((InetSocketAddress) getLocalSocketAddress()).getHostName();
            authChallenge = "<" + timestamp + ".gumdrop@" + hostname + ">";
            
            // Send base64-encoded challenge
            String challengeB64 = Base64.getEncoder().encodeToString(authChallenge.getBytes(US_ASCII));
            reply(334, challengeB64);
            
            authState = AuthState.CRAM_MD5_RESPONSE;
            authMechanism = "CRAM-MD5";
            
        } catch (Exception e) {
            reply(535, "5.7.8 Authentication system error");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "CRAM-MD5 challenge generation error", e);
            }
        }
    }

    /**
     * Handles AUTH DIGEST-MD5 mechanism (RFC 2831).
     * HTTP Digest-style challenge-response authentication.
     * @param initialResponse should be null for DIGEST-MD5
     */
    private void handleAuthDigestMD5(String initialResponse) throws IOException {
        if (initialResponse != null) {
            reply(501, "5.5.4 DIGEST-MD5 does not support initial response");
            return;
        }

        try {
            // Generate nonce
            SecureRandom random = new SecureRandom();
            byte[] nonceBytes = new byte[16];
            random.nextBytes(nonceBytes);
            authNonce = Base64.getEncoder().encodeToString(nonceBytes);
            
            // Build challenge
            StringBuilder challenge = new StringBuilder();
            String hostname = ((InetSocketAddress) getLocalSocketAddress()).getHostName();
            challenge.append("realm=\"").append(hostname).append("\",");
            challenge.append("nonce=\"").append(authNonce).append("\",");
            challenge.append("qop=\"auth\",");
            challenge.append("algorithm=\"md5-sess\",");
            challenge.append("charset=\"utf-8\"");
            
            authChallenge = challenge.toString();
            
            // Send base64-encoded challenge
            String challengeB64 = Base64.getEncoder().encodeToString(authChallenge.getBytes(US_ASCII));
            reply(334, challengeB64);
            
            authState = AuthState.DIGEST_MD5_RESPONSE;
            authMechanism = "DIGEST-MD5";
            
        } catch (Exception e) {
            reply(535, "5.7.8 Authentication system error");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "DIGEST-MD5 challenge generation error", e);
            }
        }
    }

    /**
     * Handles AUTH SCRAM-SHA-256 mechanism (RFC 5802).
     * Salted Challenge Response Authentication Mechanism using SHA-256.
     * @param initialResponse client-first-message or null
     */
    private void handleAuthScramSHA256(String initialResponse) throws IOException {
        try {
            if (initialResponse == null) {
                // Request client first message
                reply(334, ""); // Empty challenge
                authState = AuthState.SCRAM_INITIAL;
                authMechanism = "SCRAM-SHA-256";
                return;
            }
            
            // Process client-first-message directly
            processScramClientFirst(initialResponse);
            
        } catch (Exception e) {
            reply(535, "5.7.8 Authentication credentials invalid");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "SCRAM-SHA-256 error", e);
            }
        }
    }

    /**
     * Handles AUTH OAUTHBEARER mechanism (RFC 7628).
     * OAuth 2.0 bearer token authentication.
     * @param initialResponse base64-encoded bearer token or null
     */
    private void handleAuthOAuthBearer(String initialResponse) throws IOException {
        try {
            String bearerData;
            if (initialResponse != null && !initialResponse.equals("=")) {
                // Initial response provided
                bearerData = initialResponse;
            } else {
                // Request bearer token
                reply(334, ""); // Empty challenge
                authState = AuthState.OAUTH_RESPONSE;
                authMechanism = "OAUTHBEARER";
                return;
            }

            // Process bearer token
            processOAuthBearer(bearerData);
            
        } catch (Exception e) {
            reply(535, "5.7.8 Authentication credentials invalid");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "OAUTHBEARER error", e);
            }
        }
    }

    /**
     * Handles AUTH GSSAPI mechanism (RFC 4752).
     * Uses SASL GSSAPI for Kerberos/GSS-API authentication.
     * @param initialResponse optional initial GSSAPI token
     */
    private void handleAuthGSSAPI(String initialResponse) throws IOException {
        try {
            if (saslServer == null) {
                // Create SASL GSSAPI server
                Map<String, Object> props = new HashMap<>();
                props.put(Sasl.QOP, "auth"); // Authentication only, no integrity/confidentiality
                
                String hostname = ((InetSocketAddress) getLocalSocketAddress()).getHostName();
                saslServer = Sasl.createSaslServer("GSSAPI", "smtp", hostname, props, null);
                
                if (saslServer == null) {
                    reply(535, "5.7.8 GSSAPI authentication not available");
                    resetAuthState();
                    return;
                }
                
                authState = AuthState.GSSAPI_EXCHANGE;
                authMechanism = "GSSAPI";
            }
            
            byte[] challenge = null;
            if (initialResponse != null && !initialResponse.equals("=")) {
                // Process initial response
                byte[] response = Base64.getDecoder().decode(initialResponse);
                challenge = saslServer.evaluateResponse(response);
            } else {
                // Start the exchange
                challenge = saslServer.evaluateResponse(new byte[0]);
            }
            
            if (saslServer.isComplete()) {
                // Authentication successful
                String authzid = saslServer.getAuthorizationID();
                authenticated = true;
                authenticatedUser = authzid;
                reply(235, "2.7.0 Authentication successful");
                
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info("SMTP GSSAPI successful for user: " + authzid);
                }
                
                // Authentication state is tracked internally
                resetAuthState();
            } else {
                // Send challenge and continue
                String challengeB64 = Base64.getEncoder().encodeToString(challenge);
                reply(334, challengeB64);
            }
            
        } catch (SaslException e) {
            reply(535, "5.7.8 GSSAPI authentication failed");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "GSSAPI authentication error", e);
            }
        } catch (Exception e) {
            reply(535, "5.7.8 Authentication system error");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "GSSAPI error", e);
            }
        }
    }

    /**
     * Handles AUTH EXTERNAL mechanism (RFC 4422).
     * Uses TLS client certificate for authentication.
     * @param initialResponse optional authorization identity
     */
    private void handleAuthExternal(String initialResponse) throws IOException {
        try {
            // Check if we have a secure connection with client certificate
            if (!isSecure()) {
                reply(538, "5.7.11 EXTERNAL requires secure connection");
                return;
            }
            
            // Extract client certificate from SSL session
            clientCertificate = getClientCertificate();
            if (clientCertificate == null) {
                reply(535, "5.7.8 No client certificate provided");
                return;
            }
            
            // Extract username from certificate (typically CN or email)
            String certSubject = clientCertificate.getSubjectX500Principal().getName();
            String username = extractUsernameFromCertificate(certSubject);
            
            if (username == null) {
                reply(535, "5.7.8 Cannot extract username from certificate");
                return;
            }
            
            // If initial response provided, use it as authorization identity
            String authzid = username;
            if (initialResponse != null && !initialResponse.equals("=")) {
                byte[] decoded = Base64.getDecoder().decode(initialResponse);
                String requestedAuthzid = new String(decoded, US_ASCII);
                if (!requestedAuthzid.isEmpty()) {
                    authzid = requestedAuthzid;
                }
            }
            
            // Validate certificate against realm using userExists()
            if (getRealm() != null) {
                if (getRealm().userExists(username)) {
                    // User exists - certificate authentication successful
                    authenticated = true;
                    authenticatedUser = authzid;
                    reply(235, "2.7.0 Authentication successful");
                    
                    if (LOGGER.isLoggable(Level.INFO)) {
                        LOGGER.info("SMTP EXTERNAL successful for user: " + authzid + " (cert: " + username + ")");
                    }
                    
                    // Authentication state is tracked internally
                } else {
                    reply(535, "5.7.8 Certificate authentication failed");
                    if (LOGGER.isLoggable(Level.WARNING)) {
                        LOGGER.warning("SMTP EXTERNAL failed: user not found: " + username);
                    }
                }
            } else {
                reply(535, "5.7.8 Authentication not available");
            }
            
            resetAuthState();
            
        } catch (Exception e) {
            reply(535, "5.7.8 Authentication credentials invalid");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "EXTERNAL authentication error", e);
            }
        }
    }

    /**
     * Handles AUTH NTLM mechanism.
     * Uses NTLM challenge-response authentication (Microsoft proprietary).
     * @param initialResponse optional Type 1 message
     */
    private void handleAuthNTLM(String initialResponse) throws IOException {
        try {
            if (initialResponse != null && !initialResponse.equals("=")) {
                // Process Type 1 message (NTLM negotiation)
                byte[] type1Message = Base64.getDecoder().decode(initialResponse);
                
                // Parse Type 1 message (simplified)
                if (type1Message.length < 12 || 
                    !new String(type1Message, 0, 8, US_ASCII).equals("NTLMSSP\0")) {
                    reply(535, "5.7.8 Invalid NTLM Type 1 message");
                    resetAuthState();
                    return;
                }
                
                // Generate Type 2 challenge message
                byte[] type2Message = generateNTLMType2Challenge();
                String challengeB64 = Base64.getEncoder().encodeToString(type2Message);
                
                reply(334, challengeB64);
                authState = AuthState.NTLM_TYPE3;
                authMechanism = "NTLM";
                
            } else {
                // Request Type 1 message
                reply(334, ""); // Empty challenge to request Type 1
                authState = AuthState.NTLM_TYPE1;
                authMechanism = "NTLM";
            }
            
        } catch (Exception e) {
            reply(535, "5.7.8 NTLM authentication failed");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "NTLM authentication error", e);
            }
        }
    }

    /**
     * Authenticates a user against the configured realm.
     * @param username the username
     * @param password the password
     * @return true if authentication succeeds, false otherwise
     */
    private boolean authenticateUser(String username, String password) {
        if (getRealm() == null) {
            return false;
        }

        return getRealm().passwordMatch(username, password);
    }

    /**
     * Processes SCRAM-SHA-256 client first message.
     * @param clientFirst base64-encoded client first message
     */
    private void processScramClientFirst(String clientFirst) throws IOException {
        try {
            // Decode client first message
            byte[] decoded = Base64.getDecoder().decode(clientFirst);
            String message = new String(decoded, US_ASCII);
            
            // Parse: n,,n=username,r=clientNonce
            if (!message.startsWith("n,,")) {
                reply(535, "5.7.8 Invalid SCRAM client first message");
                resetAuthState();
                return;
            }
            
            String[] parts = message.substring(3).split(",");
            String username = null;
            String clientNonce = null;
            
            for (String part : parts) {
                if (part.startsWith("n=")) {
                    username = part.substring(2);
                } else if (part.startsWith("r=")) {
                    clientNonce = part.substring(2);
                }
            }
            
            if (username == null || clientNonce == null) {
                reply(535, "5.7.8 Invalid SCRAM client first message");
                resetAuthState();
                return;
            }
            
            // Generate server nonce and salt
            SecureRandom random = new SecureRandom();
            byte[] serverNonceBytes = new byte[18];
            random.nextBytes(serverNonceBytes);
            String serverNonce = Base64.getEncoder().encodeToString(serverNonceBytes);
            
            authSalt = new byte[16];
            random.nextBytes(authSalt);
            
            authClientNonce = clientNonce;
            authNonce = clientNonce + serverNonce;
            pendingAuthUsername = username;
            
            // Build server first message
            String serverFirst = "r=" + authNonce + ",s=" + Base64.getEncoder().encodeToString(authSalt) + ",i=" + authIterations;
            String serverFirstB64 = Base64.getEncoder().encodeToString(serverFirst.getBytes(US_ASCII));
            
            reply(334, serverFirstB64);
            authState = AuthState.SCRAM_FINAL;
            
        } catch (Exception e) {
            reply(535, "5.7.8 Authentication credentials invalid");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "SCRAM client first processing error", e);
            }
        }
    }

    /**
     * Processes OAuth Bearer token.
     * @param bearerData base64-encoded bearer token data
     */
    private void processOAuthBearer(String bearerData) throws IOException {
        try {
            // Decode bearer token
            byte[] decoded = Base64.getDecoder().decode(bearerData);
            String tokenString = new String(decoded, "UTF-8");
            
            // Parse bearer token format: n,a=authzid,^Ahost=hostname^Aport=port^Aauth=Bearer token^A^A
            String[] parts = tokenString.split("\1"); // ASCII 0x01 separator
            String username = null;
            String token = null;
            
            for (String part : parts) {
                if (part.startsWith("a=")) {
                    username = part.substring(2);
                } else if (part.startsWith("auth=Bearer ")) {
                    token = part.substring(12);
                }
            }
            
            if (username == null || token == null) {
                reply(535, "5.7.8 Invalid bearer token format");
                resetAuthState();
                return;
            }
            
            // Validate bearer token using realm (if it supports token validation)
            Realm realm = getRealm();
            if (realm != null) {
                // Check if realm supports bearer token validation
                try {
                    Realm.TokenValidationResult result = realm.validateBearerToken(token);
                    if (result != null && result.valid && result.username.equals(username)) {
                        authenticated = true;
                        authenticatedUser = username;
                        reply(235, "2.7.0 Authentication successful");
                        
                        if (LOGGER.isLoggable(Level.INFO)) {
                            LOGGER.info("SMTP OAUTHBEARER successful for user: " + username);
                        }
                        
                        // Authentication state is tracked internally
                    } else {
                        reply(535, "5.7.8 Invalid bearer token");
                        if (LOGGER.isLoggable(Level.WARNING)) {
                            LOGGER.warning("SMTP OAUTHBEARER failed for user: " + username);
                        }
                    }
                } catch (UnsupportedOperationException e) {
                    // Realm doesn't support bearer tokens
                    reply(535, "5.7.8 Bearer token authentication not supported");
                }
            } else {
                reply(535, "5.7.8 Bearer token authentication not available");
            }
            
            resetAuthState();
            
        } catch (Exception e) {
            reply(535, "5.7.8 Authentication credentials invalid");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "OAuth bearer processing error", e);
            }
        }
    }


    /**
     * Extracts the client certificate from the SSL session.
     * @return the client certificate, or null if none provided
     */
    private X509Certificate getClientCertificate() {
        try {
            // Get SSL engine and session from Connection base class
            if (engine != null && engine.getSession() != null) {
                java.security.cert.Certificate[] certs = engine.getSession().getPeerCertificates();
                if (certs != null && certs.length > 0 && certs[0] instanceof X509Certificate) {
                    return (X509Certificate) certs[0];
                }
            }
        } catch (Exception e) {
            // No client certificate or SSL session
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "No client certificate available", e);
            }
        }
        return null;
    }

    /**
     * Extracts username from X.509 certificate subject.
     * @param certSubject the certificate subject DN
     * @return extracted username, or null if not found
     */
    private String extractUsernameFromCertificate(String certSubject) {
        // Try to extract from Common Name (CN)
        String[] parts = certSubject.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("CN=")) {
                String cn = part.substring(3);
                // Remove quotes if present
                if (cn.startsWith("\"") && cn.endsWith("\"")) {
                    cn = cn.substring(1, cn.length() - 1);
                }
                return cn;
            }
            // Also try emailAddress
            if (part.startsWith("emailAddress=") || part.startsWith("1.2.840.113549.1.9.1=")) {
                int equalPos = part.indexOf('=');
                if (equalPos > 0) {
                    return part.substring(equalPos + 1);
                }
            }
        }
        return null;
    }

    /**
     * Generates NTLM Type 2 challenge message.
     * @return the Type 2 challenge message bytes
     */
    private byte[] generateNTLMType2Challenge() throws Exception {
        // Generate 8-byte challenge
        SecureRandom random = new SecureRandom();
        ntlmChallenge = new byte[8];
        random.nextBytes(ntlmChallenge);
        
        // Get target name (hostname)
        String hostname = ((InetSocketAddress) getLocalSocketAddress()).getHostName();
        byte[] targetName = hostname.toUpperCase().getBytes("UTF-16LE");
        
        // Build Type 2 message (simplified)
        ByteBuffer buffer = ByteBuffer.allocate(48 + targetName.length);
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        
        // NTLM signature
        buffer.put("NTLMSSP\0".getBytes(US_ASCII));
        // Message type (2)
        buffer.putInt(2);
        // Target name length and allocation
        buffer.putShort((short) targetName.length);
        buffer.putShort((short) targetName.length);
        buffer.putInt(48); // Offset to target name
        // Flags (0x00008205: Unicode, OEM, NTLM, Target Type Domain)
        buffer.putInt(0x00008205);
        // Challenge
        buffer.put(ntlmChallenge);
        // Context (8 bytes of zeros)
        buffer.putLong(0);
        // Target info length and allocation (none for simplified version)
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(48 + targetName.length);
        // Target name data
        buffer.put(targetName);
        
        return buffer.array();
    }

    /**
     * Parses NTLM Type 3 message to extract username.
     * @param type3Message the NTLM Type 3 message bytes
     * @return extracted username, or null if parsing fails
     */
    private String parseNTLMType3Username(byte[] type3Message) {
        try {
            if (type3Message.length < 64 || 
                !new String(type3Message, 0, 8, US_ASCII).equals("NTLMSSP\0")) {
                return null;
            }
            
            ByteBuffer buffer = ByteBuffer.wrap(type3Message);
            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            
            // Skip to username field (offset 36)
            buffer.position(36);
            int usernameLength = buffer.getShort() & 0xFFFF;
            buffer.getShort(); // Skip allocated space
            int usernameOffset = buffer.getInt();
            
            if (usernameOffset + usernameLength > type3Message.length) {
                return null;
            }
            
            // Extract username (UTF-16LE encoded)
            byte[] usernameBytes = new byte[usernameLength];
            System.arraycopy(type3Message, usernameOffset, usernameBytes, 0, usernameLength);
            return new String(usernameBytes, "UTF-16LE");
            
        } catch (Exception e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Failed to parse NTLM Type 3 username", e);
            }
            return null;
        }
    }


    /**
     * Resets authentication state after completion or failure.
     */
    private void resetAuthState() {
        authState = AuthState.NONE;
        authMechanism = null;
        pendingAuthUsername = null;
        authChallenge = null;
        authNonce = null;
        authClientNonce = null;
        authServerSignature = null;
        authSalt = null;
        authIterations = 4096; // Reset to default
        
        // Clean up enterprise authentication state
        if (saslServer != null) {
            try {
                saslServer.dispose();
            } catch (SaslException e) {
                // Ignore disposal errors
            }
            saslServer = null;
        }
        clientCertificate = null;
        ntlmChallenge = null;
        ntlmTargetName = null;
    }

    /**
     * Checks if the authenticated user is authorized to send from the specified email address.
     * This implements sender address authorization policies for authenticated SMTP sessions.
     * 
     * Current policy (can be enhanced):
     * - Username equals email address (exact match)
     * - Username equals local part of email address (user@domain.com authorized for "user")
     * - Users with "admin" or "postmaster" roles can send from any address
     * 
     * @param fromAddress the email address in the MAIL FROM command
     * @param authenticatedUser the authenticated username
     * @return true if authorized to send from this address
     */
    private boolean isAuthorizedSender(String fromAddress, String authenticatedUser) {
        if (fromAddress == null || authenticatedUser == null) {
            return false;
        }
        
        // Policy 1: Exact match (username equals full email address)
        if (authenticatedUser.equalsIgnoreCase(fromAddress)) {
            return true;
        }
        
        // Policy 2: Username matches local part of email address
        // e.g., user "john" can send from "john@example.com"
        int atIndex = fromAddress.indexOf('@');
        if (atIndex > 0) {
            String localPart = fromAddress.substring(0, atIndex);
            if (authenticatedUser.equalsIgnoreCase(localPart)) {
                return true;
            }
        }
        
        // Policy 3: Check realm roles for administrative privileges
        // If user has "admin" or "postmaster" role, allow any sender address
        if (getRealm() != null) {
            if (getRealm().isUserInRole(authenticatedUser, "admin") || 
                getRealm().isUserInRole(authenticatedUser, "postmaster")) {
                return true;
            }
        }
        
        // Future enhancement: Could add domain-based authorization, alias mapping, etc.
        
        return false; // Default: deny
    }


    /**
     * Basic email address syntax validation.
     */
    private boolean isValidEmailAddress(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }
        
        // Basic validation - can be enhanced with more sophisticated regex
        int atIndex = email.indexOf('@');
        if (atIndex <= 0 || atIndex >= email.length() - 1) {
            return false; // No @ or @ at start/end
        }
        
        String localPart = email.substring(0, atIndex);
        String domain = email.substring(atIndex + 1);
        
        // RFC 5321 limits: local part <= 64 chars, domain <= 255 chars
        return localPart.length() <= 64 && domain.length() <= 255 && 
               !localPart.isEmpty() && !domain.isEmpty();
    }

    /**
     * Extracts domain portion from email address.
     */
    private String extractDomain(String email) {
        int atIndex = email.indexOf('@');
        return atIndex > 0 ? email.substring(atIndex + 1).toLowerCase() : null;
    }

    /**
     * Checks if domain is in blocked list.
     * Example implementation - customize with your blocked domains.
     */
    private boolean isBlockedDomain(String domain) {
        // Example blocked domains - replace with your policy
        String[] blockedDomains = {
            "spam.example.com",
            "blocked.domain.com",
            "malicious.net"
        };
        
        for (String blocked : blockedDomains) {
            if (domain.equals(blocked) || domain.endsWith("." + blocked)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks sender against spam reputation databases.
     * Placeholder for integration with reputation services.
     */
    private boolean hasSpamReputation(String fromAddress) {
        // Placeholder - integrate with reputation services like:
        // - Spamhaus SBL/XBL/PBL
        // - SURBL
        // - Custom reputation database
        return false;
    }

    /**
     * Implements per-sender rate limiting.
     * Placeholder for rate limiting logic.
     */
    private boolean isSenderRateLimited(String fromAddress) {
        // Placeholder - implement rate limiting per sender:
        // - Track sending frequency per address
        // - Apply limits based on sender reputation
        // - Use sliding window or token bucket algorithms
        return false;
    }

    /**
     * Implements greylisting policy.
     * Placeholder for greylisting logic.
     */
    private boolean shouldGreylist(String fromAddress) {
        // Placeholder - implement greylisting:
        // - Temporarily reject first-time senders
        // - Accept after legitimate retry (usually 15+ minutes)
        // - Maintain whitelist of known good senders
        return false;
    }

    /**
     * Checks for policy violations.
     * Placeholder for custom business rules.
     */
    private boolean violatesPolicy(String fromAddress) {
        // Placeholder - implement custom policies:
        // - Content filtering rules
        // - Business-specific sender restrictions
        // - Compliance requirements
        return false;
    }

    /**
     * Checks if mail storage is full.
     * Placeholder for storage capacity monitoring.
     */
    private boolean isStorageFull() {
        // Placeholder - implement storage monitoring:
        // - Check disk space availability
        // - Monitor queue sizes
        // - Apply per-user quotas
        return false;
    }

    /**
     * Checks if relaying is denied for this sender.
     * Placeholder for relay authorization.
     */
    private boolean isRelayDenied(String fromAddress) {
        // Placeholder - implement relay policies:
        // - Allow relay for authenticated users
        // - Allow relay for internal networks
        // - Deny relay for external users (open relay prevention)
        return false;
    }


    /**
     * Checks if a mailbox exists for the given recipient.
     * Placeholder for mailbox existence verification.
     */
    private boolean mailboxExists(String recipient) {
        // Placeholder - implement mailbox lookup:
        // - Check local user database
        // - Query LDAP/Active Directory
        // - Check virtual alias maps
        // - Verify catch-all settings
        return true; // Default: assume exists for demo
    }

    /**
     * Checks if a mailbox is temporarily unavailable.
     * Placeholder for mailbox availability checking.
     */
    private boolean isMailboxTemporarilyUnavailable(String recipient) {
        // Placeholder - implement availability checks:
        // - Check if user account is locked
        // - Verify mailbox maintenance status
        // - Check system load/performance
        return false;
    }

    /**
     * Checks if recipient has exceeded their storage quota.
     * Placeholder for quota management.
     */
    private boolean isRecipientQuotaExceeded(String recipient) {
        // Placeholder - implement quota checking:
        // - Check per-user disk usage
        // - Verify message count limits
        // - Check attachment size restrictions
        return false;
    }

    /**
     * Determines if recipient should be forwarded to another server.
     * Placeholder for forwarding logic.
     */
    private boolean shouldForwardRecipient(String recipient) {
        // Placeholder - implement forwarding rules:
        // - Check if domain is handled locally
        // - Determine if user has forwarding rules
        // - Check MX record priorities
        return false;
    }

    /**
     * Checks if recipient is in blocked list.
     * Placeholder for recipient blocking.
     */
    private boolean isRecipientBlocked(String recipient) {
        // Placeholder - implement recipient blocking:
        // - Internal blacklists
        // - Suspended accounts
        // - Compliance restrictions
        return false;
    }

    /**
     * Checks relay permissions for recipient (different context than sender).
     * Placeholder for recipient relay authorization.
     */
    private boolean isRecipientRelayDenied(String recipient) {
        // Placeholder - implement recipient relay checks:
        // - Verify domain is local or relay is authorized
        // - Check if authenticated user can send to this recipient
        // - Apply recipient-specific relay rules
        return false;
    }

    /**
     * Checks if storage is full for this specific recipient.
     * Placeholder for per-recipient storage monitoring.
     */
    private boolean isRecipientStorageFull(String recipient) {
        // Placeholder - implement per-recipient storage:
        // - Check individual mailbox limits
        // - Verify per-domain storage quotas
        // - Monitor system-wide capacity
        return false;
    }

    /**
     * Checks if recipient violates policy (recipient-specific rules).
     * Placeholder for recipient policy enforcement.
     */
    private boolean recipientViolatesPolicy(String recipient) {
        // Placeholder - implement recipient policies:
        // - Corporate email policies
        // - External recipient restrictions
        // - Compliance and regulatory rules
        return false;
    }

    /**
     * Checks if mailbox name format is allowed.
     * Placeholder for mailbox naming policies.
     */
    private boolean isMailboxNameAllowed(String recipient) {
        // Placeholder - implement naming policies:
        // - Restricted usernames (admin, root, etc.)
        // - Corporate naming conventions
        // - Character restrictions beyond RFC compliance
        return true;
    }

    /**
     * Invoked when the client closes the connection.
     * Performs any necessary cleanup for the SMTP session.
     */
    @Override
    protected void disconnected() throws IOException {
        try {
            // Record connection closed metric
            SMTPServerMetrics metrics = getServerMetrics();
            if (metrics != null) {
                double durationMs = System.currentTimeMillis() - getTimestampCreated();
                metrics.connectionClosed(durationMs);
            }

            // End telemetry session span if still active
            if (sessionSpan != null && !sessionSpan.isEnded()) {
                if (state == SMTPState.QUIT) {
                    endSessionSpan("Connection closed");
                } else {
                    endSessionSpanError("Connection lost");
                }
            }

            // Notify handler of disconnection
            if (connectedHandler != null) {
                connectedHandler.disconnected();
            }

            // Notify connector that connection is closed for tracking
            try {
                if (channel != null) {
                    server.connectionClosed((InetSocketAddress) channel.getRemoteAddress());
                }
            } catch (IOException e) {
                // Log but don't fail - cleanup is more important
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING, "Error notifying connector of connection close", e);
                }
            }
        } finally {
            // Cleanup resources in finally block to guarantee execution
            if (recipients != null) {
                recipients.clear();
            }
            if (dsnRecipients != null) {
                dsnRecipients.clear();
            }
            deliveryRequirements = null;
            resetDataState();

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("SMTP connection disconnected: " + getRemoteSocketAddress());
            }
        }
    }

    // SMTPConnectionMetadata implementation
    
    @Override
    public InetSocketAddress getClientAddress() {
        // Return XCLIENT-overridden address if set
        if (xclientAddr != null) {
            return xclientAddr;
        }
        try {
            if (channel != null) {
                return (InetSocketAddress) channel.getRemoteAddress();
            }
        } catch (IOException e) {
            // Ignore - return null
        }
        return null;
    }
    
    @Override
    public InetSocketAddress getServerAddress() {
        // Return XCLIENT-overridden destination address if set
        if (xclientDestAddr != null) {
            return xclientDestAddr;
        }
        try {
            if (channel != null) {
                return (InetSocketAddress) channel.getLocalAddress();
            }
        } catch (IOException e) {
            // Ignore - return null
        }
        return null;
    }
    
    @Override
    public boolean isSecure() {
        return super.isSecure();
    }
    
    @Override
    public java.security.cert.X509Certificate[] getClientCertificates() {
        // TODO: Need to expose SSL session details from Connection class
        // For now, return null as SSL certificate access is not yet available
        return null;
    }
    
    @Override
    public String getCipherSuite() {
        // TODO: Need to expose SSL session details from Connection class
        // For now, return null as SSL cipher suite is not yet available
        return null;
    }
    
    @Override
    public String getProtocolVersion() {
        // TODO: Need to expose SSL session details from Connection class
        // For now, return null as SSL protocol version is not yet available
        return null;
    }
    
    @Override
    public long getConnectionTimeMillis() {
        return connectionTimeMillis;
    }

    @Override
    public DSNEnvelopeParameters getDSNEnvelopeParameters() {
        if (deliveryRequirements == null || !deliveryRequirements.hasDsnParameters()) {
            return null;
        }
        return new DSNEnvelopeParameters(deliveryRequirements.getDsnReturn(), 
                                         deliveryRequirements.getDsnEnvelopeId());
    }

    @Override
    public DSNRecipientParameters getDSNRecipientParameters(EmailAddress recipient) {
        if (dsnRecipients == null || recipient == null) {
            return null;
        }
        return dsnRecipients.get(recipient);
    }

    @Override
    public boolean isRequireTls() {
        return deliveryRequirements != null && deliveryRequirements.isRequireTls();
    }

    // ========================================================================
    // ConnectedState implementation
    // ========================================================================
    
    @Override
    public void acceptConnection(String greeting, HelloHandler handler) {
        this.helloHandler = handler;
        try {
            startSessionSpan();
            reply(220, greeting);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending greeting", e);
            close();
        }
    }
    
    @Override
    public void rejectConnection() {
        rejectConnection("Connection rejected");
    }
    
    @Override
    public void rejectConnection(String message) {
        this.state = SMTPState.REJECTED;
        try {
            reply(554, "5.0.0 " + message);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
        close();
        if (connectedHandler != null) {
            connectedHandler.disconnected();
        }
    }
    
    // ========================================================================
    // HelloState implementation
    // ========================================================================
    
    @Override
    public void acceptHello(MailFromHandler handler) {
        this.mailFromHandler = handler;
        this.state = SMTPState.READY;
        try {
            sendEhloResponse();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending EHLO response", e);
            close();
        }
    }
    
    @Override
    public void rejectHelloTemporary(String message, HelloHandler handler) {
        this.helloHandler = handler;
        try {
            reply(421, "4.3.0 " + message);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectHello(String message, HelloHandler handler) {
        this.helloHandler = handler;
        try {
            reply(550, "5.0.0 " + message);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectHelloAndClose(String message) {
        this.state = SMTPState.REJECTED;
        try {
            reply(554, "5.0.0 " + message);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
        close();
    }
    
    @Override
    public void serverShuttingDown() {
        try {
            reply(421, "4.3.0 Server shutting down");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending shutdown notice", e);
        }
        close();
    }
    
    // ========================================================================
    // AuthenticateState implementation
    // ========================================================================
    
    @Override
    public void accept(MailFromHandler handler) {
        this.authenticated = true;
        this.mailFromHandler = handler;
        this.state = SMTPState.READY;
        recordAuthenticationSuccess(authenticatedUser, authMechanism);
        try {
            reply(235, "2.7.0 Authentication successful");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending auth success", e);
            close();
        }
    }
    
    @Override
    public void reject(HelloHandler handler) {
        this.helloHandler = handler;
        SMTPServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.authAttempt(authMechanism);
            metrics.authFailure(authMechanism);
        }
        try {
            reply(535, "5.7.8 Authentication rejected");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending auth failure", e);
            close();
        }
    }
    
    @Override
    public void rejectAndClose() {
        SMTPServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.authAttempt(authMechanism);
            metrics.authFailure(authMechanism);
        }
        try {
            reply(535, "5.7.8 Authentication rejected");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending auth failure", e);
        }
        close();
    }
    
    // ========================================================================
    // MailFromState implementation
    // ========================================================================
    
    @Override
    public void acceptSender(RecipientHandler handler) {
        this.recipientHandler = handler;
        this.state = SMTPState.MAIL;
        
        // Get pipeline from the mail from handler if available
        if (mailFromHandler != null) {
            this.currentPipeline = mailFromHandler.getPipeline();
            if (currentPipeline != null) {
                // Notify pipeline of sender
                currentPipeline.mailFrom(mailFrom);
            }
        }
        
        String senderAddr = (mailFrom != null) ? mailFrom.getEnvelopeAddress() : "";
        addSessionAttribute("smtp.mail_from", senderAddr);
        addSessionEvent("MAIL FROM: " + senderAddr);
        try {
            reply(250, "2.1.0 Sender ok");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending acceptance", e);
            close();
        }
    }
    
    @Override
    public void rejectSenderGreylist(MailFromHandler handler) {
        this.mailFromHandler = handler;
        try {
            reply(450, "4.7.1 Greylisting in effect, please try again later");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectSenderRateLimit(MailFromHandler handler) {
        this.mailFromHandler = handler;
        try {
            reply(450, "4.7.1 Rate limit exceeded, please try again later");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectSenderStorageFull(MailFromHandler handler) {
        this.mailFromHandler = handler;
        try {
            reply(452, "4.3.1 Insufficient system storage");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectSenderBlockedDomain(MailFromHandler handler) {
        this.mailFromHandler = handler;
        try {
            reply(550, "5.1.1 Sender domain blocked by policy");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectSenderInvalidDomain(MailFromHandler handler) {
        this.mailFromHandler = handler;
        try {
            reply(550, "5.1.1 Sender domain does not exist");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectSenderPolicy(String message, MailFromHandler handler) {
        this.mailFromHandler = handler;
        try {
            reply(553, "5.7.1 " + message);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectSenderSpam(MailFromHandler handler) {
        this.mailFromHandler = handler;
        try {
            reply(554, "5.7.1 Sender has poor reputation");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectSenderSyntax(MailFromHandler handler) {
        this.mailFromHandler = handler;
        try {
            reply(501, "5.1.3 Invalid sender address format");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    // ========================================================================
    // RecipientState implementation
    // ========================================================================
    
    @Override
    public void acceptRecipient(RecipientHandler handler) {
        this.recipientHandler = handler;
        EmailAddress recipient = pendingRecipient;
        this.recipients.add(recipient);
        if (pendingRecipientDSN != null) {
            this.dsnRecipients.put(recipient, pendingRecipientDSN);
            pendingRecipientDSN = null;
        }
        this.state = SMTPState.RCPT;
        
        // Notify pipeline of recipient
        if (currentPipeline != null) {
            currentPipeline.rcptTo(recipient);
        }
        
        String addr = recipient.getEnvelopeAddress();
        addSessionAttribute("smtp.rcpt_count", recipients.size());
        addSessionEvent("RCPT TO: " + addr);
        try {
            reply(250, "2.1.5 " + addr + "... Recipient ok");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending acceptance", e);
            close();
        }
    }
    
    @Override
    public void acceptRecipientForward(String forwardPath, RecipientHandler handler) {
        this.recipientHandler = handler;
        EmailAddress recipient = pendingRecipient;
        this.recipients.add(recipient);
        if (pendingRecipientDSN != null) {
            this.dsnRecipients.put(recipient, pendingRecipientDSN);
            pendingRecipientDSN = null;
        }
        this.state = SMTPState.RCPT;
        addSessionAttribute("smtp.rcpt_count", recipients.size());
        addSessionEvent("RCPT TO (forward): " + recipient.getEnvelopeAddress());
        try {
            reply(251, "2.1.5 User not local; will forward to " + forwardPath);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending acceptance", e);
            close();
        }
    }
    
    @Override
    public void rejectRecipientUnavailable(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(450, "4.2.1 Mailbox temporarily unavailable");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectRecipientSystemError(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(451, "4.3.0 Local error in processing");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectRecipientStorageFull(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(452, "4.3.1 Insufficient system storage");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectRecipientNotFound(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(550, "5.1.1 Mailbox unavailable");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectRecipientNotLocal(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(551, "5.1.1 User not local");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectRecipientQuota(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(552, "5.2.2 Mailbox full, quota exceeded");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectRecipientInvalid(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(553, "5.1.3 Mailbox name not allowed");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectRecipientRelayDenied(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(551, "5.7.1 Relaying denied");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectRecipientPolicy(String message, RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(553, "5.7.1 " + message);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    // ========================================================================
    // MessageStartState implementation
    // ========================================================================
    
    @Override
    public void acceptMessage(MessageDataHandler handler) {
        this.messageHandler = handler;
        doAcceptMessage();
    }
    
    @Override
    public void rejectMessageStorageFull(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(452, "4.3.1 Insufficient system storage");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectMessageProcessingError(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(451, "4.3.0 Local processing error");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectMessage(String message, MailFromHandler handler) {
        this.mailFromHandler = handler;
        resetTransaction();
        try {
            reply(550, "5.7.0 " + message);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    // ========================================================================
    // BdatStartState - MessageStartState for BDAT (no 354 response)
    // ========================================================================
    
    /**
     * A MessageStartState implementation for BDAT that sets the handler
     * without sending the 354 response (which is only for DATA).
     */
    private class BdatStartState implements MessageStartState {
        @Override
        public void acceptMessage(MessageDataHandler handler) {
            // Just set the handler - don't call doAcceptMessage() which sends 354
            messageHandler = handler;
        }
        
        @Override
        public void rejectMessageStorageFull(RecipientHandler handler) {
            SMTPConnection.this.rejectMessageStorageFull(handler);
        }
        
        @Override
        public void rejectMessageProcessingError(RecipientHandler handler) {
            SMTPConnection.this.rejectMessageProcessingError(handler);
        }
        
        @Override
        public void rejectMessage(String message, MailFromHandler handler) {
            SMTPConnection.this.rejectMessage(message, handler);
        }
        
        @Override
        public void serverShuttingDown() {
            SMTPConnection.this.serverShuttingDown();
        }
    }
    
    // ========================================================================
    // MessageEndState implementation
    // ========================================================================
    
    @Override
    public void acceptMessageDelivery(String queueId, MailFromHandler handler) {
        this.mailFromHandler = handler;
        resetTransaction();
        try {
            String msg = "2.0.0 Message accepted for delivery";
            if (queueId != null) {
                msg = msg + " " + queueId;
            }
            reply(250, msg);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending acceptance", e);
            close();
        }
    }
    
    @Override
    public void rejectMessageTemporary(String message, MailFromHandler handler) {
        this.mailFromHandler = handler;
        resetTransaction();
        try {
            reply(450, "4.0.0 " + message);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectMessagePermanent(String message, MailFromHandler handler) {
        this.mailFromHandler = handler;
        resetTransaction();
        try {
            reply(550, "5.0.0 " + message);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    @Override
    public void rejectMessagePolicy(String message, MailFromHandler handler) {
        this.mailFromHandler = handler;
        resetTransaction();
        try {
            reply(553, "5.7.1 " + message);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            close();
        }
    }
    
    // ========================================================================
    // ResetState implementation
    // ========================================================================
    
    @Override
    public void acceptReset(MailFromHandler handler) {
        this.mailFromHandler = handler;
        resetTransaction();
        try {
            reply(250, "2.0.0 Reset OK");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending reset response", e);
            close();
        }
    }
    
    // ========================================================================
    // Helper methods for state transitions
    // ========================================================================
    
    /**
     * Resets transaction state (preserves session state like authentication).
     */
    private void resetTransaction() {
        this.state = SMTPState.READY;
        this.mailFrom = null;
        this.recipients.clear();
        this.dsnRecipients.clear();
        this.deliveryRequirements = null;
        this.smtputf8 = false;
        this.bodyType = BodyType.SEVEN_BIT;
        this.pendingRecipient = null;
        this.pendingRecipientDSN = null;
        
        // Reset pipeline
        if (currentPipeline != null) {
            currentPipeline.reset();
            currentPipeline = null;
        }
        
        // Start a new session span for the next transaction
        startSessionSpan();
    }
    
    // Temporary storage for pending recipient during callback
    private EmailAddress pendingRecipient;

    // -- Telemetry helpers --

    /**
     * Adds an attribute to the current session span.
     *
     * @param key the attribute key
     * @param value the attribute value
     */
    private void addSessionAttribute(String key, String value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    /**
     * Adds an attribute to the current session span.
     *
     * @param key the attribute key
     * @param value the attribute value
     */
    private void addSessionAttribute(String key, long value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    /**
     * Adds an attribute to the current session span.
     *
     * @param key the attribute key
     * @param value the attribute value
     */
    private void addSessionAttribute(String key, boolean value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    /**
     * Adds an event to the current session span.
     *
     * @param name the event name
     */
    private void addSessionEvent(String name) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addEvent(name);
        }
    }

    /**
     * Records an exception on the current session span.
     *
     * @param exception the exception to record
     */
    private void recordSessionException(Throwable exception) {
        if (sessionSpan != null && !sessionSpan.isEnded() && exception != null) {
            sessionSpan.recordExceptionWithCategory(exception);
        }
    }

    /**
     * Records an exception with an explicit error category on the current session span.
     *
     * @param exception the exception to record
     * @param category the error category
     */
    private void recordSessionException(Throwable exception, ErrorCategory category) {
        if (sessionSpan != null && !sessionSpan.isEnded() && exception != null) {
            sessionSpan.recordException(exception, category);
        }
    }

    /**
     * Records an SMTP error reply in telemetry.
     * This correlates the SMTP reply code with a standardized error category.
     *
     * @param replyCode the SMTP reply code (4xx or 5xx)
     * @param message the error message
     */
    private void recordSmtpError(int replyCode, String message) {
        if (sessionSpan == null || sessionSpan.isEnded()) {
            return;
        }
        ErrorCategory category = ErrorCategory.fromSmtpReplyCode(replyCode);
        if (category != null) {
            sessionSpan.recordError(category, replyCode, message);
        }
    }

    /**
     * Records successful authentication for telemetry.
     *
     * @param username the authenticated username
     * @param mechanism the authentication mechanism used
     */
    private void recordAuthenticationSuccess(String username, String mechanism) {
        addSessionAttribute("smtp.authenticated", true);
        addSessionAttribute("smtp.auth_user", username);
        addSessionAttribute("smtp.auth_mechanism", mechanism);
        addSessionEvent("AUTH success: " + mechanism);

        // Record authentication success metric
        SMTPServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.authAttempt(mechanism);
            metrics.authSuccess(mechanism);
        }
    }
    
    /**
     * Called after successful SASL authentication to notify the handler.
     * 
     * <p>Creates a Principal for the authenticated user and calls the
     * handler's authenticated() method for policy decision.
     * 
     * @param username the authenticated username
     * @param mechanism the authentication mechanism used
     */
    private void notifyAuthenticationSuccess(String username, String mechanism) throws IOException {
        this.authenticatedUser = username;
        this.authMechanism = mechanism;
        
        if (helloHandler != null) {
            // Create a simple Principal for the username
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
            
            // Handler makes policy decision - calls accept() or reject()
            helloHandler.authenticated(principal, this);
        } else {
            // No handler - auto-accept
            this.authenticated = true;
            recordAuthenticationSuccess(username, mechanism);
            reply(235, "2.7.0 Authentication successful");
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info("SMTP AUTH " + mechanism + " successful for user: " + username);
            }
        }
    }
    
    /**
     * Called after failed SASL authentication.
     * 
     * @param username the username that failed (may be null)
     * @param mechanism the authentication mechanism used
     */
    private void notifyAuthenticationFailure(String username, String mechanism) throws IOException {
        SMTPServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.authAttempt(mechanism);
            metrics.authFailure(mechanism);
        }
        addSessionEvent("AUTH " + mechanism + " failed");
        reply(535, "5.7.8 Authentication credentials invalid");
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.warning("SMTP AUTH " + mechanism + " failed for user: " + 
                (username != null ? username : "(unknown)"));
        }
    }

}

