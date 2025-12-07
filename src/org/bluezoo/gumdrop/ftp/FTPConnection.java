/*
 * FTPConnection.java
 * Copyright (C) 2006 Chris Burdess
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

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.SendCallback;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.quota.Quota;
import org.bluezoo.gumdrop.quota.QuotaManager;
import org.bluezoo.gumdrop.quota.QuotaPolicy;
import org.bluezoo.gumdrop.quota.QuotaSource;
import org.bluezoo.gumdrop.util.LineInput;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * Connection handler for the FTP protocol.
 * This manages one TCP control connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see https://www.rfc-editor.org/rfc/rfc959
 */
public class FTPConnection extends Connection {

    private static final Logger LOGGER = Logger.getLogger(FTPConnection.class.getName());
    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.ftp.L10N");

    static final Charset US_ASCII = Charset.forName("US-ASCII");
    static final CharsetDecoder US_ASCII_DECODER = US_ASCII.newDecoder();

    // Using base class protected SocketChannel channel field
    private final FTPServer server;
    private final FTPConnectionHandler handler;
    private final FTPConnectionMetadata metadata;
    private final FTPDataConnectionCoordinator dataCoordinator;

    private String user;
    private String password;
    private String account;
    private String renameFrom;
    private String currentDirectory = "/";
    private boolean authenticated = false;
    private long restartOffset = 0; // For REST command
    private boolean epsvAllMode = false;
    private boolean pbszSet = false;
    private boolean dataProtection = false;
    private boolean authUsed = false;

    private ByteBuffer in; // input buffer
    private LineReader lineReader;

    // Telemetry
    private Trace connectionTrace = null;
    private Span sessionSpan = null;
    private Span authenticatedSpan = null;

    class LineReader implements LineInput {

        private CharBuffer sink; // character buffer to receive decoded characters

        @Override public ByteBuffer getLineInputBuffer() {
            return in;
        }

        @Override public CharBuffer getOrCreateLineInputCharacterSink(int capacity) {
            if (sink == null || sink.capacity() < capacity) {
                sink = CharBuffer.allocate(capacity);
            }
            return sink;
        }

    }

    protected FTPConnection(FTPServer server, SocketChannel channel, SSLEngine engine, boolean secure, FTPConnectionHandler handler) {
        super(engine, secure);
        this.server = server;
        this.channel = channel;
        this.handler = handler;
        
        // Initialize metadata
        FTPConnectionMetadata tempMetadata;
        try {
            InetSocketAddress clientAddr = null;
            InetSocketAddress serverAddr = null;
            if (channel != null) {
                clientAddr = (InetSocketAddress) channel.getRemoteAddress();
                serverAddr = (InetSocketAddress) channel.getLocalAddress();
            }
            
            tempMetadata = new FTPConnectionMetadata(
                clientAddr,
                serverAddr,
                secure, // isSecureConnection
                null, // clientCertificates - TODO: get from SSLEngine if available
                null, // cipherSuite - TODO: get from SSLEngine if available
                null, // protocolVersion - TODO: get from SSLEngine if available
                System.currentTimeMillis(), // connectionStartTimeMillis
                "ftp" // connectorDescription
            );
        } catch (IOException e) {
            // Fallback if we can't get addresses
            tempMetadata = new FTPConnectionMetadata(
                null, null, secure, null, null, null,
                System.currentTimeMillis(), "ftp"
            );
        }
        this.metadata = tempMetadata;
        
        // Initialize data connection coordinator
        this.dataCoordinator = new FTPDataConnectionCoordinator(this);
        
        // Initialize input buffer for line reading
        in = ByteBuffer.allocate(8192);
        lineReader = this.new LineReader();
    }
    
    @Override
    public void setSendCallback(SendCallback callback) {
        super.setSendCallback(callback);
    }
    
    @Override
    public void init() throws IOException {
        super.init();

        // Initialize telemetry
        initConnectionTrace();
        startSessionSpan();
        
        // Send welcome banner after channel is properly initialized
        try {
            if (handler != null) {
                String customBanner = handler.connected(metadata);
                if (customBanner != null && !customBanner.isEmpty()) {
                    reply(220, customBanner);
                } else {
                    reply(220, L10N.getString("ftp.welcome_banner").substring(4)); // Remove "220 " prefix
                }
            } else {
                reply(220, L10N.getString("ftp.welcome_banner").substring(4));
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send welcome banner", e);
            throw e; // Re-throw to prevent connection from being established
        }
    }

    @Override
    public void receive(ByteBuffer buf) {
        try {
            // buf is already in read mode - copy its data to our internal buffer
            // We must consume all the data from buf since we can't retain a reference
            
            // Ensure our internal buffer is in write mode to accept new data
            if (in.position() == 0 && in.limit() == in.capacity()) {
                // Buffer is empty and ready for writing
            } else {
                // Buffer contains partial data from previous received() call
                // Position to end of existing data for appending
                in.position(in.limit());
                in.limit(in.capacity());
            }
            
            // Copy all available data from buf to our internal buffer
            while (buf.hasRemaining()) {
                if (!in.hasRemaining()) {
                    // Internal buffer is full - need to expand or handle overflow
                    // For FTP commands, this should be rare since commands are typically short
                    LOGGER.warning("FTP command buffer full, expanding buffer");
                    
                    // Create larger buffer and copy existing data
                    ByteBuffer newBuffer = ByteBuffer.allocate(in.capacity() * 2);
                    in.flip(); // Switch to read mode to copy existing data
                    newBuffer.put(in);
                    in = newBuffer;
                    // newBuffer is now positioned for appending
                }
                in.put(buf.get());
            }
            
            // Switch internal buffer to read mode for line processing
            in.flip();
            
            // Process all complete lines available
            String line = lineReader.readLine(US_ASCII_DECODER);
            while (line != null) {
                lineRead(line);
                line = lineReader.readLine(US_ASCII_DECODER);
            }
            
            // After readLine() processing:
            // - If lines were found: buffer is positioned after the processed lines
            // - If no complete line: buffer contains partial data and is ready for more data
            // The LineInput.readLine() method manages the buffer state correctly
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error processing FTP command", e);
            try {
                reply(500, L10N.getString("ftp.err.illegal_characters")); // Syntax error
            } catch (IOException e2) {
                LOGGER.log(Level.SEVERE, "Cannot write error reply", e2);
            }
        }
    }

    /**
     * Called when the connection is closed by the peer.
     * Uses try-finally to guarantee telemetry completion and resource cleanup.
     */
    protected void disconnected() throws IOException {
        try {
            // End telemetry spans first (if still active)
            // Note: If QUIT was processed, spans will already be ended
            endAuthenticatedSpan();
            if (sessionSpan != null && !sessionSpan.isEnded()) {
                endSessionSpanError("Connection lost");
            }
        } finally {
            // Clean up data connection coordinator in finally block
            try {
                if (dataCoordinator != null) {
                    dataCoordinator.cleanup();
                }
            } finally {
                // Notify handler
                if (handler != null) {
                    handler.disconnected(metadata);
                }
            }
        }
    }

    /**
     * Helper method to get the file system from the handler
     */
    private FTPFileSystem getFileSystem() {
        if (handler != null && authenticated) {
            return handler.getFileSystem(metadata);
        }
        return null;
    }

    /**
     * Checks if the current user is authorized to perform an operation.
     * If the handler returns false, sends a 550 permission denied response.
     * 
     * @param operation the operation being attempted
     * @param path the file/directory path (may be null)
     * @return true if authorized, false if denied (and response already sent)
     */
    private boolean checkAuthorization(FTPOperation operation, String path) throws IOException {
        if (handler != null && !handler.isAuthorized(operation, path, metadata)) {
            reply(550, L10N.getString("ftp.err.permission_denied"));
            return false;
        }
        return true;
    }

    /**
     * Maps FTPAuthenticationResult to FTP response codes and sends appropriate reply
     */
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
                reply(530, L10N.getString("ftp.err.invalid_password")); // Generic login incorrect
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

    /**
     * Maps FTPFileOperationResult to FTP response codes and sends appropriate reply
     */
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

    /**
     * Invoked when a complete CRLF-terminated string has been read from the
     * connection. Does not include the CRLF.
     *
     * @param line the line read
     */
    void lineRead(String line) throws IOException {
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

    protected void reply(int code, String description) throws IOException {
        String message = String.format("%d %s\r\n", code, description);
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes("US-ASCII"));
        send(buffer);
    }

    /**
     * Sends a line of text to the client without a reply code.
     * Used for multi-line responses where intermediate lines don't include the code.
     * @param line the line to send (without CRLF)
     */
    protected void sendLine(String line) throws IOException {
        String message = line + "\r\n";
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes("US-ASCII"));
        send(buffer);
    }

    /**
     * USER NAME
     */
    protected void doUser(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }
        
        user = args.trim();
        password = null; // Reset password when user changes
        account = null;
        authenticated = false;
        
        if (handler != null) {
            FTPAuthenticationResult result = handler.authenticate(user, null, null, metadata);
            handleAuthenticationResult(result);
        } else {
            // Default behavior without handler - just ask for password
            reply(331, L10N.getString("ftp.user_ok_need_password"));
        }
    }

    /**
     * PASSWORD
     */
    protected void doPass(String args) throws IOException {
        if (user == null) {
            reply(503, L10N.getString("ftp.err.bad_sequence"));
            return;
        }
        
        password = args; // Password can be empty for anonymous
        
        if (handler != null) {
            FTPAuthenticationResult result = handler.authenticate(user, password, account, metadata);
            handleAuthenticationResult(result);
        } else {
            // Default behavior without handler - always deny
            reply(530, L10N.getString("ftp.err.invalid_password"));
        }
    }

    /**
     * ACCOUNT
     */
    protected void doAcct(String args) throws IOException {
        if (user == null) {
            reply(503, L10N.getString("ftp.err.bad_sequence"));
            return;
        }
        
        account = args;
        
        if (handler != null) {
            FTPAuthenticationResult result = handler.authenticate(user, password, account, metadata);
            handleAuthenticationResult(result);
        } else {
            // Default behavior without handler - account not required
            reply(202, L10N.getString("ftp.command_ok"));
        }
    }

    /**
     * CHANGE WORKING DIRECTORY
     */
    protected void doCwd(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }
        
        String targetPath = args.trim();
        
        // Check authorization for navigate operation
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

    /**
     * CHANGE TO PARENT DIRECTORY
     */
    protected void doCdup(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        
        // Check authorization for navigate operation
        if (!checkAuthorization(FTPOperation.NAVIGATE, "..")) {
            return;
        }
        
        FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }
        
        // CDUP is equivalent to CWD ..
        FTPFileSystem.DirectoryChangeResult result = fs.changeDirectory("..", currentDirectory, metadata);
        
        if (result.getResult() == FTPFileOperationResult.SUCCESS) {
            currentDirectory = result.getNewDirectory();
            metadata.setCurrentDirectory(currentDirectory);
            reply(250, L10N.getString("ftp.directory_changed"));
        } else {
            handleFileOperationResult(result.getResult(), "..");
        }
    }

    /**
     * STRUCTURE MOUNT
     */
    protected void doSmnt(String args) throws IOException {
        // SMNT is rarely implemented and not required by most FTP servers
        reply(502, L10N.getString("ftp.err.command_not_implemented"));
    }

    /**
     * REINITIALIZE
     */
    protected void doRein(String args) throws IOException {
        user = null;
        password = null;
        account = null;
    }

    /**
     * LOGOUT
     */
    protected void doQuit(String args) throws IOException {
        addSessionEvent("QUIT");
        reply(221, L10N.getString("ftp.goodbye"));
        
        // Clean up state
        user = null;
        password = null;
        account = null;
        authenticated = false;

        // End telemetry spans
        endAuthenticatedSpan();
        endSessionSpan();
        
        // Close the connection
        close();
    }

    /**
     * DATA PORT (IPv4 only)
     * Format: h1,h2,h3,h4,p1,p2
     */
    protected void doPort(String args) throws IOException {
        if (epsvAllMode) {
            reply(522, L10N.getString("ftp.err.epsv_all_active"));
            return;
        }
        String[] fields = args.split(",");
        try {
            if (fields.length != 6) {
                String message = L10N.getString("ftp.err.invalid_port_arguments");
                reply(501, MessageFormat.format(message, args));
                return;
            }
            // Build IPv4 host string
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
            
            // Extract port
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
            
            // Set up active mode through coordinator
            dataCoordinator.setupActiveMode(clientHost, clientPort);
            reply(200, L10N.getString("ftp.command_ok"));
        } catch (NumberFormatException e) {
            String message = L10N.getString("ftp.err.invalid_port_arguments");
            reply(501, MessageFormat.format(message, args));
        }
    }

    /**
     * PASSIVE (IPv4 only)
     */
    protected void doPasv(String args) throws IOException {
        if (epsvAllMode) {
            reply(522, L10N.getString("ftp.err.epsv_all_active"));
            return;
        }
        try {
            // PASV command typically doesn't take arguments, but some clients send them
            int requestedPort = 0; // 0 = system-assigned port
            if (args != null && !args.trim().isEmpty()) {
                requestedPort = Integer.parseInt(args.trim());
            }
            
            // Set up passive mode through coordinator
            int actualPort = dataCoordinator.setupPassiveMode(requestedPort);
            
            // Get server's local address for PASV response
            if (channel == null) {
                reply(425, "Cannot establish data connection - no network channel");
                return;
            }
            InetSocketAddress localAddr = (InetSocketAddress) channel.getLocalAddress();
            String pasvResponse = dataCoordinator.generatePassiveResponse(localAddr.getAddress());
            
            // Send PASV response to client
            reply(227, pasvResponse.substring(4)); // Remove "227 " prefix
            
        } catch (NumberFormatException e) {
            String message = L10N.getString("ftp.err.invalid_pasv_arguments");
            reply(501, MessageFormat.format(message, args != null ? args : ""));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to set up passive mode", e);
            reply(425, L10N.getString("ftp.err.local_error"));
        }
    }

    /**
     * EXTENDED PORT (RFC 2428) - IPv6 compatible active mode.
     * Format: |protocol|address|port|
     * Protocol: 1 = IPv4, 2 = IPv6
     */
    protected void doEprt(String args) throws IOException {
        if (epsvAllMode) {
            reply(522, L10N.getString("ftp.err.epsv_all_active"));
            return;
        }
        
        if (args == null || args.length() < 7) {
            reply(501, L10N.getString("ftp.err.invalid_eprt_syntax"));
            return;
        }
        
        try {
            // Parse: |protocol|address|port|
            char delimiter = args.charAt(0);
            
            // Split by delimiter, keeping empty strings
            List<String> partList = new ArrayList<>();
            int start = 0;
            for (int i = 0; i <= args.length(); i++) {
                if (i == args.length() || args.charAt(i) == delimiter) {
                    partList.add(args.substring(start, i));
                    start = i + 1;
                }
            }
            String[] parts = partList.toArray(new String[0]);
            // parts[0] is empty (before first delimiter)
            // parts[1] = protocol
            // parts[2] = address
            // parts[3] = port
            // parts[4] is empty (after last delimiter)
            
            if (parts.length < 4) {
                reply(501, L10N.getString("ftp.err.invalid_eprt_syntax"));
                return;
            }
            
            int protocol = Integer.parseInt(parts[1]);
            String addressStr = parts[2];
            int port = Integer.parseInt(parts[3]);
            
            // Validate protocol (1 = IPv4, 2 = IPv6)
            if (protocol != 1 && protocol != 2) {
                reply(522, L10N.getString("ftp.err.network_protocol_not_supported"));
                return;
            }
            
            // Validate port range
            if (port < 1 || port > 65535) {
                reply(501, L10N.getString("ftp.err.invalid_eprt_port"));
                return;
            }
            
            // Parse and validate address
            InetAddress addr;
            try {
                addr = InetAddress.getByName(addressStr);
            } catch (UnknownHostException e) {
                reply(501, L10N.getString("ftp.err.invalid_eprt_address"));
                return;
            }
            
            // Verify protocol matches address family
            if ((protocol == 1 && !(addr instanceof Inet4Address)) ||
                (protocol == 2 && !(addr instanceof Inet6Address))) {
                reply(522, L10N.getString("ftp.err.network_protocol_mismatch"));
                return;
            }
            
            // Set up active mode
            dataCoordinator.setupActiveMode(addr.getHostAddress(), port);
            reply(200, L10N.getString("ftp.eprt_ok"));
            
        } catch (NumberFormatException e) {
            reply(501, L10N.getString("ftp.err.invalid_eprt_syntax"));
        }
    }

    /**
     * EXTENDED PASSIVE (RFC 2428) - IPv6 compatible passive mode.
     * Response format: 229 Entering Extended Passive Mode (|||port|)
     * The client uses the control connection's IP address.
     */
    protected void doEpsv(String args) throws IOException {
        try {
            // Handle "EPSV ALL" - locks client to extended commands only
            if (args != null && args.trim().equalsIgnoreCase("ALL")) {
                epsvAllMode = true;
                reply(200, L10N.getString("ftp.epsv_all_ok"));
                return;
            }
            
            // Handle optional protocol argument
            if (args != null && !args.trim().isEmpty()) {
                int protocol = Integer.parseInt(args.trim());
                if (protocol != 1 && protocol != 2) {
                    reply(522, L10N.getString("ftp.err.network_protocol_not_supported"));
                    return;
                }
                // We support both protocols, proceed with passive setup
            }
            
            // Set up passive mode through coordinator
            int actualPort = dataCoordinator.setupPassiveMode(0);
            
            // Send EPSV response - only port, client uses control connection IP
            String response = L10N.getString("ftp.entering_extended_passive");
            reply(229, MessageFormat.format(response, actualPort));
            
        } catch (NumberFormatException e) {
            reply(501, L10N.getString("ftp.err.invalid_epsv_arguments"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to set up extended passive mode", e);
            reply(425, L10N.getString("ftp.err.local_error"));
        }
    }

    /**
     * REPRESENTATION TYPE
     */
    protected void doType(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }
        
        String typeCode = args.trim().toUpperCase();
        switch (typeCode.charAt(0)) {
            case 'A': // ASCII
                metadata.setTransferType(FTPConnectionMetadata.FTPTransferType.ASCII);
                reply(200, L10N.getString("ftp.command_ok"));
                break;
            case 'I': // Binary (Image)
                metadata.setTransferType(FTPConnectionMetadata.FTPTransferType.BINARY);
                reply(200, L10N.getString("ftp.command_ok"));
                break;
            case 'E': // EBCDIC
                metadata.setTransferType(FTPConnectionMetadata.FTPTransferType.EBCDIC);
                reply(200, L10N.getString("ftp.command_ok"));
                break;
            case 'L': // Local
                metadata.setTransferType(FTPConnectionMetadata.FTPTransferType.LOCAL);
                reply(200, L10N.getString("ftp.command_ok"));
                break;
            default:
                reply(504, L10N.getString("ftp.err.parameter_not_implemented"));
                break;
        }
    }

    /**
     * FILE STRUCTURE
     */
    protected void doStru(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }
        
        String structureCode = args.trim().toUpperCase();
        switch (structureCode.charAt(0)) {
            case 'F': // File (default)
                reply(200, L10N.getString("ftp.command_ok"));
                break;
            case 'R': // Record
            case 'P': // Page
                reply(504, L10N.getString("ftp.err.parameter_not_implemented"));
                break;
            default:
                reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
                break;
        }
    }

    /**
     * TRANSFER MODE
     */
    protected void doMode(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }
        
        String modeCode = args.trim().toUpperCase();
        switch (modeCode.charAt(0)) {
            case 'S': // Stream (default)
                metadata.setTransferMode(FTPConnectionMetadata.FTPTransferMode.STREAM);
                reply(200, L10N.getString("ftp.command_ok"));
                break;
            case 'B': // Block
                metadata.setTransferMode(FTPConnectionMetadata.FTPTransferMode.BLOCK);
                reply(504, L10N.getString("ftp.err.parameter_not_implemented"));
                break;
            case 'C': // Compressed
                metadata.setTransferMode(FTPConnectionMetadata.FTPTransferMode.COMPRESSED);
                reply(504, L10N.getString("ftp.err.parameter_not_implemented"));
                break;
            default:
                reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
                break;
        }
    }

    /**
     * RETRIEVE
     */
    protected void doRetr(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }
        
        String filePath = args.trim();
        
        // Check authorization for read operation
        if (!checkAuthorization(FTPOperation.READ, filePath)) {
            return;
        }
        
        try {
            // Send transfer starting response
            reply(150, L10N.getString("ftp.transfer_starting"));
            
            // Create pending transfer for download
            FTPDataConnectionCoordinator.PendingTransfer transfer = 
                new FTPDataConnectionCoordinator.PendingTransfer(
                    FTPDataConnectionCoordinator.TransferType.DOWNLOAD,
                    filePath,
                    false, // not append
                    restartOffset,
                    handler,
                    metadata
                );
            
            // Start the transfer (this will establish data connection and stream data)
            dataCoordinator.startTransfer(transfer);
            
            // Reset restart offset after use
            restartOffset = 0;
            
            // Record telemetry
            recordFileTransfer("RETR", filePath);
            
            // Transfer completed successfully
            reply(226, L10N.getString("ftp.transfer_complete"));
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "RETR failed for " + filePath, e);
            recordSessionException(e);
            reply(550, MessageFormat.format(L10N.getString("ftp.err.file_not_found"), filePath));
        }
    }

    /**
     * STORE
     */
    protected void doStor(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }
        
        String filePath = args.trim();
        
        // Check authorization for write operation
        if (!checkAuthorization(FTPOperation.WRITE, filePath)) {
            return;
        }
        
        // Check quota before accepting upload
        if (!checkQuotaForUpload(filePath, -1)) {
            return;
        }
        
        try {
            // Send transfer starting response
            reply(150, L10N.getString("ftp.transfer_starting"));
            
            // Create pending transfer for upload
            FTPDataConnectionCoordinator.PendingTransfer transfer = 
                new FTPDataConnectionCoordinator.PendingTransfer(
                    FTPDataConnectionCoordinator.TransferType.UPLOAD,
                    filePath,
                    false, // not append
                    0, // no restart for uploads
                    handler,
                    metadata
                );
            
            // Start the transfer
            dataCoordinator.startTransfer(transfer);

            // Record telemetry
            recordFileTransfer("STOR", filePath);
            
            // Transfer completed successfully
            reply(226, L10N.getString("ftp.transfer_complete"));
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "STOR failed for " + filePath, e);
            recordSessionException(e);
            reply(550, L10N.getString("ftp.err.file_system_error"));
        }
    }

    /**
     * STORE UNIQUE
     */
    protected void doStou(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        
        // Check authorization for write operation
        if (!checkAuthorization(FTPOperation.WRITE, null)) {
            return;
        }
        
        // Check quota before accepting upload
        if (!checkQuotaForUpload(null, -1)) {
            return;
        }
        
        try {
            // Send transfer starting response
            reply(150, L10N.getString("ftp.transfer_starting"));
            
            // Create pending transfer for unique store (path will be generated by file system)
            FTPDataConnectionCoordinator.PendingTransfer transfer = 
                new FTPDataConnectionCoordinator.PendingTransfer(
                    FTPDataConnectionCoordinator.TransferType.UPLOAD,
                    "", // empty path signals STOU
                    false, // not append
                    0, // no restart for uploads
                    handler,
                    metadata
                );
            
            // Start the transfer
            dataCoordinator.startTransfer(transfer);
            
            // Transfer completed successfully
            reply(226, L10N.getString("ftp.transfer_complete"));
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "STOU failed", e);
            reply(550, L10N.getString("ftp.err.file_system_error"));
        }
    }

    /**
     * APPEND (with create)
     */
    protected void doAppe(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }
        
        String filePath = args.trim();
        
        // Check authorization for write operation
        if (!checkAuthorization(FTPOperation.WRITE, filePath)) {
            return;
        }
        
        // Check quota before accepting upload
        if (!checkQuotaForUpload(filePath, -1)) {
            return;
        }
        
        try {
            // Send transfer starting response
            reply(150, L10N.getString("ftp.transfer_starting"));
            
            // Create pending transfer for append
            FTPDataConnectionCoordinator.PendingTransfer transfer = 
                new FTPDataConnectionCoordinator.PendingTransfer(
                    FTPDataConnectionCoordinator.TransferType.UPLOAD,
                    filePath,
                    true, // append mode
                    0, // no restart for uploads
                    handler,
                    metadata
                );
            
            // Start the transfer
            dataCoordinator.startTransfer(transfer);

            // Record telemetry
            recordFileTransfer("APPE", filePath);
            
            // Transfer completed successfully  
            reply(226, L10N.getString("ftp.transfer_complete"));
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "APPE failed for " + filePath, e);
            recordSessionException(e);
            reply(550, L10N.getString("ftp.err.file_system_error"));
        }
    }

    /**
     * ALLOCATE
     */
    protected void doAllo(String args) throws IOException {
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
                // Most implementations ignore ALLO
                reply(200, L10N.getString("ftp.command_ok"));
            }
        } catch (NumberFormatException e) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
        }
    }

    /**
     * RESTART
     */
    protected void doRest(String args) throws IOException {
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
            // Store restart offset for next transfer
            restartOffset = marker;
            String restartMsg = L10N.getString("ftp.restart_marker");
            reply(350, MessageFormat.format(restartMsg, marker));
        } catch (NumberFormatException e) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
        }
    }

    /**
     * RENAME FROM
     */
    protected void doRnfr(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }
        
        String sourcePath = args.trim();
        
        // Check authorization for rename operation
        if (!checkAuthorization(FTPOperation.RENAME, sourcePath)) {
            return;
        }
        
        FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }
        // Check if source file exists
        FTPFileInfo info = fs.getFileInfo(sourcePath, metadata);
        if (info != null) {
            renameFrom = sourcePath;
            reply(350, L10N.getString("ftp.rename_pending"));
        } else {
            reply(550, MessageFormat.format(L10N.getString("ftp.err.file_not_found"), sourcePath));
        }
    }

    /**
     * RENAME TO
     */
    protected void doRnto(String args) throws IOException {
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
        
        // Clear rename state regardless of success/failure
        renameFrom = null;
    }

    /**
     * ABORT
     */
    protected void doAbor(String args) throws IOException {
        // Abort any active data transfer
        dataCoordinator.abortTransfer();
        reply(225, "ABOR command successful");
    }

    /**
     * DELETE
     */
    protected void doDele(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }
        
        String filePath = args.trim();
        
        // Check authorization for delete operation
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

    /**
     * REMOVE DIRECTORY
     */
    protected void doRmd(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }
        
        String dirPath = args.trim();
        
        // Check authorization for delete directory operation
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

    /**
     * MAKE DIRECTORY
     */
    protected void doMkd(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }
        
        String dirPath = args.trim();
        
        // Check authorization for create directory operation
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

    /**
     * PRINT WORKING DIRECTORY
     */
    protected void doPwd(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        
        // PWD returns current directory in quotes
        reply(257, "\"" + currentDirectory + "\" " + L10N.getString("ftp.directory_created").substring(4));
    }

    /**
     * LIST
     */
    protected void doList(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        
        String listPath = (args != null && !args.trim().isEmpty()) ? args.trim() : currentDirectory;
        
        // Check authorization for read operation
        if (!checkAuthorization(FTPOperation.READ, listPath)) {
            return;
        }
        
        try {
            // Send transfer starting response
            reply(150, L10N.getString("ftp.directory_listing"));
            
            // Create pending transfer for directory listing
            FTPDataConnectionCoordinator.PendingTransfer transfer = 
                new FTPDataConnectionCoordinator.PendingTransfer(
                    FTPDataConnectionCoordinator.TransferType.LISTING,
                    listPath,
                    false, // not append
                    0, // no restart for listings
                    handler,
                    metadata
                );
            
            // Start the transfer
            dataCoordinator.startTransfer(transfer);
            
            // Transfer completed successfully
            reply(226, L10N.getString("ftp.transfer_complete"));
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "LIST failed for " + listPath, e);
            reply(550, MessageFormat.format(L10N.getString("ftp.err.file_not_found"), listPath));
        }
    }

    /**
     * NAME LIST
     */
    protected void doNlst(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        
        String listPath = (args != null && !args.trim().isEmpty()) ? args.trim() : currentDirectory;
        
        // Check authorization for read operation
        if (!checkAuthorization(FTPOperation.READ, listPath)) {
            return;
        }
        
        try {
            // Send transfer starting response
            reply(150, L10N.getString("ftp.directory_listing"));
            
            // Create pending transfer for name listing (same as LIST for now)
            // TODO: Add NLST-specific formatting (names only vs full listing)
            FTPDataConnectionCoordinator.PendingTransfer transfer = 
                new FTPDataConnectionCoordinator.PendingTransfer(
                    FTPDataConnectionCoordinator.TransferType.LISTING,
                    listPath,
                    false, // not append
                    0, // no restart for listings
                    handler,
                    metadata
                );
            
            // Start the transfer
            dataCoordinator.startTransfer(transfer);
            
            // Transfer completed successfully
            reply(226, L10N.getString("ftp.transfer_complete"));
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "NLST failed for " + listPath, e);
            reply(550, MessageFormat.format(L10N.getString("ftp.err.file_not_found"), listPath));
        }
    }

    /**
     * SITE PARAMETERS
     */
    protected void doSite(String args) throws IOException {
        if (!authenticated) {
            reply(530, L10N.getString("ftp.err.not_logged_in"));
            return;
        }
        
        String siteCommand = (args != null) ? args.trim() : "";
        String upperCommand = siteCommand.toUpperCase();
        
        // Handle SITE QUOTA directly
        if (upperCommand.equals("QUOTA") || upperCommand.startsWith("QUOTA ")) {
            handleSiteQuota(siteCommand);
            return;
        }
        
        // Handle SITE SETQUOTA directly (admin only)
        if (upperCommand.startsWith("SETQUOTA ")) {
            handleSiteSetQuota(siteCommand);
            return;
        }
        
        if (handler != null) {
            metadata.clearSiteCommandResponse();
            FTPFileOperationResult result = handler.handleSiteCommand(siteCommand, metadata);
            
            // Check for multi-line response from handler
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

    /**
     * Handles SITE QUOTA command.
     * Displays current user's quota status.
     */
    private void handleSiteQuota(String args) throws IOException {
        QuotaManager quotaManager = getQuotaManager();
        if (quotaManager == null) {
            reply(502, L10N.getString("ftp.err.quota_not_configured"));
            addSessionEvent("QUOTA_CHECK_FAILED");
            addSessionAttribute("ftp.quota.error", "not_configured");
            return;
        }
        
        // Parse optional username (admin can check other users)
        String targetUser = user;
        String argPart = args.length() > 5 ? args.substring(5).trim() : "";
        if (!argPart.isEmpty() && handler != null) {
            // Only admins can check other users' quotas
            if (handler.isAuthorized(FTPOperation.ADMIN, null, metadata)) {
                targetUser = argPart;
            }
        }
        
        Quota quota = quotaManager.getQuota(targetUser);
        
        // Build multi-line response
        StringBuilder response = new StringBuilder();
        response.append(MessageFormat.format(L10N.getString("ftp.quota.status_for"), targetUser)).append("\r\n");
        
        // Source
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
        
        // Storage
        if (quota.isStorageUnlimited()) {
            response.append(L10N.getString("ftp.quota.storage_unlimited")).append("\r\n");
        } else {
            String used = QuotaPolicy.formatSize(quota.getStorageUsed());
            String limit = QuotaPolicy.formatSize(quota.getStorageLimit());
            int percent = quota.getStoragePercentUsed();
            response.append(MessageFormat.format(L10N.getString("ftp.quota.storage_status"), 
                used, limit, String.valueOf(percent))).append("\r\n");
        }
        
        // Telemetry
        addSessionEvent("QUOTA_CHECK");
        addSessionAttribute("ftp.quota.user", targetUser);
        addSessionAttribute("ftp.quota.used", String.valueOf(quota.getStorageUsed()));
        addSessionAttribute("ftp.quota.limit", String.valueOf(quota.getStorageLimit()));
        
        replyMultiLine(211, response.toString());
    }

    /**
     * Handles SITE SETQUOTA command.
     * Syntax: SITE SETQUOTA username storageLimit
     */
    private void handleSiteSetQuota(String args) throws IOException {
        QuotaManager quotaManager = getQuotaManager();
        if (quotaManager == null) {
            reply(502, L10N.getString("ftp.err.quota_not_configured"));
            addSessionEvent("QUOTA_SET_FAILED");
            addSessionAttribute("ftp.quota.error", "not_configured");
            return;
        }
        
        // Admin only
        if (handler == null || !handler.isAuthorized(FTPOperation.ADMIN, null, metadata)) {
            reply(550, L10N.getString("ftp.err.permission_denied"));
            addSessionEvent("QUOTA_SET_DENIED");
            addSessionAttribute("ftp.quota.error", "permission_denied");
            return;
        }
        
        // Parse: SETQUOTA username storageLimit
        String argPart = args.substring(8).trim(); // Remove "SETQUOTA"
        String[] parts = argPart.split("\\s+");
        if (parts.length < 2) {
            reply(501, L10N.getString("ftp.err.syntax_error_parameters"));
            return;
        }
        
        String targetUser = parts[0];
        String storageStr = parts[1];
        
        try {
            long storageLimit = QuotaPolicy.parseSize(storageStr);
            quotaManager.setUserQuota(targetUser, storageLimit, -1);
            
            // Telemetry
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

    /**
     * Sends a multi-line response.
     */
    private void replyMultiLine(int code, String message) throws IOException {
        String[] lines = message.split("\r\n|\n");
        StringBuilder response = new StringBuilder();
        
        for (int i = 0; i < lines.length; i++) {
            if (i == lines.length - 1) {
                // Last line uses space after code
                response.append(code).append(" ").append(lines[i]).append("\r\n");
            } else {
                // Intermediate lines use hyphen after code
                response.append(code).append("-").append(lines[i]).append("\r\n");
            }
        }
        
        send(US_ASCII.encode(response.toString()));
    }

    /**
     * Checks quota before upload.
     * 
     * @param filePath the file path (may be null for STOU)
     * @param expectedSize expected size in bytes (-1 if unknown)
     * @return true if quota allows upload, false if denied
     */
    private boolean checkQuotaForUpload(String filePath, long expectedSize) throws IOException {
        QuotaManager quotaManager = getQuotaManager();
        if (quotaManager == null) {
            return true; // No quota enforcement
        }
        
        // If we don't know the size, we can only check if quota is already exceeded
        long bytesToCheck = expectedSize > 0 ? expectedSize : 0;
        
        if (!quotaManager.canStore(user, bytesToCheck)) {
            Quota quota = quotaManager.getQuota(user);
            
            // Telemetry
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

    /**
     * Gets the quota manager from the handler, if available.
     */
    private QuotaManager getQuotaManager() {
        if (handler != null) {
            return handler.getQuotaManager();
        }
        return null;
    }

    /**
     * SYSTEM
     */
    protected void doSyst(String args) throws IOException {
        reply(215, L10N.getString("ftp.system_type"));
    }

    /**
     * STATUS
     */
    protected void doStat(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            // System status
            StringBuilder status = new StringBuilder();
            status.append("Server: ").append(metadata.getConnectorDescription()).append("\r\n");
            status.append("User: ").append(authenticated ? user : "Not logged in").append("\r\n");
            status.append("Directory: ").append(currentDirectory).append("\r\n");
            status.append("Secure: ").append(metadata.isSecureConnection() ? "Yes" : "No").append("\r\n");
            reply(211, status.toString());
        } else {
            // File status - delegate to file system
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

    /**
     * HELP
     */
    protected void doHelp(String args) throws IOException {
        reply(214, L10N.getString("ftp.help_message"));
    }

    /**
     * NOOP
     */
    protected void doNoop(String args) throws IOException {
        reply(200, L10N.getString("ftp.no_operation"));
    }

    // ========== RFC 4217 - FTP Security Extensions ==========

    /**
     * AUTH command (RFC 4217) - Authentication/Security Mechanism.
     * Initiates TLS upgrade of the control connection.
     * 
     * @param args the security mechanism (TLS or SSL)
     */
    protected void doAuth(String args) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            reply(504, L10N.getString("ftp.err.auth_mechanism_required"));
            return;
        }
        
        String mechanism = args.trim().toUpperCase();
        
        // Only support TLS and SSL (both mean the same thing now)
        if (!mechanism.equals("TLS") && !mechanism.equals("SSL")) {
            reply(504, MessageFormat.format(L10N.getString("ftp.err.auth_unknown_mechanism"), mechanism));
            return;
        }
        
        // Check if already secure
        if (secure) {
            reply(503, L10N.getString("ftp.err.already_secure"));
            return;
        }
        
        // Check if TLS is available
        FTPServer ftpServer = (FTPServer) getServer();
        if (ftpServer == null || !ftpServer.isSTARTTLSAvailable()) {
            reply(534, L10N.getString("ftp.err.auth_tls_not_available"));
            return;
        }
        
        try {
            // Send success response - client expects TLS handshake next
            reply(234, MessageFormat.format(L10N.getString("ftp.auth_tls_ok"), mechanism));
            
            // Upgrade connection to TLS
            initializeSSLState();
            
            // Reset security-related state
            pbszSet = false;
            dataProtection = false;
            authUsed = true;

            // Record telemetry
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

    /**
     * PBSZ command (RFC 4217) - Protection Buffer Size.
     * Must be issued after AUTH and before PROT.
     * For TLS, the only valid value is 0.
     * 
     * @param args the buffer size (must be 0 for TLS)
     */
    protected void doPbsz(String args) throws IOException {
        // PBSZ only valid after AUTH TLS
        if (!secure) {
            reply(503, L10N.getString("ftp.err.pbsz_requires_auth"));
            return;
        }
        
        if (args == null || args.trim().isEmpty()) {
            reply(501, L10N.getString("ftp.err.pbsz_size_required"));
            return;
        }
        
        try {
            int bufferSize = Integer.parseInt(args.trim());
            // For TLS, only 0 is valid (TLS handles buffering internally)
            if (bufferSize != 0) {
                // RFC 4217: server should accept any value and respond with 0
                reply(200, "PBSZ=0");
            } else {
                reply(200, "PBSZ=0");
            }
            pbszSet = true;
            
        } catch (NumberFormatException e) {
            reply(501, L10N.getString("ftp.err.pbsz_invalid_size"));
        }
    }

    /**
     * PROT command (RFC 4217) - Data Channel Protection Level.
     * Must be issued after PBSZ.
     * 
     * @param args the protection level: C (Clear), S (Safe), E (Confidential), P (Private)
     */
    protected void doProt(String args) throws IOException {
        // PROT only valid after PBSZ
        if (!secure) {
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
            case "C": // Clear - no protection
                dataProtection = false;
                dataCoordinator.setDataProtection(false);
                reply(200, L10N.getString("ftp.prot_clear"));
                break;
                
            case "P": // Private - TLS encrypted
                dataProtection = true;
                dataCoordinator.setDataProtection(true);
                reply(200, L10N.getString("ftp.prot_private"));
                break;
                
            case "S": // Safe - integrity only (not supported by TLS)
            case "E": // Confidential - confidentiality only (not supported by TLS)
                reply(536, MessageFormat.format(L10N.getString("ftp.err.prot_level_not_supported"), level));
                break;
                
            default:
                reply(504, MessageFormat.format(L10N.getString("ftp.err.prot_unknown_level"), level));
        }
    }

    /**
     * CCC command (RFC 4217) - Clear Command Channel.
     * Downgrades control connection to cleartext while keeping data protected.
     * This is rarely used and potentially dangerous, so we don't support it.
     * 
     * @param args should be null
     */
    protected void doCcc(String args) throws IOException {
        // CCC is controversial and rarely implemented
        // It allows control commands to be sent in clear while data is encrypted
        reply(533, L10N.getString("ftp.err.ccc_not_supported"));
    }

    /**
     * FEAT command (RFC 2389) - Feature List.
     * Returns the list of extensions supported by this server.
     * 
     * @param args should be null
     */
    protected void doFeat(String args) throws IOException {
        // FEAT response format: 211-Extensions supported:
        //                       <SP>FEATURE<CRLF>
        //                       211 End
        reply(211, "-Extensions supported:");
        
        // IPv6 support (RFC 2428)
        sendLine(" EPRT");
        sendLine(" EPSV");
        
        // Security extensions (RFC 4217)
        FTPServer ftpServer = (FTPServer) getServer();
        if (ftpServer != null && ftpServer.isSTARTTLSAvailable() && !secure) {
            sendLine(" AUTH TLS");
            sendLine(" AUTH SSL");
        }
        if (secure) {
            sendLine(" PBSZ");
            sendLine(" PROT");
        }
        
        // UTF-8 support (RFC 2640)
        sendLine(" UTF8");
        
        // Size before transfer
        sendLine(" SIZE");
        
        // Modify time
        sendLine(" MDTM");
        
        // Restart support
        sendLine(" REST STREAM");
        
        // TVFS - trivial virtual file store
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

    /**
     * Returns the FTPServer instance for this connection.
     */
    FTPServer getServer() {
        return server;
    }

    // ========== TELEMETRY METHODS ==========

    /**
     * Initializes the connection trace if telemetry is enabled.
     */
    private void initConnectionTrace() {
        if (isTelemetryEnabled()) {
            TelemetryConfig config = getTelemetryConfig();
            String traceName = L10N.getString("telemetry.ftp_connection");
            connectionTrace = config.createTrace(traceName);
            Span rootSpan = connectionTrace.getRootSpan();
            // Add connection-level attributes
            rootSpan.addAttribute("net.transport", "ip_tcp");
            rootSpan.addAttribute("net.peer.ip", getRemoteSocketAddress().toString());
            rootSpan.addAttribute("rpc.system", "ftp");
        }
    }

    /**
     * Starts a new session span within the connection trace.
     */
    private void startSessionSpan() {
        if (connectionTrace != null) {
            String spanName = L10N.getString("telemetry.ftp_session");
            sessionSpan = connectionTrace.startSpan(spanName, SpanKind.SERVER);
            sessionSpan.addEvent("SESSION_START");
        }
    }

    /**
     * Starts the authenticated span when authentication succeeds.
     */
    private void startAuthenticatedSpan() {
        if (sessionSpan != null) {
            String spanName = L10N.getString("telemetry.ftp_authenticated");
            authenticatedSpan = sessionSpan.startChild(spanName, SpanKind.SERVER);
            authenticatedSpan.addAttribute("enduser.id", user);
            authenticatedSpan.addEvent("AUTHENTICATED");
        }
    }

    /**
     * Ends the session span normally.
     */
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

    /**
     * Ends the session span with an error.
     */
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

    /**
     * Ends the authenticated span.
     */
    private void endAuthenticatedSpan() {
        if (authenticatedSpan != null) {
            authenticatedSpan.setStatusOk();
            authenticatedSpan.end();
            authenticatedSpan = null;
        }
    }

    /**
     * Adds an attribute to the session span.
     */
    private void addSessionAttribute(String key, String value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    /**
     * Adds an event to the session span.
     */
    private void addSessionEvent(String event) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addEvent(event);
        }
    }

    /**
     * Adds an event to the authenticated span.
     */
    private void addAuthenticatedEvent(String event) {
        if (authenticatedSpan != null && !authenticatedSpan.isEnded()) {
            authenticatedSpan.addEvent(event);
        }
    }

    /**
     * Records an exception in the session span with automatic category detection.
     */
    private void recordSessionException(Exception e) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.recordExceptionWithCategory(e);
        }
    }

    /**
     * Records an exception with an explicit error category in the session span.
     */
    private void recordSessionException(Exception e, ErrorCategory category) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.recordException(e, category);
        }
    }

    /**
     * Records an error with an explicit category in the session span.
     */
    private void recordSessionError(ErrorCategory category, String message) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.recordError(category, 0, message);
        }
    }

    /**
     * Records an FTP error reply in telemetry.
     * This correlates the FTP reply code with a standardized error category.
     *
     * @param replyCode the FTP reply code (4xx or 5xx)
     * @param message the error message
     */
    private void recordFtpError(int replyCode, String message) {
        if (sessionSpan == null || sessionSpan.isEnded()) {
            return;
        }
        ErrorCategory category = ErrorCategory.fromFtpReplyCode(replyCode);
        if (category != null) {
            sessionSpan.recordError(category, replyCode, message);
        }
    }

    /**
     * Records successful authentication.
     */
    private void recordAuthenticationSuccess(String mechanism) {
        addSessionAttribute("ftp.auth.mechanism", mechanism);
        addSessionAttribute("enduser.id", user);
        addSessionEvent("AUTH_SUCCESS");
        startAuthenticatedSpan();
    }

    /**
     * Records failed authentication.
     */
    private void recordAuthenticationFailure(String mechanism, String attemptedUser) {
        addSessionAttribute("ftp.auth.mechanism", mechanism);
        if (attemptedUser != null) {
            addSessionAttribute("ftp.auth.attempted_user", attemptedUser);
        }
        addSessionEvent("AUTH_FAILURE");
    }

    /**
     * Records STARTTLS upgrade success.
     */
    private void recordStartTLSSuccess() {
        addSessionEvent("STARTTLS_SUCCESS");
        addSessionAttribute("net.protocol.name", "ftps");
    }

    /**
     * Records STARTTLS upgrade failure.
     */
    private void recordStartTLSFailure(Exception e) {
        addSessionEvent("STARTTLS_FAILURE");
        recordSessionException(e);
    }

    /**
     * Records a file transfer operation.
     */
    private void recordFileTransfer(String operation, String path) {
        addAuthenticatedEvent(operation);
        if (authenticatedSpan != null && !authenticatedSpan.isEnded()) {
            authenticatedSpan.addAttribute("ftp.file.path", path);
        }
    }

    /**
     * Records a file operation (non-transfer).
     */
    private void recordFileOperation(String operation, String path) {
        addAuthenticatedEvent(operation);
        if (authenticatedSpan != null && !authenticatedSpan.isEnded()) {
            authenticatedSpan.addAttribute("ftp.file.path", path);
        }
    }

}
