/*
 * FTPDataConnectionCoordinator.java
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

package org.bluezoo.gumdrop.ftp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinates data connections between FTP control connections and data transfers.
 * 
 * <p>This class manages both passive and active mode data connections:
 * <ul>
 * <li><strong>Passive Mode</strong>: Server creates a listening port, client connects to it</li>
 * <li><strong>Active Mode</strong>: Server connects to client's specified port</li>
 * </ul>
 *
 * <p>The coordinator handles the complex synchronization between:
 * <ul>
 * <li>Control connection command processing</li>
 * <li>Data connection establishment</li>
 * <li>File system stream coordination</li>
 * <li>Transfer progress and completion</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPDataConnectionCoordinator {
    
    private static final Logger LOGGER = Logger.getLogger(FTPDataConnectionCoordinator.class.getName());
    
    /** Buffer size for file transfers (32KB for optimal performance) */
    private static final int TRANSFER_BUFFER_SIZE = 32 * 1024;
    
    /** Progress notification interval (every 64KB transferred) */
    private static final long PROGRESS_NOTIFY_INTERVAL = 64 * 1024;
    
    /**
     * Data connection modes
     */
    public enum DataConnectionMode {
        /** No data connection established */
        NONE,
        /** Passive mode - server listening for client connection */
        PASSIVE,
        /** Active mode - server will connect to client */
        ACTIVE
    }
    
    /**
     * Data transfer types
     */
    public enum TransferType {
        UPLOAD,   // STOR, APPE, STOU
        DOWNLOAD, // RETR
        LISTING   // LIST, NLST
    }
    
    /**
     * Represents a pending data transfer operation
     */
    public static class PendingTransfer {
        private final TransferType type;
        private final String path;
        private final boolean append; // For STOR vs APPE
        private final long restartOffset; // For REST command
        private final FTPConnectionHandler handler;
        private final FTPConnectionMetadata metadata;
        
        public PendingTransfer(TransferType type, String path, boolean append, 
                             long restartOffset, FTPConnectionHandler handler, 
                             FTPConnectionMetadata metadata) {
            this.type = type;
            this.path = path;
            this.append = append;
            this.restartOffset = restartOffset;
            this.handler = handler;
            this.metadata = metadata;
        }
        
        public TransferType getType() { return type; }
        public String getPath() { return path; }
        public boolean isAppend() { return append; }
        public long getRestartOffset() { return restartOffset; }
        public FTPConnectionHandler getHandler() { return handler; }
        public FTPConnectionMetadata getMetadata() { return metadata; }
    }
    
    private final FTPConnection controlConnection;
    private DataConnectionMode mode = DataConnectionMode.NONE;
    
    // Passive mode state
    private FTPDataServer passiveConnector;
    private int passivePort = -1;
    private final BlockingQueue<FTPDataConnection> incomingDataConnections;
    
    // Active mode state  
    private String activeHost;
    private int activePort;
    
    // Transfer state
    private PendingTransfer pendingTransfer;
    private FTPDataConnection activeDataConnection;
    private long transferStartTime;
    private long totalBytesTransferred;
    
    // TLS protection state (RFC 4217)
    private boolean dataProtection = false;
    
    public FTPDataConnectionCoordinator(FTPConnection controlConnection) {
        this.controlConnection = controlConnection;
        this.incomingDataConnections = new LinkedBlockingQueue<>();
    }
    
    /**
     * Sets whether data connections should be TLS-protected (RFC 4217 PROT P).
     * 
     * @param protect true to enable TLS protection for data connections
     */
    public void setDataProtection(boolean protect) {
        this.dataProtection = protect;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Data protection " + (protect ? "enabled" : "disabled"));
        }
    }
    
    /**
     * Returns whether data connections are TLS-protected.
     * 
     * @return true if PROT P is active
     */
    public boolean isDataProtectionEnabled() {
        return dataProtection;
    }
    
    /**
     * Configures passive mode data connection.
     * Creates a server socket that the client can connect to.
     *
     * @param port the port to listen on (0 for system-assigned)
     * @return the actual port being used
     * @throws IOException if passive mode setup fails
     */
    public synchronized int setupPassiveMode(int port) throws IOException {
        cleanup(); // Clean up any existing setup
        
        passiveConnector = new FTPDataServer(controlConnection, port, this);
        
        // Bind the socket synchronously so we know the port immediately
        java.nio.channels.ServerSocketChannel ssc = java.nio.channels.ServerSocketChannel.open();
        ssc.configureBlocking(false);
        
        // Bind to the requested port (0 for system-assigned)
        InetSocketAddress bindAddress = new InetSocketAddress(port);
        ssc.bind(bindAddress);
        
        // Get the actual bound port
        passivePort = ((InetSocketAddress) ssc.getLocalAddress()).getPort();
        passiveConnector.notifyBound(ssc);
        
        // Register the already-bound channel with AcceptSelectorLoop
        org.bluezoo.gumdrop.Gumdrop.getInstance().getAcceptLoop().registerChannel(passiveConnector, ssc);
        
        mode = DataConnectionMode.PASSIVE;
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Passive mode enabled on port " + passivePort);
        }
        
        return passivePort;
    }
    
    /**
     * Configures active mode data connection.
     * Stores client connection info for later use.
     *
     * @param host client's host address
     * @param port client's port number
     */
    public synchronized void setupActiveMode(String host, int port) {
        cleanup(); // Clean up any existing setup
        
        this.activeHost = host;
        this.activePort = port;
        this.mode = DataConnectionMode.ACTIVE;
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Active mode configured to " + host + ":" + port);
        }
    }
    
    /**
     * Called by FTPDataServer when a client connects in passive mode.
     *
     * @param dataConnection the established data connection
     */
    public void acceptDataConnection(FTPDataConnection dataConnection) {
        try {
            incomingDataConnections.offer(dataConnection);
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Data connection accepted in passive mode");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error accepting data connection", e);
            try {
                dataConnection.close();
            } catch (Exception closeEx) {
                // Ignore close errors
            }
        }
    }
    
    /**
     * Starts a data transfer operation.
     * This handles both passive and active mode coordination.
     *
     * @param transfer the transfer details
     * @throws IOException if transfer setup fails
     */
    public synchronized void startTransfer(PendingTransfer transfer) throws IOException {
        if (mode == DataConnectionMode.NONE) {
            throw new IOException("No data connection mode configured (need PORT or PASV first)");
        }
        
        this.pendingTransfer = transfer;
        this.transferStartTime = System.currentTimeMillis();
        this.totalBytesTransferred = 0;
        
        // Establish data connection based on mode
        switch (mode) {
            case PASSIVE:
                waitForPassiveConnection();
                break;
            case ACTIVE:
                establishActiveConnection();
                break;
            default:
                throw new IOException("Invalid data connection mode: " + mode);
        }
        
        // Start the actual transfer
        performTransfer();
    }
    
    /**
     * Waits for client to connect in passive mode.
     */
    private void waitForPassiveConnection() throws IOException {
        try {
            // Wait for client connection (with timeout)
            activeDataConnection = incomingDataConnections.poll(30, TimeUnit.SECONDS);
            if (activeDataConnection == null) {
                throw new IOException("Timeout waiting for client data connection");
            }
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Client connected to passive data port");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for data connection", e);
        }
    }
    
    /**
     * Establishes connection to client in active mode.
     */
    private void establishActiveConnection() throws IOException {
        try {
            SocketChannel channel = SocketChannel.open();
            channel.connect(new InetSocketAddress(activeHost, activePort));
            
            // Create data connection wrapper, with TLS if PROT P is active
            javax.net.ssl.SSLEngine engine = null;
            boolean secure = false;
            if (dataProtection) {
                FTPServer server = controlConnection.getServer();
                if (server != null) {
                    engine = server.createDataSSLEngine();
                    secure = (engine != null);
                }
            }
            
            activeDataConnection = new FTPDataConnection(channel, engine, secure, this);
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Connected to client data port " + activeHost + ":" + activePort + 
                           (secure ? " (TLS)" : ""));
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to connect to client data port", e);
            throw e;
        }
    }
    
    /**
     * Performs the actual data transfer based on transfer type.
     */
    private void performTransfer() throws IOException {
        if (pendingTransfer == null || activeDataConnection == null) {
            throw new IOException("Transfer not properly initialized");
        }
        
        try {
            // Notify handler that transfer is starting
            if (pendingTransfer.getHandler() != null) {
                boolean isUpload = (pendingTransfer.getType() == TransferType.UPLOAD);
                pendingTransfer.getHandler().transferStarting(
                    pendingTransfer.getPath(), 
                    isUpload, 
                    -1, // size unknown initially
                    pendingTransfer.getMetadata()
                );
            }
            
            // Perform transfer based on type
            switch (pendingTransfer.getType()) {
                case DOWNLOAD:
                    performDownload();
                    break;
                case UPLOAD:
                    performUpload();
                    break;
                case LISTING:
                    performListing();
                    break;
                default:
                    throw new IOException("Unsupported transfer type: " + pendingTransfer.getType());
            }
            
            // Notify handler of completion
            if (pendingTransfer.getHandler() != null) {
                boolean isUpload = (pendingTransfer.getType() == TransferType.UPLOAD);
                pendingTransfer.getHandler().transferCompleted(
                    pendingTransfer.getPath(), 
                    isUpload, 
                    totalBytesTransferred, 
                    true, // success
                    pendingTransfer.getMetadata()
                );
            }
            
        } catch (IOException e) {
            // Notify handler of failure
            if (pendingTransfer.getHandler() != null) {
                boolean isUpload = (pendingTransfer.getType() == TransferType.UPLOAD);
                pendingTransfer.getHandler().transferCompleted(
                    pendingTransfer.getPath(), 
                    isUpload, 
                    totalBytesTransferred, 
                    false, // failure
                    pendingTransfer.getMetadata()
                );
            }
            throw e;
        } finally {
            // Clean up data connection
            cleanup();
        }
    }
    
    /**
     * Handles file download (RETR command) using high-performance NIO channels.
     */
    private void performDownload() throws IOException {
        FTPFileSystem fs = pendingTransfer.getHandler().getFileSystem(pendingTransfer.getMetadata());
        if (fs == null) {
            throw new IOException("No file system available for download");
        }
        
        // Open file for reading using NIO channel
        ReadableByteChannel fileChannel = fs.openForReading(
            pendingTransfer.getPath(), 
            pendingTransfer.getRestartOffset(), 
            pendingTransfer.getMetadata()
        );
        
        if (fileChannel == null) {
            throw new IOException("Failed to open file for reading: " + pendingTransfer.getPath());
        }
        
        try {
            // Get data connection channel
            WritableByteChannel dataChannel = activeDataConnection.getChannel();
            
            // Stream data from file to network using channels
            streamChannelData(fileChannel, dataChannel, pendingTransfer.getMetadata());
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Channel download completed: " + totalBytesTransferred + " bytes");
            }
            
        } finally {
            try {
                fileChannel.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing file channel", e);
            }
        }
    }
    
    /**
     * Handles file upload (STOR/APPE/STOU commands) using high-performance NIO channels.
     */
    private void performUpload() throws IOException {
        FTPFileSystem fs = pendingTransfer.getHandler().getFileSystem(pendingTransfer.getMetadata());
        if (fs == null) {
            throw new IOException("No file system available for upload");
        }
        
        String targetPath = pendingTransfer.getPath();
        
        // Handle STOU (Store Unique) - generate unique filename
        if (pendingTransfer.getType() == TransferType.UPLOAD && targetPath.isEmpty()) {
            FTPFileSystem.UniqueNameResult uniqueResult = fs.generateUniqueName(
                "/", null, pendingTransfer.getMetadata());
            if (uniqueResult.getResult() != FTPFileOperationResult.SUCCESS) {
                throw new IOException("Failed to generate unique filename");
            }
            targetPath = uniqueResult.getUniquePath();
        }
        
        // Open file for writing using NIO channel
        WritableByteChannel fileChannel = fs.openForWriting(
            targetPath, 
            pendingTransfer.isAppend(), 
            pendingTransfer.getMetadata()
        );
        
        if (fileChannel == null) {
            throw new IOException("Failed to open file for writing: " + targetPath);
        }
        
        try {
            // Get data connection channel
            ReadableByteChannel dataChannel = activeDataConnection.getChannel();
            
            // Stream data from network to file using channels
            streamChannelData(dataChannel, fileChannel, pendingTransfer.getMetadata());
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Channel upload completed: " + totalBytesTransferred + " bytes");
            }
            
        } finally {
            try {
                fileChannel.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing file channel", e);
            }
        }
    }
    
    /**
     * Handles directory listing (LIST/NLST commands).
     */
    private void performListing() throws IOException {
        FTPFileSystem fs = pendingTransfer.getHandler().getFileSystem(pendingTransfer.getMetadata());
        if (fs == null) {
            throw new IOException("No file system available for listing");
        }
        
        // Get directory listing
        List<FTPFileInfo> files = fs.listDirectory(
            pendingTransfer.getPath(), 
            pendingTransfer.getMetadata()
        );
        
        if (files == null) {
            throw new IOException("Failed to list directory: " + pendingTransfer.getPath());
        }
        
        try {
            // Get data connection channel
            WritableByteChannel dataChannel = activeDataConnection.getChannel();
            
            // Format and send listing data
            StringBuilder listing = new StringBuilder();
            for (FTPFileInfo file : files) {
                // Use Unix-style format for LIST command
                // TODO: Add NLST support (names only) based on command type
                listing.append(file.formatAsListingLine()).append("\r\n");
            }
            
            // Convert to bytes and send via channel
            byte[] listingBytes = listing.toString().getBytes(StandardCharsets.UTF_8);
            ByteBuffer listingBuffer = ByteBuffer.wrap(listingBytes);
            
            while (listingBuffer.hasRemaining()) {
                int written = dataChannel.write(listingBuffer);
                if (written == 0) {
                    Thread.yield(); // Handle non-blocking channels
                }
            }
            
            // Update transfer statistics
            totalBytesTransferred = listingBytes.length;
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error sending directory listing", e);
            throw e;
        }
    }
    
    /**
     * Aborts any active transfer.
     */
    public synchronized void abortTransfer() {
        if (activeDataConnection != null) {
            try {
                activeDataConnection.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error closing data connection during abort", e);
            }
        }
        cleanup();
    }
    
    /**
     * Generates PASV response string in the format "227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)".
     *
     * @param serverAddress the server's IP address
     * @return formatted PASV response
     */
    public String generatePassiveResponse(InetAddress serverAddress) {
        if (mode != DataConnectionMode.PASSIVE || passivePort == -1) {
            throw new IllegalStateException("Not in passive mode or port not established");
        }
        
        byte[] addressBytes = serverAddress.getAddress();
        int p1 = (passivePort >> 8) & 0xFF;
        int p2 = passivePort & 0xFF;
        
        return String.format("227 Entering Passive Mode (%d,%d,%d,%d,%d,%d)",
            addressBytes[0] & 0xFF,
            addressBytes[1] & 0xFF,
            addressBytes[2] & 0xFF,  
            addressBytes[3] & 0xFF,
            p1, p2);
    }
    
    /**
     * Cleans up any active data connections and resets state.
     */
    public synchronized void cleanup() {
        // Close active data connection
        if (activeDataConnection != null) {
            try {
                activeDataConnection.close();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Error closing data connection", e);
            }
            activeDataConnection = null;
        }
        
        // Close passive connector
        if (passiveConnector != null) {
            try {
                passiveConnector.stop();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Error stopping passive connector", e);
            }
            passiveConnector = null;
        }
        
        // Clear queued connections
        FTPDataConnection conn;
        while ((conn = incomingDataConnections.poll()) != null) {
            try {
                conn.close();
            } catch (Exception e) {
                // Ignore close errors
            }
        }
        
        // Reset state
        mode = DataConnectionMode.NONE;
        passivePort = -1;
        activeHost = null;
        activePort = -1;
        pendingTransfer = null;
        totalBytesTransferred = 0;
    }
    
    /**
     * Gets current data connection mode.
     */
    public DataConnectionMode getMode() {
        return mode;
    }
    
    /**
     * Gets the passive port (if in passive mode).
     */
    public int getPassivePort() {
        return passivePort;
    }
    
    /**
     * Checks if a data connection is currently active.
     */
    public boolean hasActiveDataConnection() {
        return activeDataConnection != null;
    }
    
    /**
     * Streams data between channels with progress tracking and mode conversion.
     * 
     * @param input source channel
     * @param output destination channel
     * @param metadata connection metadata for transfer type information
     * @throws IOException if transfer fails
     */
    private void streamChannelData(ReadableByteChannel input, WritableByteChannel output, 
                                 FTPConnectionMetadata metadata) throws IOException {
        
        ByteBuffer buffer = ByteBuffer.allocateDirect(TRANSFER_BUFFER_SIZE);
        long nextProgressNotify = PROGRESS_NOTIFY_INTERVAL;
        
        FTPConnectionMetadata.FTPTransferType transferType = metadata.getTransferType();
        boolean isAsciiMode = (transferType == FTPConnectionMetadata.FTPTransferType.ASCII);
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Starting channel data transfer in " + transferType + " mode");
        }
        
        try {
            while (input.read(buffer) != -1) {
                buffer.flip();
                
                if (isAsciiMode) {
                    // ASCII mode: Convert line endings from file system to network format
                    ByteBuffer converted = convertToNetworkFormat(buffer);
                    while (converted.hasRemaining()) {
                        int written = output.write(converted);
                        if (written == 0) {
                            // Channel may be non-blocking, but we need to write all data
                            Thread.yield();
                        }
                    }
                    totalBytesTransferred += converted.position();
                } else {
                    // Binary mode: Direct transfer
                    while (buffer.hasRemaining()) {
                        int written = output.write(buffer);
                        if (written == 0) {
                            // Channel may be non-blocking, but we need to write all data
                            Thread.yield();
                        }
                        totalBytesTransferred += written;
                    }
                }
                
                buffer.clear();
                
                // Progress notification
                if (totalBytesTransferred >= nextProgressNotify) {
                    notifyProgress();
                    nextProgressNotify = totalBytesTransferred + PROGRESS_NOTIFY_INTERVAL;
                }
            }
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Channel transfer completed: " + totalBytesTransferred + " bytes");
            }
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Channel transfer failed after " + totalBytesTransferred + " bytes", e);
            throw e;
        }
    }
    
    /**
     * Converts data from local format to network format for ASCII transfers.
     * In ASCII mode, line endings should be CRLF (\r\n) for network transmission.
     * 
     * @param buffer data buffer (will be consumed)
     * @return converted data with proper line endings
     */
    private ByteBuffer convertToNetworkFormat(ByteBuffer buffer) {
        // Simple implementation: convert LF to CRLF
        // TODO: More sophisticated conversion for different platforms
        
        int remaining = buffer.remaining();
        byte[] data = new byte[remaining];
        buffer.get(data);
        
        // Count LF characters to estimate output size
        int lfCount = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n' && (i == 0 || data[i-1] != '\r')) {
                lfCount++;
            }
        }
        
        if (lfCount == 0) {
            // No conversion needed
            return ByteBuffer.wrap(data);
        }
        
        // Convert LF to CRLF
        byte[] result = new byte[data.length + lfCount];
        int outputPos = 0;
        
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n' && (i == 0 || data[i-1] != '\r')) {
                result[outputPos++] = '\r';
                result[outputPos++] = '\n';
            } else {
                result[outputPos++] = data[i];
            }
        }
        
        return ByteBuffer.wrap(result);
    }
    
    /**
     * Notifies handler of transfer progress.
     */
    private void notifyProgress() {
        if (pendingTransfer != null && pendingTransfer.getHandler() != null) {
            try {
                boolean isUpload = (pendingTransfer.getType() == TransferType.UPLOAD);
                pendingTransfer.getHandler().transferProgress(
                    pendingTransfer.getPath(),
                    isUpload,
                    null, // no specific data chunk for periodic progress updates
                    totalBytesTransferred,
                    pendingTransfer.getMetadata()
                );
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying transfer progress", e);
            }
        }
    }
}
