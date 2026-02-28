/*
 * SMTPProtocolHandler.java
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
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
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
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.Subject;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.LineParser;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SASLMechanism;
import org.bluezoo.gumdrop.auth.SASLUtils;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddressParser;
import org.bluezoo.gumdrop.smtp.handler.AuthenticateState;
import org.bluezoo.gumdrop.smtp.handler.ClientConnected;
import org.bluezoo.gumdrop.smtp.handler.ConnectedState;
import org.bluezoo.gumdrop.smtp.handler.HelloHandler;
import org.bluezoo.gumdrop.smtp.handler.HelloState;
import org.bluezoo.gumdrop.smtp.handler.MailFromHandler;
import org.bluezoo.gumdrop.smtp.handler.MailFromState;
import org.bluezoo.gumdrop.smtp.handler.MessageEndState;
import org.bluezoo.gumdrop.smtp.handler.MessageStartState;
import org.bluezoo.gumdrop.smtp.handler.RecipientState;
import org.bluezoo.gumdrop.smtp.handler.ResetState;
import org.bluezoo.gumdrop.smtp.handler.MessageDataHandler;
import org.bluezoo.gumdrop.smtp.handler.MessageEndState;
import org.bluezoo.gumdrop.smtp.handler.MessageStartState;
import org.bluezoo.gumdrop.smtp.handler.RecipientHandler;
import org.bluezoo.gumdrop.smtp.handler.RecipientState;
import org.bluezoo.gumdrop.smtp.handler.ResetState;

import org.bluezoo.gumdrop.smtp.DeliveryRequirements;
import org.bluezoo.gumdrop.smtp.DSNNotify;
import org.bluezoo.gumdrop.smtp.DSNReturn;
import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * SMTP protocol handler using {@link ProtocolHandler} and {@link LineParser}.
 *
 * <p>Implements the SMTP protocol with the transport layer fully decoupled:
 * <ul>
 * <li>Transport operations delegate to an {@link Endpoint} reference
 *     received in {@link #connected(Endpoint)}</li>
 * <li>Line parsing uses the composable {@link LineParser} utility</li>
 * <li>TLS upgrade uses {@link Endpoint#startTLS()}</li>
 * <li>Security info uses {@link Endpoint#getSecurityInfo()}</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtocolHandler
 * @see LineParser
 * @see SMTPListener
 */
public class SMTPProtocolHandler
        implements ProtocolHandler, LineParser.Callback,
                   ConnectedState, HelloState, AuthenticateState,
                   MailFromState, RecipientState, MessageStartState, MessageEndState,
                   ResetState, SMTPConnectionMetadata {

    private static final Logger LOGGER =
            Logger.getLogger(SMTPProtocolHandler.class.getName());
    static final java.util.ResourceBundle L10N =
            java.util.ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");

    static final Charset US_ASCII = Charset.forName("US-ASCII");
    static final CharsetDecoder US_ASCII_DECODER = US_ASCII.newDecoder();
    static final Charset UTF_8 = Charset.forName("UTF-8");
    static final CharsetDecoder UTF_8_DECODER = UTF_8.newDecoder();

    private static final int MAX_LINE_LENGTH = 998;
    private static final int MAX_CONTROL_BUFFER_SIZE = 8;
    private static final int MAX_COMMAND_LINE_LENGTH = 1000;

    enum SMTPState {
        INITIAL, REJECTED, READY, MAIL, RCPT, DATA, BDAT, QUIT
    }

    enum AuthState {
        NONE, PLAIN_RESPONSE, LOGIN_USERNAME, LOGIN_PASSWORD,
        CRAM_MD5_RESPONSE, DIGEST_MD5_RESPONSE, SCRAM_INITIAL, SCRAM_FINAL,
        OAUTH_RESPONSE, GSSAPI_EXCHANGE, EXTERNAL_CERT
    }

    enum DataState {
        NORMAL, SAW_CR, SAW_CRLF, SAW_DOT, SAW_DOT_CR
    }

    private Endpoint endpoint;

    private final SMTPListener server;
    private final ClientConnected connectedHandler;
    private final long connectionTimeMillis;
    private final List<EmailAddress> recipients;
    private final Map<EmailAddress, DSNRecipientParameters> dsnRecipients;
    private final CharBuffer charBuffer;
    private ByteBuffer controlBuffer;

    private Realm realm;
    private HelloHandler helloHandler;
    private MailFromHandler mailFromHandler;
    private RecipientHandler recipientHandler;
    private MessageDataHandler messageHandler;
    private SMTPPipeline currentPipeline;

    private SMTPState state = SMTPState.INITIAL;
    private String heloName;
    private EmailAddress mailFrom;
    private boolean extendedSMTP;
    private boolean smtputf8;
    private BodyType bodyType = BodyType.SEVEN_BIT;
    private DefaultDeliveryRequirements deliveryRequirements;

    private DSNRecipientParameters pendingRecipientDSN;
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

    private long bdatBytesRemaining;
    private boolean bdatLast;
    private boolean bdatStarted;

    private Span sessionSpan;
    private int sessionNumber;

    private EmailAddress pendingRecipient;

    public SMTPProtocolHandler(SMTPListener server, ClientConnected handler) {
        this.server = server;
        this.connectedHandler = handler;
        this.connectionTimeMillis = System.currentTimeMillis();
        this.recipients = new ArrayList<EmailAddress>();
        this.dsnRecipients = new HashMap<EmailAddress, DSNRecipientParameters>();
        this.charBuffer = CharBuffer.allocate(MAX_COMMAND_LINE_LENGTH);
        this.controlBuffer = ByteBuffer.allocate(MAX_CONTROL_BUFFER_SIZE);
    }

    // ── ProtocolHandler implementation ──

    @Override
    public void connected(Endpoint ep) {
        this.endpoint = ep;
        initConnectionTrace();
        SMTPServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.connectionOpened();
        }
        if (endpoint.isSecure()) {
            return;
        }
        sendGreeting();
    }

    @Override
    public void receive(ByteBuffer buf) {
        try {
            if (state == SMTPState.BDAT) {
                handleBdatContent(buf);
            } else if (state == SMTPState.DATA) {
                handleDataContent(buf);
            } else {
                LineParser.parse(buf, this);
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

    @Override
    public void disconnected() {
        try {
            SMTPServerMetrics metrics = getServerMetrics();
            if (metrics != null) {
                double durationMs = System.currentTimeMillis() - connectionTimeMillis;
                metrics.connectionClosed(durationMs);
            }
            if (sessionSpan != null && !sessionSpan.isEnded()) {
                if (state == SMTPState.QUIT) {
                    endSessionSpan("Connection closed");
                } else {
                    endSessionSpanError("Connection lost");
                }
            }
            if (connectedHandler != null) {
                connectedHandler.disconnected();
            }
            if (endpoint != null && endpoint.getRemoteAddress() != null) {
                server.connectionClosed((InetSocketAddress) endpoint.getRemoteAddress());
            }
        } catch (Exception e) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "Error in disconnected handler", e);
            }
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

    @Override
    public void securityEstablished(SecurityInfo info) {
        if (state == SMTPState.INITIAL && !starttlsUsed) {
            sendGreeting();
        } else if (helloHandler != null && starttlsUsed) {
            helloHandler.tlsEstablished(info);
        }
    }

    @Override
    public void error(Exception cause) {
        LOGGER.log(Level.WARNING, "SMTP transport error", cause);
        if (endpoint != null) {
            endpoint.close();
        }
    }

    // ── LineParser.Callback implementation ──

    @Override
    public void lineReceived(ByteBuffer line) {
        try {
            int lineLength = line.remaining();
            if (lineLength > MAX_COMMAND_LINE_LENGTH) {
                reply(500, L10N.getString("smtp.err.line_too_long"));
                return;
            }
            boolean mightBeMailCommand = (state == SMTPState.READY && lineLength >= 4
                    && isMailCommandPrefix(line));
            CharsetDecoder decoder;
            boolean requireAscii;
            if (mightBeMailCommand) {
                decoder = UTF_8_DECODER;
                requireAscii = false;
            } else if (smtputf8) {
                decoder = UTF_8_DECODER;
                requireAscii = false;
            } else {
                decoder = US_ASCII_DECODER;
                requireAscii = true;
            }
            charBuffer.clear();
            decoder.reset();
            CoderResult result = decoder.decode(line, charBuffer, true);
            if (result.isError()) {
                reply(500, L10N.getString("smtp.err.invalid_encoding"));
                return;
            }
            charBuffer.flip();
            if (requireAscii && containsNonAscii(charBuffer)) {
                reply(500, L10N.getString("smtp.err.invalid_encoding"));
                return;
            }
            int len = charBuffer.limit();
            if (len >= 2 && charBuffer.get(len - 2) == '\r' && charBuffer.get(len - 1) == '\n') {
                charBuffer.limit(len - 2);
                len -= 2;
            }
            if (authState != AuthState.NONE) {
                String lineStr = charBuffer.toString();
                handleAuthData(lineStr);
                return;
            }
            int spaceIndex = -1;
            for (int i = 0; i < len; i++) {
                if (charBuffer.get(i) == ' ') {
                    spaceIndex = i;
                    break;
                }
            }
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
            dispatchCommand(command, args);
            if (mightBeMailCommand && "MAIL".equals(command) && !smtputf8) {
                charBuffer.position(0);
                charBuffer.limit(len);
                if (containsNonAscii(charBuffer)) {
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

    @Override
    public boolean continueLineProcessing() {
        return state != SMTPState.DATA && state != SMTPState.BDAT;
    }

    // ── Transport helpers ──

    private void reply(int code, String message) throws IOException {
        sendResponse(code, message);
    }

    private void replyMultiline(int code, String message) throws IOException {
        String response = String.format("%d-%s\r\n", code, message);
        ByteBuffer buffer = ByteBuffer.wrap(response.getBytes(US_ASCII));
        endpoint.send(buffer);
    }

    private void sendResponse(int code, String message) throws IOException {
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

    private SMTPServerMetrics getServerMetrics() {
        return server != null ? server.getMetrics() : null;
    }

    private void initConnectionTrace() {
        if (endpoint == null || !endpoint.isTelemetryEnabled()) {
            return;
        }
        TelemetryConfig cfg = endpoint.getTelemetryConfig();
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

    private void sendGreeting() {
        if (connectedHandler != null) {
            connectedHandler.connected(this, endpoint);
        } else {
            startSessionSpan();
            try {
                reply(220, endpoint.getLocalAddress().toString() + " ESMTP Service ready");
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error sending greeting", e);
                closeEndpoint();
            }
        }
    }

    private boolean isMailCommandPrefix(ByteBuffer buf) {
        int pos = buf.position();
        if (buf.remaining() < 4) {
            return false;
        }
        byte b0 = buf.get(pos);
        byte b1 = buf.get(pos + 1);
        byte b2 = buf.get(pos + 2);
        byte b3 = buf.get(pos + 3);
        return (b0 == 'M' || b0 == 'm') && (b1 == 'A' || b1 == 'a')
                && (b2 == 'I' || b2 == 'i') && (b3 == 'L' || b3 == 'l');
    }

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
        } else if ("STARTTLS".equals(command) && !endpoint.isSecure() && server.isSTARTTLSAvailable()
                && !starttlsUsed) {
            starttls(args);
        } else if ("AUTH".equals(command)) {
            auth(args);
        } else if ("XCLIENT".equals(command)) {
            xclient(args);
        } else {
            reply(500, MessageFormat.format(L10N.getString("smtp.err.command_unrecognized"), command));
        }
    }

    private void handlePipelinedCommands(ByteBuffer buf) throws IOException {
        LineParser.parse(buf, this);
        if (buf.hasRemaining()) {
            if (state == SMTPState.BDAT) {
                handleBdatContent(buf);
            } else if (state == SMTPState.DATA) {
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
        if (currentPipeline != null) {
            try {
                java.nio.channels.WritableByteChannel ch = currentPipeline.getMessageChannel();
                if (ch != null) {
                    ch.write(messageBuffer);
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error writing to pipeline", e);
            }
        }
    }

    private void sendChunk(ByteBuffer source, int start, int end) throws IOException {
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

    private void handleControlSequenceWithNewData(ByteBuffer newData) throws IOException {
        appendToControlBuffer(newData);
        controlBuffer.flip();
        if (controlBuffer.hasRemaining()) {
            ByteBuffer tempBuf = controlBuffer.slice();
            controlBuffer.clear();
            if (tempBuf.hasRemaining()) {
                processDataBuffer(tempBuf);
            }
        } else {
            controlBuffer.clear();
        }
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
        resetBdatState();
    }

    private void resetBdatState() {
        bdatBytesRemaining = 0L;
        bdatLast = false;
        bdatStarted = false;
    }

    private void handleDataContent(ByteBuffer buf) throws IOException {
        if (controlBuffer.position() > 0) {
            handleControlSequenceWithNewData(buf);
            if (state != SMTPState.DATA) {
                if (buf.hasRemaining()) {
                    handlePipelinedCommands(buf);
                }
                return;
            }
        }
        processDataBuffer(buf);
        if (state != SMTPState.DATA && buf.hasRemaining()) {
            handlePipelinedCommands(buf);
        }
    }

    private void processDataBuffer(ByteBuffer buf) throws IOException {
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
                        if (b == '\r') {
                            dataState = DataState.SAW_CR;
                        }
                    }
                    break;
                case SAW_DOT_CR:
                    if (b == '\n') {
                        if (chunkStart < currentPos - 3) {
                            sendChunk(buf, chunkStart, currentPos - 3);
                        }
                        long messageSize = dataBytesReceived;
                        int recipientCount = recipients != null ? recipients.size() : 0;
                        boolean exceeded = sizeExceeded;
                        long maxSize = server.getMaxMessageSize();
                        resetDataState();
                        state = SMTPState.READY;
                        if (exceeded) {
                            addSessionEvent("DATA rejected (size exceeded)");
                            reply(552, "5.3.4 Message size exceeds maximum (" + maxSize + " bytes)");
                            return;
                        }
                        addSessionEvent("DATA complete");
                        SMTPServerMetrics metrics = getServerMetrics();
                        if (metrics != null) {
                            metrics.messageReceived(messageSize, recipientCount);
                        }
                        if (messageHandler != null) {
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
        } else {
            if (buf.position() > chunkStart) {
                sendChunk(buf, chunkStart, buf.position());
            }
        }
    }

    private void handleBdatContent(ByteBuffer buf) throws IOException {
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
        }
        if (bdatBytesRemaining == 0) {
            handleBdatChunkComplete();
            if (buf.hasRemaining() && state != SMTPState.BDAT) {
                handlePipelinedCommands(buf);
            }
        }
    }

    private void handleBdatChunkComplete() throws IOException {
        if (bdatLast) {
            long messageSize = dataBytesReceived;
            int recipientCount = recipients != null ? recipients.size() : 0;
            addSessionEvent("BDAT LAST complete");
            SMTPServerMetrics metrics = getServerMetrics();
            if (metrics != null) {
                metrics.messageReceived(messageSize, recipientCount);
            }
            if (currentPipeline != null) {
                currentPipeline.endData();
            }
            if (messageHandler != null) {
                messageHandler.messageComplete(this);
                return;
            }
            resetDataState();
            state = SMTPState.READY;
            reply(250, "2.0.0 Message accepted for delivery (" + messageSize + " bytes)");
        } else {
            state = SMTPState.RCPT;
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

    private void helo(String hostname) throws IOException {
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
            this.state = SMTPState.READY;
            String localHostname = endpoint.getLocalAddress().toString();
            reply(250, localHostname + " Hello " + hostname);
        }
    }

    private void ehlo(String hostname) throws IOException {
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
            this.state = SMTPState.READY;
            sendEhloResponse();
        }
    }

    private void sendEhloResponse() throws IOException {
        String localHostname = endpoint.getLocalAddress().toString();
        replyMultiline(250, localHostname + " Hello " + heloName);
        replyMultiline(250, "SIZE " + server.getMaxMessageSize());
        replyMultiline(250, "PIPELINING");
        replyMultiline(250, "8BITMIME");
        replyMultiline(250, "SMTPUTF8");
        replyMultiline(250, "ENHANCEDSTATUSCODES");
        replyMultiline(250, "CHUNKING");
        replyMultiline(250, "BINARYMIME");
        replyMultiline(250, "DSN");
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
        if (endpoint.isSecure()) {
            replyMultiline(250, "REQUIRETLS");
        }
        replyMultiline(250, "MT-PRIORITY MIXER STANAG4406 NSEP");
        replyMultiline(250, "FUTURERELEASE 604800 2012-01-01T00:00:00Z");
        replyMultiline(250, "DELIVERBY 604800");
        if (isXclientAuthorized()) {
            replyMultiline(250, "XCLIENT NAME ADDR PORT PROTO HELO LOGIN DESTADDR DESTPORT");
        }
        if (!endpoint.isSecure() && server.isSTARTTLSAvailable()) {
            replyMultiline(250, "STARTTLS");
        }
        Realm r = getRealm();
        if (r != null && (endpoint.isSecure() || server.isSTARTTLSAvailable())) {
            Set<SASLMechanism> supported = r.getSupportedSASLMechanisms();
            if (!supported.isEmpty()) {
                StringBuilder authLine = new StringBuilder("AUTH");
                for (SASLMechanism mech : supported) {
                    if (!endpoint.isSecure() && mech.requiresTLS()) {
                        continue;
                    }
                    if (mech == SASLMechanism.EXTERNAL && !endpoint.isSecure()) {
                        continue;
                    }
                    authLine.append(" ").append(mech.getMechanismName());
                }
                replyMultiline(250, authLine.toString());
            }
        }
        sendResponse(250, "HELP");
    }

    private void starttls(String args) throws IOException {
        if (endpoint.isSecure()) {
            reply(454, "4.7.0 TLS already active");
            return;
        }
        if (!server.isSTARTTLSAvailable()) {
            reply(454, "4.7.0 TLS not available");
            return;
        }
        if (state != SMTPState.INITIAL && state != SMTPState.READY) {
            reply(503, "5.0.0 Bad sequence of commands");
            return;
        }
        doStarttls();
    }

    private void doStarttls() {
        try {
            reply(220, "2.0.0 Ready to start TLS");
            endpoint.startTLS();
            state = SMTPState.INITIAL;
            heloName = null;
            extendedSMTP = false;
            starttlsUsed = true;
            addSessionAttribute("smtp.starttls", true);
            addSessionEvent("STARTTLS");
            SMTPServerMetrics metrics = getServerMetrics();
            if (metrics != null) {
                metrics.starttlsUpgraded();
            }
        } catch (Exception e) {
            try {
                reply(454, "4.3.0 TLS not available due to temporary reason");
            } catch (IOException ioe) {
                // Ignore
            }
            LOGGER.log(Level.WARNING, "STARTTLS failed", e);
        }
    }

    private void auth(String args) throws IOException {
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
        } else {
            reply(504, "5.5.4 Authentication mechanism not supported");
        }
    }

    private void handleAuthPlain(String initialResponse) throws IOException {
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
                reply(535, "5.7.8 Authentication credentials invalid");
                resetAuthState();
                return;
            }
            String username = authString.substring(firstNull + 1, secondNull);
            String password = authString.substring(secondNull + 1);
            if (username.isEmpty() || password.isEmpty()) {
                reply(535, "5.7.8 Authentication credentials invalid");
                resetAuthState();
                return;
            }
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

    private void handleAuthLogin(String initialResponse) throws IOException {
        try {
            if (initialResponse != null && !initialResponse.equals("=")) {
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
            reply(535, "5.7.8 Authentication credentials invalid");
            resetAuthState();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "AUTH LOGIN error", e);
            }
        }
    }

    private void handleAuthData(String data) throws IOException {
        try {
            switch (authState) {
                case PLAIN_RESPONSE:
                    handleAuthPlain(data);
                    break;
                case LOGIN_USERNAME: {
                    byte[] decoded = Base64.getDecoder().decode(data);
                    String username = new String(decoded, US_ASCII);
                    if (username.isEmpty()) {
                        reply(535, "5.7.8 Authentication credentials invalid");
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
                        reply(535, "5.7.8 Authentication credentials invalid");
                        resetAuthState();
                        return;
                    }
                    if (authenticateUser(pendingAuthUsername, password)) {
                        authenticatedUser = pendingAuthUsername;
                        authMechanism = "LOGIN";
                        if (helloHandler != null) {
                            Principal principal = new Principal() {
                                @Override
                                public String getName() {
                                    return pendingAuthUsername;
                                }
                                @Override
                                public String toString() {
                                    return pendingAuthUsername;
                                }
                            };
                            helloHandler.authenticated(SMTPProtocolHandler.this, principal);
                        } else {
                            authenticated = true;
                            recordAuthenticationSuccess(pendingAuthUsername, "LOGIN");
                            reply(235, "2.7.0 Authentication successful");
                        }
                    } else {
                        notifyAuthenticationFailure(pendingAuthUsername, "LOGIN");
                    }
                    resetAuthState();
                    break;
                }
                default:
                    reply(503, "5.5.1 Bad sequence of commands");
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

    private boolean authenticateUser(String username, String password) {
        if (getRealm() == null) {
            return false;
        }
        return getRealm().passwordMatch(username, password);
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
        clientCertificate = null;
    }

    private void notifyAuthenticationSuccess(String username, String mechanism) throws IOException {
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
            helloHandler.authenticated(SMTPProtocolHandler.this, principal);
        } else {
            authenticated = true;
            recordAuthenticationSuccess(username, mechanism);
            reply(235, "2.7.0 Authentication successful");
        }
    }

    private void notifyAuthenticationFailure(String username, String mechanism) throws IOException {
        SMTPServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.authAttempt(mechanism);
            metrics.authFailure(mechanism);
        }
        reply(535, "5.7.8 Authentication credentials invalid");
    }

    private void quit(String args) throws IOException {
        endSessionSpan("QUIT");
        this.state = SMTPState.QUIT;
        reply(221, "2.0.0 Goodbye");
        closeEndpoint();
        if (connectedHandler != null) {
            connectedHandler.disconnected();
        }
    }

    private void noop(String args) throws IOException {
        reply(250, "2.0.0 Ok");
    }

    private void help(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            replyMultiline(214, "2.0.0 Gumdrop SMTP server - supported commands:");
            replyMultiline(214, "  HELO EHLO MAIL RCPT DATA BDAT RSET");
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
            } else {
                reply(504, "5.5.1 HELP not available for: " + args);
            }
        }
    }

    private void vrfy(String args) throws IOException {
        reply(252, "2.5.2 Cannot VRFY user, but will accept message and attempt delivery");
    }

    private void expn(String args) throws IOException {
        reply(502, "5.5.1 EXPN not implemented");
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

    private void xclient(String args) throws IOException {
        if (!isXclientAuthorized()) {
            reply(550, "5.7.0 XCLIENT not authorized");
            return;
        }
        if (state == SMTPState.MAIL || state == SMTPState.RCPT
                || state == SMTPState.DATA || state == SMTPState.BDAT) {
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
                xclientLogin = value;
                if (value != null) {
                    authenticated = true;
                    authenticatedUser = value;
                } else {
                    authenticated = false;
                    authenticatedUser = null;
                }
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
        state = SMTPState.INITIAL;
        heloName = xclientHelo;
        mailFrom = null;
        recipients.clear();
        dsnRecipients.clear();
        smtputf8 = false;
        bodyType = BodyType.SEVEN_BIT;
        deliveryRequirements = null;
        resetDataState();
        String localHostname = ((InetSocketAddress) endpoint.getLocalAddress())
                .getHostName();
        reply(220, localHostname + " ESMTP Gumdrop");
    }

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
        if (currentPipeline != null) {
            currentPipeline.reset();
            currentPipeline = null;
        }
        startSessionSpan();
    }

    private void rset(String args) throws IOException {
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

    private void mail(String args) throws IOException {
        if (state != SMTPState.READY) {
            reply(503, "5.0.0 Bad sequence of commands");
            return;
        }
        int maxTransactions = server.getMaxTransactionsPerSession();
        if (maxTransactions > 0 && sessionNumber >= maxTransactions) {
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
        String fromAddr = (sender != null) ? sender.getEnvelopeAddress() : "";
        if (authenticated && !isAuthorizedSender(fromAddr, authenticatedUser)) {
            reply(550, "5.7.1 Not authorized to send from this address");
            return;
        }
        this.mailFrom = sender;
        this.recipients.clear();
        this.dsnRecipients.clear();
        if (mailFromHandler != null) {
            DeliveryRequirements delivery = deliveryRequirements != null
                    ? deliveryRequirements : DefaultDeliveryRequirements.EMPTY;
            mailFromHandler.mailFrom(this, sender, smtputf8, delivery);
        } else {
            this.state = SMTPState.MAIL;
            reply(250, "2.1.0 Sender ok");
        }
    }

    private boolean isAuthorizedSender(String fromAddress, String authenticatedUser) {
        if (fromAddress == null || authenticatedUser == null) {
            return false;
        }
        if (authenticatedUser.equalsIgnoreCase(fromAddress)) {
            return true;
        }
        int atIndex = fromAddress.indexOf('@');
        if (atIndex > 0) {
            String localPart = fromAddress.substring(0, atIndex);
            if (authenticatedUser.equalsIgnoreCase(localPart)) {
                return true;
            }
        }
        Realm r = getRealm();
        if (r != null) {
            if (r.isUserInRole(authenticatedUser, "admin")
                    || r.isUserInRole(authenticatedUser, "postmaster")) {
                return true;
            }
        }
        return false;
    }

    private void rcpt(String args) throws IOException {
        if (state != SMTPState.MAIL && state != SMTPState.RCPT) {
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
        Set<DSNNotify> dsnNotify = null;
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
                    dsnNotify = EnumSet.noneOf(DSNNotify.class);
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
                                dsnNotify.add(DSNNotify.parse(keyword));
                            }
                            kwStart = kwEnd + 1;
                        }
                    } catch (IllegalArgumentException e) {
                        reply(501, "5.5.4 Invalid NOTIFY parameter");
                        return;
                    }
                    if (dsnNotify.contains(DSNNotify.NEVER) && dsnNotify.size() > 1) {
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
        DSNRecipientParameters pendingDsnParams = null;
        if (dsnNotify != null || orcptType != null) {
            pendingDsnParams = new DSNRecipientParameters(dsnNotify, orcptType, orcptAddress);
        }
        this.pendingRecipientDSN = pendingDsnParams;
        this.pendingRecipient = recipient;
        if (recipientHandler != null) {
            recipientHandler.rcptTo(this, recipient, server.getMailboxFactory());
        } else {
            this.recipients.add(recipient);
            this.state = SMTPState.RCPT;
            reply(250, "2.1.5 " + recipient.getEnvelopeAddress() + "... Recipient ok");
        }
    }

    private void data(String args) throws IOException {
        if (state != SMTPState.RCPT) {
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
        this.state = SMTPState.DATA;
        try {
            reply(354, "Start mail input; end with <CRLF>.<CRLF>");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending 354 response", e);
            closeEndpoint();
        }
    }

    private void bdat(String args) throws IOException {
        if (!extendedSMTP) {
            reply(503, "5.0.0 BDAT requires EHLO");
            return;
        }
        if (state != SMTPState.RCPT) {
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
        state = SMTPState.BDAT;
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
            SMTPProtocolHandler.this.rejectMessageStorageFull(handler);
        }

        @Override
        public void rejectMessageProcessingError(RecipientHandler handler) {
            SMTPProtocolHandler.this.rejectMessageProcessingError(handler);
        }

        @Override
        public void rejectMessage(String message, MailFromHandler handler) {
            SMTPProtocolHandler.this.rejectMessage(message, handler);
        }

        @Override
        public void serverShuttingDown() {
            SMTPProtocolHandler.this.serverShuttingDown();
        }
    }

    @Override
    public void rejectMessageStorageFull(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(452, "4.3.1 Insufficient system storage");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            closeEndpoint();
        }
    }

    @Override
    public void rejectMessageProcessingError(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(451, "4.3.0 Local processing error");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
            closeEndpoint();
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
            closeEndpoint();
        }
    }

    // ── ConnectedState implementation ──

    @Override
    public void acceptConnection(String greeting, HelloHandler handler) {
        this.helloHandler = handler;
        try {
            startSessionSpan();
            reply(220, greeting);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending greeting", e);
            closeEndpoint();
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
        closeEndpoint();
        if (connectedHandler != null) {
            connectedHandler.disconnected();
        }
    }

    // ── HelloState implementation ──

    @Override
    public void acceptHello(MailFromHandler handler) {
        this.mailFromHandler = handler;
        this.state = SMTPState.READY;
        try {
            sendEhloResponse();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending EHLO response", e);
            closeEndpoint();
        }
    }

    @Override
    public void rejectHelloTemporary(String message, HelloHandler handler) {
        this.helloHandler = handler;
        try {
            reply(421, "4.3.0 " + message);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    @Override
    public void rejectHello(String message, HelloHandler handler) {
        this.helloHandler = handler;
        try {
            reply(550, "5.0.0 " + message);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
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
        closeEndpoint();
    }

    @Override
    public void serverShuttingDown() {
        try {
            reply(421, "4.3.0 Server shutting down");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending shutdown notice", e);
        }
        closeEndpoint();
    }

    // ── AuthenticateState implementation ──

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
            closeEndpoint();
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
            closeEndpoint();
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
        closeEndpoint();
    }

    // ── MailFromState implementation ──

    @Override
    public void acceptSender(RecipientHandler handler) {
        this.recipientHandler = handler;
        this.state = SMTPState.MAIL;
        if (mailFromHandler != null) {
            this.currentPipeline = mailFromHandler.getPipeline();
            if (currentPipeline != null) {
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
            closeEndpoint();
        }
    }

    @Override
    public void rejectSenderGreylist(MailFromHandler handler) {
        this.mailFromHandler = handler;
        try {
            reply(450, "4.7.1 Greylisting in effect, please try again later");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    @Override
    public void rejectSenderRateLimit(MailFromHandler handler) {
        this.mailFromHandler = handler;
        try {
            reply(450, "4.7.1 Rate limit exceeded, please try again later");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    @Override
    public void rejectSenderStorageFull(MailFromHandler handler) {
        this.mailFromHandler = handler;
        try {
            reply(452, "4.3.1 Insufficient system storage");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    @Override
    public void rejectSenderBlockedDomain(MailFromHandler handler) {
        this.mailFromHandler = handler;
        try {
            reply(550, "5.1.1 Sender domain blocked by policy");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    @Override
    public void rejectSenderInvalidDomain(MailFromHandler handler) {
        this.mailFromHandler = handler;
        try {
            reply(550, "5.1.1 Sender domain does not exist");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    @Override
    public void rejectSenderPolicy(String message, MailFromHandler handler) {
        this.mailFromHandler = handler;
        try {
            reply(553, "5.7.1 " + message);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    @Override
    public void rejectSenderSpam(MailFromHandler handler) {
        this.mailFromHandler = handler;
        try {
            reply(554, "5.7.1 Sender has poor reputation");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    @Override
    public void rejectSenderSyntax(MailFromHandler handler) {
        this.mailFromHandler = handler;
        try {
            reply(501, "5.1.3 Invalid sender address format");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    // ── RecipientState implementation ──

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
            closeEndpoint();
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
        }
    }

    @Override
    public void rejectRecipientUnavailable(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(450, "4.2.1 Mailbox temporarily unavailable");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    @Override
    public void rejectRecipientSystemError(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(451, "4.3.0 Local error in processing");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    @Override
    public void rejectRecipientStorageFull(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(452, "4.3.1 Insufficient system storage");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    @Override
    public void rejectRecipientNotFound(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(550, "5.1.1 Mailbox unavailable");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    @Override
    public void rejectRecipientNotLocal(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(551, "5.1.1 User not local");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    @Override
    public void rejectRecipientQuota(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(552, "5.2.2 Mailbox full, quota exceeded");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    @Override
    public void rejectRecipientInvalid(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(553, "5.1.3 Mailbox name not allowed");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    @Override
    public void rejectRecipientRelayDenied(RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(551, "5.7.1 Relaying denied");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    @Override
    public void rejectRecipientPolicy(String message, RecipientHandler handler) {
        this.recipientHandler = handler;
        try {
            reply(553, "5.7.1 " + message);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending rejection", e);
        }
    }

    // ── MessageStartState implementation ──

    @Override
    public void acceptMessage(MessageDataHandler handler) {
        this.messageHandler = handler;
        doAcceptMessage();
    }

    // ── MessageEndState implementation ──

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
            closeEndpoint();
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
        }
    }

    // ── ResetState implementation ──

    @Override
    public void acceptReset(MailFromHandler handler) {
        this.mailFromHandler = handler;
        resetTransaction();
        try {
            reply(250, "2.0.0 Reset OK");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending reset response", e);
            closeEndpoint();
        }
    }

    // ── SMTPConnectionMetadata implementation ──

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
    public DSNEnvelopeParameters getDSNEnvelopeParameters() {
        if (deliveryRequirements == null
                || !deliveryRequirements.hasDsnParameters()) {
            return null;
        }
        return new DSNEnvelopeParameters(deliveryRequirements.getDsnReturn(),
                deliveryRequirements.getDsnEnvelopeId());
    }

    @Override
    public DSNRecipientParameters getDSNRecipientParameters(
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
        addSessionAttribute("smtp.authenticated", true);
        addSessionAttribute("smtp.auth_user", username);
        addSessionAttribute("smtp.auth_mechanism", mechanism);
        addSessionEvent("AUTH success: " + mechanism);
        SMTPServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.authAttempt(mechanism);
            metrics.authSuccess(mechanism);
        }
    }
}
