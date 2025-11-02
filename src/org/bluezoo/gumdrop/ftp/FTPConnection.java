/*
 * FTPConnection.java
 * Copyright (C) 2006 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.ftp;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.SendCallback;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.util.LineInput;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

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

    @FunctionalInterface
    interface IOConsumer<T> {
        void accept(T t) throws IOException;
    }

    // Using base class protected SocketChannel channel field
    private final Map<String,IOConsumer<String>> commands;
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

    private ByteBuffer in; // input buffer
    private LineReader lineReader;

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

    protected FTPConnection(SocketChannel channel, SSLEngine engine, boolean secure, FTPConnectionHandler handler) {
        super(engine, secure);
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
        
        commands = new HashMap<>();
        commands.put("USER", this::doUser);
        commands.put("PASS", this::doPass);
        commands.put("ACCT", this::doAcct);
        commands.put("CWD", this::doCwd);
        commands.put("CDUP", this::doCdup);
        commands.put("SMNT", this::doSmnt);
        commands.put("REIN", this::doRein);
        commands.put("QUIT", this::doQuit);
        commands.put("PORT", this::doPort);
        commands.put("PASV", this::doPasv);
        commands.put("TYPE", this::doType);
        commands.put("STRU", this::doStru);
        commands.put("MODE", this::doMode);
        commands.put("RETR", this::doRetr);
        commands.put("STOR", this::doStor);
        commands.put("STOU", this::doStou);
        commands.put("APPE", this::doAppe);
        commands.put("ALLO", this::doAllo);
        commands.put("REST", this::doRest);
        commands.put("RNFR", this::doRnfr);
        commands.put("RNTO", this::doRnto);
        commands.put("ABOR", this::doAbor);
        commands.put("DELE", this::doDele);
        commands.put("RMD", this::doRmd);
        commands.put("MKD", this::doMkd);
        commands.put("PWD", this::doPwd);
        commands.put("LIST", this::doList);
        commands.put("NLST", this::doNlst);
        commands.put("SITE", this::doSite);
        commands.put("SYST", this::doSyst);
        commands.put("STAT", this::doStat);
        commands.put("HELP", this::doHelp);
        commands.put("NOOP", this::doNoop);
        
        // Initialize input buffer for line reading
        in = ByteBuffer.allocate(8192); // 8KB buffer for command input
        lineReader = this.new LineReader();
    }
    
    @Override
    protected void setSendCallback(SendCallback callback) {
        super.setSendCallback(callback);
    }
    
    @Override
    protected void init() throws IOException {
        super.init();
        
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
    protected synchronized void received(ByteBuffer buf) {
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

    protected void disconnected() throws IOException {
        // Clean up data connection coordinator
        if (dataCoordinator != null) {
            dataCoordinator.cleanup();
        }
        
        // Notify handler
        if (handler != null) {
            handler.disconnected(metadata);
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
     * Maps FTPAuthenticationResult to FTP response codes and sends appropriate reply
     */
    private void handleAuthenticationResult(FTPAuthenticationResult result) throws IOException {
        switch (result) {
            case SUCCESS:
                authenticated = true;
                metadata.setAuthenticated(true);
                metadata.setAuthenticatedUser(user);
                reply(230, L10N.getString("ftp.login_successful"));
                break;
            case NEED_PASSWORD:
                reply(331, L10N.getString("ftp.user_ok_need_password"));
                break;
            case NEED_ACCOUNT:
                reply(332, L10N.getString("ftp.user_ok_need_account"));
                break;
            case INVALID_USER:
                reply(530, L10N.getString("ftp.err.invalid_username"));
                break;
            case INVALID_PASSWORD:
                reply(530, L10N.getString("ftp.err.invalid_password"));
                break;
            case INVALID_ACCOUNT:
                reply(530, L10N.getString("ftp.err.invalid_password")); // Generic login incorrect
                break;
            case ACCOUNT_DISABLED:
                reply(530, L10N.getString("ftp.err.account_disabled"));
                break;
            case TOO_MANY_ATTEMPTS:
                reply(421, L10N.getString("ftp.err.too_many_attempts"));
                break;
            case ANONYMOUS_NOT_ALLOWED:
                reply(530, L10N.getString("ftp.err.anonymous_not_allowed"));
                break;
            case USER_LIMIT_EXCEEDED:
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
        IOConsumer<String> function = commands.get(command);
        if (function == null) {
            String message = L10N.getString("ftp.err.command_unrecognized");
            reply(500, MessageFormat.format(message, command));
        } else {
            function.accept(args);
        }
    }

    protected void reply(int code, String description) throws IOException {
        String message = String.format("%d %s\r\n", code, description);
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
        
        FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }
        
        String targetPath = args.trim();
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
        reply(221, L10N.getString("ftp.goodbye"));
        
        // Clean up state
        user = null;
        password = null;
        account = null;
        authenticated = false;
        
        // Close the connection
        close();
    }

    /**
     * DATA PORT
     */
    protected void doPort(String args) throws IOException {
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
     * PASSIVE
     */
    protected void doPasv(String args) throws IOException {
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
            
            // Transfer completed successfully
            reply(226, L10N.getString("ftp.transfer_complete"));
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "RETR failed for " + filePath, e);
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
            
            // Transfer completed successfully
            reply(226, L10N.getString("ftp.transfer_complete"));
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "STOR failed for " + filePath, e);
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
            
            // Transfer completed successfully  
            reply(226, L10N.getString("ftp.transfer_complete"));
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "APPE failed for " + filePath, e);
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
        
        FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }
        
        String sourcePath = args.trim();
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
        
        FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }
        
        String filePath = args.trim();
        FTPFileOperationResult result = fs.deleteFile(filePath, metadata);
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
        
        FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }
        
        String dirPath = args.trim();
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
        
        FTPFileSystem fs = getFileSystem();
        if (fs == null) {
            reply(550, L10N.getString("ftp.err.file_system_error"));
            return;
        }
        
        String dirPath = args.trim();
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
        
        if (handler != null) {
            String siteCommand = (args != null) ? args : "";
            FTPFileOperationResult result = handler.handleSiteCommand(siteCommand, metadata);
            handleFileOperationResult(result, siteCommand);
        } else {
            reply(502, L10N.getString("ftp.err.command_not_implemented"));
        }
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

}
