/*
 * NntpClientProtocolHandler.java
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

package org.bluezoo.gumdrop.nntp.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import org.bluezoo.gumdrop.ByteStreamLexer;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.auth.SaslClientMechanism;
import org.bluezoo.gumdrop.auth.SaslUtils;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.nntp.client.NntpClient.StartTls;
import org.bluezoo.gumdrop.nntp.client.NntpCommand.Block;
import org.bluezoo.gumdrop.telemetry.EventLogger;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.util.DotUnstuffer;

/**
 * Client side of an NNTP connection (RFC 3977): reads the greeting and
 * CAPABILITIES, upgrades with STARTTLS (RFC 4642) and logs in with AUTHINFO
 * USER/PASS (RFC 4643) as configured, then runs the commands queued on the
 * {@link NntpSession} one at a time.
 *
 * <p>Status lines are read by a streaming lexer. The multi-line block after
 * some of them goes through a {@link DotUnstuffer} and is passed on as it
 * arrives: article text as it comes, LIST ACTIVE and OVER output one parsed
 * line at a time.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class NntpClientProtocolHandler
        implements ProtocolHandler, ByteStreamLexer.Handler<NntpClientLexer.Token> {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.nntp.L10N");

    /** The longest LIST ACTIVE or OVER line kept; a longer one is skipped. */
    private static final int MAX_ENTRY_LINE = 65536;

    private enum Phase { GREETING, CAPABILITIES, STARTTLS, PENDING_TLS, AUTH_USER, AUTH_PASS, AUTH_SASL, READY, CLOSED }

    private final NntpClientHandler handler;
    private final String user;
    private final String password;
    private final StartTls tlsMode;
    private final long commandTimeoutMs;
    private final String saslMechanism;
    private final String host;
    private SaslClientMechanism saslClient;

    private Endpoint endpoint;
    private Phase phase = Phase.GREETING;
    private boolean afterTls;
    private boolean authenticated;
    private NntpStatus greeting;
    private List<String> capabilities = new ArrayList<String>();
    private boolean notified;
    private volatile boolean alive = true;

    // The commands
    private final ArrayDeque<NntpCommand> queue = new ArrayDeque<NntpCommand>();
    private NntpCommand inflight;
    private boolean awaitingFinal;
    private NntpStatus blockStatus;
    private String closedReason;
    private TimerHandle timer;

    // The status line being read
    private final NntpClientLexer lexer = new NntpClientLexer(this, Integer.MAX_VALUE);
    private String pendingWord = "";
    private final StringBuilder pendingText = new StringBuilder();

    // The block being read
    private final DotUnstuffer unstuffer = new DotUnstuffer(new BlockReceiver());
    private boolean blockActive;
    private Block block = Block.NONE;
    private NntpCommand blockCommand;
    private boolean blockIsCapabilities;
    private byte[] lineBuf = new byte[256];
    private int lineLen;
    private boolean lineOverflow;

    NntpClientProtocolHandler(NntpClientHandler handler, String user, String password,
                              StartTls tlsMode, long commandTimeoutMs, String saslMechanism,
                              String host) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
        this.user = user;
        this.password = password;
        this.tlsMode = tlsMode;
        this.commandTimeoutMs = commandTimeoutMs;
        this.saslMechanism = saslMechanism;
        this.host = host;
    }

    private EventLogger events() {
        TelemetryConfig telemetry = endpoint != null ? endpoint.getTelemetryConfig() : new TelemetryConfig();
        return telemetry.getLogger(NntpClientProtocolHandler.class, L10N);
    }

    boolean isAlive() {
        return alive;
    }

    // ── ProtocolHandler ──

    @Override
    public void connected(Endpoint ep) {
        this.endpoint = ep;
    }

    @Override
    public void receive(ByteBuffer data) {
        // A status line and the block after it may arrive in one read: the
        // lexer stops where the block starts, so alternate until drained.
        while (data.hasRemaining() && phase != Phase.CLOSED) {
            if (blockActive) {
                if (!unstuffer.process(data)) {
                    blockActive = false;
                }
            } else {
                int before = data.remaining();
                lexer.feed(data);
                if (!blockActive && data.remaining() == before) {
                    break;
                }
            }
        }
    }

    @Override
    public void disconnected() {
        boolean wasClosed = phase == Phase.CLOSED;
        phase = Phase.CLOSED;
        alive = false;
        cancelTimer();
        failCommands("connection closed");
        if (!wasClosed) {
            if (!notified) {
                handler.error(new IOException("connection closed during setup"));
            }
            handler.disconnected();
        }
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        if (phase == Phase.PENDING_TLS) {
            afterTls = true;
            requestCapabilities();
        }
    }

    @Override
    public void error(Exception cause) {
        fail(cause);
    }

    // ── Lexer handler ──

    @Override
    public boolean token(NntpClientLexer.Token type, ByteBuffer window) {
        switch (type) {
            case WORD:
                pendingWord = ascii(window);
                return false;
            case SP:
                return true; // the rest of the line is text
            case TEXT:
                pendingText.append(new String(bytes(window), StandardCharsets.UTF_8));
                return false;
            case CRLF:
                String word = pendingWord;
                String text = pendingText.toString();
                pendingWord = "";
                pendingText.setLength(0);
                statusLine(word, text);
                return false;
            default:
                return false;
        }
    }

    @Override
    public void rawBytes(ByteBuffer slice) {
        events().warn("warn.nntp_client_unexpected_raw_bytes").emit();
    }

    @Override
    public void tokenTooLong() {
        events().warn("warn.nntp_client_unexpected_token_too_long").emit();
    }

    private static byte[] bytes(ByteBuffer window) {
        byte[] b = new byte[window.remaining()];
        window.get(b);
        return b;
    }

    private static String ascii(ByteBuffer window) {
        return new String(bytes(window), StandardCharsets.US_ASCII);
    }

    private void statusLine(String word, String text) {
        int code = parseCode(word);
        if (code < 0) {
            events().warn("warn.nntp_client_bad_status_line").attr("line", (word + " " + text).trim()).emit();
            fail(new IOException("malformed response: " + (word + " " + text).trim()));
            return;
        }
        cancelTimer();
        dispatch(new NntpStatus(code, text));
    }

    private static int parseCode(String word) {
        if (word.length() != 3) {
            return -1;
        }
        int code = 0;
        for (int i = 0; i < 3; i++) {
            char c = word.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            code = code * 10 + (c - '0');
        }
        return code;
    }

    // ── Setting up the connection ──

    private void dispatch(NntpStatus s) {
        switch (phase) {
            case GREETING:
                onGreeting(s);
                break;
            case CAPABILITIES:
                if (s.getCode() == 101) {
                    startBlock(Block.CONTENT, null, true);
                } else {
                    // a server without CAPABILITIES (RFC 3977 section 5.2): carry on without
                    capabilities = new ArrayList<String>();
                    afterCapabilities();
                }
                break;
            case STARTTLS:
                onStartTls(s);
                break;
            case AUTH_USER:
                onAuthUser(s);
                break;
            case AUTH_PASS:
                onAuthPass(s);
                break;
            case AUTH_SASL:
                onAuthSasl(s);
                break;
            case READY:
                onCommandReply(s);
                break;
            default:
                break;
        }
    }

    private void onGreeting(NntpStatus s) {
        if (s.getCode() != 200 && s.getCode() != 201) {
            fail(new NntpException(s.getCode(), "greeting: " + s));
            return;
        }
        greeting = s;
        requestCapabilities();
    }

    private void requestCapabilities() {
        phase = Phase.CAPABILITIES;
        send("CAPABILITIES");
    }

    private void afterCapabilities() {
        boolean wantTls = tlsMode != StartTls.NEVER && !endpoint.isSecure() && !afterTls;
        if (wantTls) {
            if (capabilities.contains("STARTTLS")) {
                phase = Phase.STARTTLS;
                send("STARTTLS");
                return;
            }
            if (tlsMode == StartTls.REQUIRED) {
                fail(new IOException("server does not offer STARTTLS"));
                return;
            }
        }
        login();
    }

    private void onStartTls(NntpStatus s) {
        if (s.getCode() == 382) {
            phase = Phase.PENDING_TLS;
            try {
                endpoint.startTLS();
            } catch (IOException e) {
                fail(e);
            }
            return;
        }
        if (tlsMode == StartTls.REQUIRED) {
            fail(new NntpException(s.getCode(), "STARTTLS: " + s));
            return;
        }
        login();
    }

    private void login() {
        if (saslMechanism != null) {
            startSasl();
            return;
        }
        if (user == null) {
            ready();
            return;
        }
        phase = Phase.AUTH_USER;
        send("AUTHINFO USER " + user);
    }

    private void onAuthUser(NntpStatus s) {
        if (s.getCode() == 281) {
            authenticated = true;
            ready();
        } else if (s.getCode() == 381) {
            phase = Phase.AUTH_PASS;
            send("AUTHINFO PASS " + password);
        } else {
            fail(new NntpException(s.getCode(), "AUTHINFO USER: " + s));
        }
    }

    private void onAuthPass(NntpStatus s) {
        if (s.getCode() == 281) {
            authenticated = true;
            ready();
        } else {
            fail(new NntpException(s.getCode(), "AUTHINFO PASS: " + s));
        }
    }

    // ── AUTHINFO SASL (RFC 4643 section 2.4) ──

    private void startSasl() {
        saslClient = SaslUtils.createClient(saslMechanism, user, password, host);
        if (saslClient == null) {
            fail(new IOException("SASL mechanism not supported by the client: " + saslMechanism));
            return;
        }
        if (!capabilities.isEmpty() && !offersSasl(saslClient.getMechanismName())) {
            fail(new IOException("server does not offer SASL " + saslClient.getMechanismName()));
            return;
        }
        phase = Phase.AUTH_SASL;
        String line = "AUTHINFO SASL " + saslClient.getMechanismName();
        try {
            if (saslClient.hasInitialResponse()) {
                line += " " + encode(saslClient.evaluateChallenge(new byte[0]));
            }
        } catch (IOException e) {
            fail(e);
            return;
        }
        send(line);
    }

    private boolean offersSasl(String mechanism) {
        for (int i = 0; i < capabilities.size(); i++) {
            String cap = capabilities.get(i);
            if (cap.startsWith("SASL ")) {
                String[] words = cap.split(" ");
                for (int j = 1; j < words.length; j++) {
                    if (words[j].equalsIgnoreCase(mechanism)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void onAuthSasl(NntpStatus s) {
        try {
            switch (s.getCode()) {
                case 281:
                    authenticated = true;
                    ready();
                    break;
                case 283:
                    // success with final server data, which the mechanism checks
                    saslClient.evaluateChallenge(decode(s.getText()));
                    authenticated = true;
                    ready();
                    break;
                case 383:
                    send(encode(saslClient.evaluateChallenge(decode(s.getText()))));
                    break;
                default:
                    fail(new NntpException(s.getCode(), "AUTHINFO SASL: " + s));
                    break;
            }
        } catch (IOException e) {
            fail(e);
        } catch (IllegalArgumentException e) {
            fail(new IOException("malformed SASL data from the server"));
        }
    }

    /** RFC 4643 section 2.4.1: base64, with "=" for no data. */
    private static String encode(byte[] data) {
        return data == null || data.length == 0 ? "=" : Base64.getEncoder().encodeToString(data);
    }

    private static byte[] decode(String text) {
        String t = text.trim();
        return t.equals("=") || t.isEmpty() ? new byte[0] : Base64.getDecoder().decode(t);
    }

    private void ready() {
        phase = Phase.READY;
        notified = true;
        NntpSession session = new NntpSession(this, capabilities, greeting.getCode() == 200);
        NntpGreeting g = new NntpGreeting(greeting, capabilities, endpoint.isSecure(), authenticated);
        try {
            handler.connected(session, g);
        } catch (RuntimeException e) {
            events().warn("warn.nntp_client_callback_failed").thrown(e).emit();
        }
        pump();
    }

    // ── Commands ──

    /** Queues a command; may be called from any thread. */
    void submit(final NntpCommand command) {
        endpoint.execute(new Runnable() {
            @Override
            public void run() {
                if (closedReason != null) {
                    fail(command, closedReason);
                    return;
                }
                if (command.quit) {
                    closedReason = "QUIT sent";
                    alive = false;
                }
                queue.add(command);
                pump();
            }
        });
    }

    private void pump() {
        if (phase != Phase.READY || inflight != null || queue.isEmpty()) {
            return;
        }
        inflight = queue.poll();
        awaitingFinal = false;
        send(inflight.line);
    }

    private void onCommandReply(NntpStatus s) {
        NntpCommand c = inflight;
        if (c == null) {
            events().warn("warn.nntp_client_unexpected_reply").attr("line", s.toString()).emit();
            return;
        }
        if (awaitingFinal) {
            awaitingFinal = false;
            finish(c, s);
            return;
        }
        if (c.continuationCode != 0 && s.getCode() == c.continuationCode) {
            awaitingFinal = true;
            endpoint.send(ByteBuffer.wrap(c.payload));
            armTimer();
            return;
        }
        if (c.block != Block.NONE && announcesBlock(s.getCode())) {
            blockStatus = s;
            startBlock(c.block, c, false);
            return;
        }
        finish(c, s);
    }

    /** Responses that are followed by a multi-line block (RFC 3977 section 3.2). */
    private static boolean announcesBlock(int code) {
        switch (code) {
            case 100: case 101: case 211: case 215: case 220: case 221: case 222:
            case 224: case 225: case 230: case 231:
                return true;
            default:
                return false;
        }
    }

    private void finish(NntpCommand c, NntpStatus s) {
        inflight = null;
        blockStatus = null;
        try {
            c.done.completed(s);
        } catch (RuntimeException e) {
            events().warn("warn.nntp_client_callback_failed").thrown(e).emit();
        }
        if (c.quit) {
            // the transport reports the close through disconnected()
            endpoint.close();
            return;
        }
        pump();
    }

    private void fail(NntpCommand c, String reason) {
        try {
            c.done.failed(new NntpException(0, reason));
        } catch (RuntimeException e) {
            events().warn("warn.nntp_client_callback_failed").thrown(e).emit();
        }
    }

    private void failCommands(String reason) {
        if (closedReason == null) {
            closedReason = reason;
        }
        NntpCommand c = inflight;
        inflight = null;
        if (c != null) {
            fail(c, reason);
        }
        NntpCommand q;
        while ((q = queue.poll()) != null) {
            fail(q, reason);
        }
    }

    private void fail(Exception cause) {
        if (phase == Phase.CLOSED) {
            return;
        }
        phase = Phase.CLOSED;
        alive = false;
        cancelTimer();
        failCommands(cause.getMessage() != null ? cause.getMessage() : cause.toString());
        try {
            handler.error(cause);
        } catch (RuntimeException e) {
            events().warn("warn.nntp_client_callback_failed").thrown(e).emit();
        }
        if (endpoint != null) {
            endpoint.close();
        }
    }

    // ── Blocks ──

    private void startBlock(Block kind, NntpCommand command, boolean capabilitiesBlock) {
        block = kind;
        blockCommand = command;
        blockIsCapabilities = capabilitiesBlock;
        lineLen = 0;
        lineOverflow = false;
        if (capabilitiesBlock) {
            capabilities = new ArrayList<String>();
        }
        unstuffer.reset();
        blockActive = true;
        lexer.stopForHandoff();
    }

    /** Receives the dot-unstuffed block. */
    private final class BlockReceiver implements DotUnstuffer.Callback {

        @Override
        public void content(ByteBuffer chunk) {
            try {
                if (!blockIsCapabilities && block == Block.CONTENT) {
                    NntpConsumer<ByteBuffer> sink = blockCommand.contentSink;
                    if (sink != null) {
                        sink.accept(chunk);
                    }
                    return;
                }
                while (chunk.hasRemaining()) {
                    byte b = chunk.get();
                    if (b == '\n') {
                        line();
                    } else if (lineLen < MAX_ENTRY_LINE) {
                        if (lineLen == lineBuf.length) {
                            byte[] bigger = new byte[lineBuf.length * 2];
                            System.arraycopy(lineBuf, 0, bigger, 0, lineLen);
                            lineBuf = bigger;
                        }
                        lineBuf[lineLen++] = b;
                    } else {
                        lineOverflow = true;
                    }
                }
            } catch (RuntimeException e) {
                events().warn("warn.nntp_client_callback_failed").thrown(e).emit();
            }
        }

        @Override
        public void complete() {
            blockActive = false;
            if (blockIsCapabilities) {
                blockIsCapabilities = false;
                block = Block.NONE;
                afterCapabilities();
                return;
            }
            NntpCommand c = blockCommand;
            NntpStatus s = blockStatus;
            blockCommand = null;
            block = Block.NONE;
            finish(c, s);
        }
    }

    /** A line of a capabilities list, LIST ACTIVE or OVER has been read. */
    private void line() {
        int len = lineLen;
        boolean skipped = lineOverflow;
        lineLen = 0;
        lineOverflow = false;
        if (len > 0 && lineBuf[len - 1] == '\r') {
            len--;
        }
        if (skipped) {
            events().warn("warn.nntp_client_line_too_long").emit();
            return;
        }
        String text = new String(lineBuf, 0, len, StandardCharsets.UTF_8);
        if (blockIsCapabilities) {
            String cap = text.trim();
            if (!cap.isEmpty()) {
                capabilities.add(cap.toUpperCase(Locale.ENGLISH));
            }
        } else if (block == Block.NEWSGROUPS) {
            NewsgroupEntry e = NewsgroupEntry.parse(text);
            if (e != null && blockCommand.groupSink != null) {
                blockCommand.groupSink.accept(e);
            }
        } else if (block == Block.OVERVIEW) {
            OverviewEntry e = OverviewEntry.parse(text);
            if (e != null && blockCommand.overviewSink != null) {
                blockCommand.overviewSink.accept(e);
            }
        }
    }

    // ── Transport ──

    private void send(String line) {
        endpoint.send(ByteBuffer.wrap((line + "\r\n").getBytes(StandardCharsets.UTF_8)));
        armTimer();
    }

    private void armTimer() {
        cancelTimer();
        if (commandTimeoutMs <= 0 || endpoint == null) {
            return;
        }
        timer = endpoint.scheduleTimer(commandTimeoutMs, new Runnable() {
            @Override
            public void run() {
                fail(new IOException("no reply within " + commandTimeoutMs + " ms"));
            }
        });
    }

    private void cancelTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /** Closes the connection. */
    void close() {
        if (endpoint != null) {
            endpoint.close();
        }
    }

    boolean isOpen() {
        return endpoint != null && endpoint.isOpen() && phase != Phase.CLOSED;
    }

}
