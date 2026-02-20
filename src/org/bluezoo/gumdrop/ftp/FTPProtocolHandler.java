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
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.security.cert.Certificate;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.LineParser;
import org.bluezoo.gumdrop.SecurityInfo;
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
 * FTP protocol handler using {@link ProtocolHandler} and {@link LineParser}.
 *
 * <p>Implements the FTP protocol with the transport layer fully decoupled:
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
 * @see FTPListener
 * @see https://www.rfc-editor.org/rfc/rfc959
 */
public class FTPProtocolHandler
        implements ProtocolHandler, LineParser.Callback, FTPControlConnection {

    private static final Logger LOGGER =
            Logger.getLogger(FTPProtocolHandler.class.getName());
    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.ftp.L10N");

    static final Charset US_ASCII = Charset.forName("US-ASCII");
    static final CharsetDecoder US_ASCII_DECODER = US_ASCII.newDecoder();

    private static final int MAX_LINE_LENGTH = 1024;

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
    private boolean authUsed = false;

    private CharBuffer charBuffer;

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
        this.charBuffer = CharBuffer.allocate(MAX_LINE_LENGTH + 2);
    }

    // ── ProtocolHandler implementation ──

    @Override
    public void connected(Endpoint ep) {
        this.endpoint = ep;

        if (endpoint.getRemoteAddress() != null) {
            metadata.setClientAddress((InetSocketAddress) endpoint.getRemoteAddress());
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
        LineParser.parse(data, this);
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
    }

    @Override
    public void error(Exception cause) {
        LOGGER.log(Level.WARNING, "FTP transport error", cause);
        if (endpoint != null) {
            endpoint.close();
        }
    }

    // ── LineParser.Callback implementation ──

    @Override
    public void lineReceived(ByteBuffer line) {
        try {
            int lineLen = line.remaining();
            if (lineLen < 2) {
                return;
            }
            if (lineLen > MAX_LINE_LENGTH + 2) {
                reply(500, L10N.getString("ftp.err.line_too_long"));
                if (endpoint != null && endpoint.getRemoteAddress() != null) {
                    LOGGER.warning("FTP command line too long from "
                            + endpoint.getRemoteAddress() + ": " + lineLen + " bytes");
                }
                return;
            }

            charBuffer.clear();
            US_ASCII_DECODER.reset();

            int savedLimit = line.limit();
            line.limit(savedLimit - 2);
            CoderResult result = US_ASCII_DECODER.decode(line, charBuffer, true);
            line.limit(savedLimit);

            if (result.isError()) {
                reply(500, L10N.getString("ftp.err.illegal_characters"));
                if (endpoint != null && endpoint.getRemoteAddress() != null) {
                    LOGGER.log(Level.WARNING, "Invalid FTP command encoding from "
                            + endpoint.getRemoteAddress() + ": " + result.toString());
                }
                return;
            }

            charBuffer.flip();
            String lineStr = charBuffer.toString();

            lineRead(lineStr);

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error processing FTP command", e);
            try {
                reply(500, L10N.getString("ftp.err.illegal_characters"));
            } catch (IOException e2) {
                LOGGER.log(Level.SEVERE, "Cannot write error reply", e2);
            }
        }
    }

    @Override
    public boolean continueLineProcessing() {
        return true;
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

    private void lineRead(String line) throws IOException {
        int si = line.indexOf(' ');
        String command = (si > 0) ? line.substring(0, si) : line;
        String args = (si > 0) ? line.substring(si + 1) : null;
        if ("USER".equals(command)) {
            doUser(args);
        } else if ("PASS".equals(command)) {
            doPass(args);
        } else if ("ACCT".equals(command)) {
            doAcct(args);
        } else if ("CWD".equals(command)) {
            doCwd(args);
        } else if ("CDUP".equals(command)) {
            doCdup(args);
        } else if ("SMNT".equals(command)) {
            doSmnt(args);
        } else if ("REIN".equals(command)) {
            doRein(args);
        } else if ("QUIT".equals(command)) {
            doQuit(args);
        } else if ("PORT".equals(command)) {
            doPort(args);
        } else if ("PASV".equals(command)) {
            doPasv(args);
        } else if ("EPRT".equals(command)) {
            doEprt(args);
        } else if ("EPSV".equals(command)) {
            doEpsv(args);
        } else if ("TYPE".equals(command)) {
            doType(args);
        } else if ("STRU".equals(command)) {
            doStru(args);
        } else if ("MODE".equals(command)) {
            doMode(args);
        } else if ("RETR".equals(command)) {
            doRetr(args);
        } else if ("STOR".equals(command)) {
            doStor(args);
        } else if ("STOU".equals(command)) {
            doStou(args);
        } else if ("APPE".equals(command)) {
            doAppe(args);
        } else if ("ALLO".equals(command)) {
            doAllo(args);
        } else if ("REST".equals(command)) {
            doRest(args);
        } else if ("RNFR".equals(command)) {
            doRnfr(args);
        } else if ("RNTO".equals(command)) {
            doRnto(args);
        } else if ("ABOR".equals(command)) {
            doAbor(args);
        } else if ("DELE".equals(command)) {
            doDele(args);
        } else if ("RMD".equals(command)) {
            doRmd(args);
        } else if ("MKD".equals(command)) {
            doMkd(args);
        } else if ("PWD".equals(command)) {
            doPwd(args);
        } else if ("LIST".equals(command)) {
            doList(args);
        } else if ("NLST".equals(command)) {
            doNlst(args);
        } else if ("SITE".equals(command)) {
            doSite(args);
        } else if ("SYST".equals(command)) {
            doSyst(args);
        } else if ("STAT".equals(command)) {
            doStat(args);
        } else if ("HELP".equals(command)) {
            doHelp(args);
        } else if ("NOOP".equals(command)) {
            doNoop(args);
        } else if ("AUTH".equals(command) && !authUsed) {
            doAuth(args);
        } else if ("PBSZ".equals(command)) {
            doPbsz(args);
        } else if ("PROT".equals(command)) {
            doProt(args);
        } else if ("CCC".equals(command)) {
            doCcc(args);
        } else if ("FEAT".equals(command)) {
            doFeat(args);
        } else {
            String message = L10N.getString("ftp.err.command_unrecognized");
            reply(500, MessageFormat.format(message, command));
        }
    }

    private void reply(int code, String description) throws IOException {
        String message = String.format("%d %s\r\n", code, description);
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes("US-ASCII"));
        send(buffer);
    }

    private void sendLine(String line) throws IOException {
        String message = line + "\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes("US-ASCII"));
        send(buffer);
    }

    // ── FTP commands ──

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

    private void doCwd(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        String targetPath = args.trim();

        if (!checkAuthorization(FTPOperation.NAVIGATE, targetPath)) {
            return;
        }

        FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }
        FTPFileSystem.DirectoryChangeResult result = fs.changeDirectory(targetPath, currentDirectory, metadata);

        if (result.getResult() == FTPFileOperationResult.SUCCESS) {
            currentDirectory = result.getNewDirectory();
            metadata.setCurrentDirectory(currentDirectory);
            reply(250, L10N.getString("ftp.directory_changed"));
        } else {
            handleFileOperationResult(result.getResult(), targetPath);
        }
    }

    private void doCdup(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (!checkAuthorization(FTPOperation.NAVIGATE, "..")) {
            return;
        }

        FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }

        FTPFileSystem.DirectoryChangeResult result = fs.changeDirectory("..", currentDirectory, metadata);

        if (result.getResult() == FTPFileOperationResult.SUCCESS) {
            currentDirectory = result.getNewDirectory();
            metadata.setCurrentDirectory(currentDirectory);
            reply(250, L10N.getString("ftp.directory_changed"));
        } else {
            handleFileOperationResult(result.getResult(), "..");
        }
    }

    private void doSmnt(String args) throws IOException {
        reply(502, L10N.getString("ftp.err.command_not_implemented"));
    }

    private void doRein(String args) throws IOException {
        user = null;
        password = null;
        account = null;
    }

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

            dataCoordinator.setupActiveMode(clientHost, clientPort);
            reply(200, L10N.getString("ftp.command_ok"));
        } catch (NumberFormatException e) {
            String message = L10N.getString("ftp.err.invalid_port_arguments");
            reply(501, MessageFormat.format(message, args));
        }
    }

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

            dataCoordinator.setupActiveMode(addr.getHostAddress(), port);
            reply(200, L10N.getString("ftp.eprt_ok"));

        } catch (NumberFormatException e) {
            reply(501, L10N.getString("ftp.err.invalid_eprt_syntax"));
        }
    }

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

    private void doType(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        String typeCode = args.trim().toUpperCase();
        switch (typeCode.charAt(0)) {
            case 'A':
                metadata.setTransferType(FTPConnectionMetadata.FTPTransferType.ASCII);
                reply(200, L10N.getString("ftp.command_ok"));
                break;
            case 'I':
                metadata.setTransferType(FTPConnectionMetadata.FTPTransferType.BINARY);
                reply(200, L10N.getString("ftp.command_ok"));
                break;
            case 'E':
                metadata.setTransferType(FTPConnectionMetadata.FTPTransferType.EBCDIC);
                reply(200, L10N.getString("ftp.command_ok"));
                break;
            case 'L':
                metadata.setTransferType(FTPConnectionMetadata.FTPTransferType.LOCAL);
                reply(200, L10N.getString("ftp.command_ok"));
                break;
            default:
                reply(504, L10N.getString("ftp.err.parameter_not_implemented"));
                break;
        }
    }

    private void doStru(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        String structureCode = args.trim().toUpperCase();
        switch (structureCode.charAt(0)) {
            case 'F':
                reply(200, L10N.getString("ftp.command_ok"));
                break;
            case 'R':
            case 'P':
                reply(504, L10N.getString("ftp.err.parameter_not_implemented"));
                break;
            default:
                reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
                break;
        }
    }

    private void doMode(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        String modeCode = args.trim().toUpperCase();
        switch (modeCode.charAt(0)) {
            case 'S':
                metadata.setTransferMode(FTPConnectionMetadata.FTPTransferMode.STREAM);
                reply(200, L10N.getString("ftp.command_ok"));
                break;
            case 'B':
                metadata.setTransferMode(FTPConnectionMetadata.FTPTransferMode.BLOCK);
                reply(504, L10N.getString("ftp.err.parameter_not_implemented"));
                break;
            case 'C':
                metadata.setTransferMode(FTPConnectionMetadata.FTPTransferMode.COMPRESSED);
                reply(504, L10N.getString("ftp.err.parameter_not_implemented"));
                break;
            default:
                reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
                break;
        }
    }

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

            dataCoordinator.startTransfer(transfer);

            restartOffset = 0;

            recordFileTransfer("RETR", filePath);

            reply(226, L10N.getString("ftp.transfer_complete"));

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "RETR failed for " + filePath, e);
            recordSessionException(e);
            reply(550, MessageFormat.format(L10N.getString("ftp.err.file_not_found"), filePath));
        }
    }

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

            dataCoordinator.startTransfer(transfer);

            recordFileTransfer("STOR", filePath);

            reply(226, L10N.getString("ftp.transfer_complete"));

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "STOR failed for " + filePath, e);
            recordSessionException(e);
            reply(550, L10N.getString("ftp.err.file_system_error"));
        }
    }

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

            dataCoordinator.startTransfer(transfer);

            reply(226, L10N.getString("ftp.transfer_complete"));

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "STOU failed", e);
            reply(550, L10N.getString("ftp.err.file_system_error"));
        }
    }

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

            dataCoordinator.startTransfer(transfer);

            recordFileTransfer("APPE", filePath);

            reply(226, L10N.getString("ftp.transfer_complete"));

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "APPE failed for " + filePath, e);
            recordSessionException(e);
            reply(550, L10N.getString("ftp.err.file_system_error"));
        }
    }

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

    private void doRnfr(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        String sourcePath = args.trim();

        if (!checkAuthorization(FTPOperation.RENAME, sourcePath)) {
            return;
        }

        FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }
        FTPFileInfo info = fs.getFileInfo(sourcePath, metadata);
        if (info != null) {
            renameFrom = sourcePath;
            reply(350, L10N.getString("ftp.rename_pending"));
        } else {
            reply(550, MessageFormat.format(L10N.getString("ftp.err.file_not_found"), sourcePath));
        }
    }

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

        FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }

        String targetPath = args.trim();
        FTPFileOperationResult result = fs.rename(renameFrom, targetPath, metadata);
        handleFileOperationResult(result, renameFrom + " -> " + targetPath);

        renameFrom = null;
    }

    private void doAbor(String args) throws IOException {
        dataCoordinator.abortTransfer();
        reply(225, "ABOR command successful");
    }

    private void doDele(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        String filePath = args.trim();

        if (!checkAuthorization(FTPOperation.DELETE, filePath)) {
            return;
        }

        FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }

        FTPFileOperationResult result = fs.deleteFile(filePath, metadata);
        if (result == FTPFileOperationResult.SUCCESS) {
            recordFileOperation("DELE", filePath);
        }
        handleFileOperationResult(result, filePath);
    }

    private void doRmd(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        String dirPath = args.trim();

        if (!checkAuthorization(FTPOperation.DELETE_DIR, dirPath)) {
            return;
        }

        FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }

        FTPFileOperationResult result = fs.removeDirectory(dirPath, metadata);
        handleFileOperationResult(result, dirPath);
    }

    private void doMkd(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }

        String dirPath = args.trim();

        if (!checkAuthorization(FTPOperation.CREATE_DIR, dirPath)) {
            return;
        }

        FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }

        FTPFileOperationResult result = fs.createDirectory(dirPath, metadata);

        if (result == FTPFileOperationResult.SUCCESS) {
            String successMsg = L10N.getString("ftp.directory_created");
            reply(257, MessageFormat.format(successMsg, dirPath));
        } else {
            handleFileOperationResult(result, dirPath);
        }
    }

    private void doPwd(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }

        reply(257, "\"" + currentDirectory + "\" " + L10N.getString("ftp.directory_created").substring(4));
    }

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

            dataCoordinator.startTransfer(transfer);

            reply(226, L10N.getString("ftp.transfer_complete"));

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "LIST failed for " + listPath, e);
            reply(550, MessageFormat.format(L10N.getString("ftp.err.file_not_found"), listPath));
        }
    }

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
                    FTPDataConnectionCoordinator.TransferType.LISTING,
                    listPath,
                    false,
                    0,
                    handler,
                    metadata
                );

            dataCoordinator.startTransfer(transfer);

            reply(226, L10N.getString("ftp.transfer_complete"));

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "NLST failed for " + listPath, e);
            reply(550, MessageFormat.format(L10N.getString("ftp.err.file_not_found"), listPath));
        }
    }

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

    private void doSyst(String args) throws IOException {
        reply(215, L10N.getString("ftp.system_type"));
    }

    private void doStat(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            StringBuilder status = new StringBuilder();
            status.append("Server: ").append(metadata.getConnectorDescription()).append("\r\n");
            status.append("User: ").append(authenticated ? user : "Not logged in").append("\r\n");
            status.append("Directory: ").append(currentDirectory).append("\r\n");
            status.append("Secure: ").append(metadata.isSecureConnection() ? "Yes" : "No").append("\r\n");
            reply(211, status.toString());
        } else {
            FTPFileSystem fs = getFileSystem();
            if (fs != null) {
                FTPFileInfo info = fs.getFileInfo(args.trim(), metadata);
                if (info != null) {
                    reply(213, L10N.getString("ftp.file_status") + ": " + info.formatAsListingLine());
                } else {
                    reply(550, MessageFormat.format(L10N.getString("ftp.err.file_not_found"), args));
                }
            } else {
                reply(550, L10N.getString("ftp.err.file_system_error"));
            }
        }
    }

    private void doHelp(String args) throws IOException {
        reply(214, L10N.getString("ftp.help_message"));
    }

    private void doNoop(String args) throws IOException {
        reply(200, L10N.getString("ftp.no_operation"));
    }

    // ── RFC 4217 - FTP Security Extensions ──

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
            authUsed = true;

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

    private void doCcc(String args) throws IOException {
        reply(533, L10N.getString("ftp.err.ccc_not_supported"));
    }

    private void doFeat(String args) throws IOException {
        reply(211, "-Extensions supported:");

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

        sendLine(" UTF8");
        sendLine(" SIZE");
        sendLine(" MDTM");
        sendLine(" REST STREAM");
        sendLine(" TVFS");

        reply(211, " End");
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
