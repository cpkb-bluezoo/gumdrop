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
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.util.ByteBufferPool;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPEndpoint;

/**
 * Coordinates FTP data connections as specified in RFC 959 sections 3.2–3.3.
 *
 * <p>RFC 959 section 3.2 defines two data connection models:
 * <ul>
 * <li><strong>Active Mode (PORT/EPRT)</strong>: Server connects to the client's
 *     specified address/port (RFC 959 section 3.2, RFC 2428 section 2)</li>
 * <li><strong>Passive Mode (PASV/EPSV)</strong>: Server opens a listening port,
 *     client connects to it (RFC 959 section 4.1.2, RFC 2428 section 3)</li>
 * </ul>
 *
 * <p>RFC 959 section 3.3 specifies that data connections are opened and closed
 * for each transfer, and the server closes the connection to signal EOF in
 * stream mode (section 3.4.1).
 *
 * <p>RFC 4217 section 7 adds TLS protection for data connections (PROT P/C).
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
     * Data connection modes per RFC 959 section 3.2.
     */
    public enum DataConnectionMode {
        /** No data connection established */
        NONE,
        /** Passive mode — server listens, client connects. RFC 959 PASV / RFC 2428 EPSV. */
        PASSIVE,
        /** Active mode — server connects to client. RFC 959 PORT / RFC 2428 EPRT. */
        ACTIVE
    }
    
    /**
     * Data transfer types
     */
    public enum TransferType {
        UPLOAD,           // STOR, APPE, STOU
        DOWNLOAD,         // RETR
        LISTING,          // LIST (full ls -l format)
        NAME_LIST,        // NLST (file names only, RFC 959 section 4.1.3)
        MACHINE_LISTING   // MLSD (machine-readable, RFC 3659 section 7)
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
    
    private final FTPControlConnection controlConnection;
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

    // RFC 4217 section 10: control connection client address for verification
    private InetAddress controlClientAddress;
    
    public FTPDataConnectionCoordinator(FTPControlConnection controlConnection) {
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
     * Configures passive mode data connection (RFC 959 section 4.1.2 PASV).
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
        org.bluezoo.gumdrop.Gumdrop.getInstance().getAcceptLoop().registerRawAcceptor(ssc, passiveConnector);
        
        mode = DataConnectionMode.PASSIVE;
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Passive mode enabled on port " + passivePort);
        }
        
        return passivePort;
    }
    
    /**
     * Configures active mode data connection (RFC 959 section 4.1.2 PORT).
     * Stores the client's address for a later outbound connection.
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
     * Sets the control connection client address for RFC 4217 section 10
     * data connection security verification.
     *
     * @param address the client address from the control connection
     */
    public void setControlClientAddress(InetAddress address) {
        this.controlClientAddress = address;
    }

    /**
     * Called by FTPDataServer when a client connects in passive mode.
     * Per RFC 4217 section 10, the data connection source IP is verified
     * against the control connection client IP to prevent hijacking.
     *
     * @param dataConnection the established data connection
     */
    public void acceptDataConnection(FTPDataConnection dataConnection) {
        try {
            // RFC 4217 section 10: verify data connection IP matches control
            if (controlClientAddress != null) {
                SocketChannel sc = dataConnection.getChannel();
                InetAddress dataAddr = ((InetSocketAddress) sc.getRemoteAddress()).getAddress();
                if (!controlClientAddress.equals(dataAddr)) {
                    LOGGER.warning("Data connection from " + dataAddr
                            + " does not match control connection from "
                            + controlClientAddress + "; rejecting");
                    dataConnection.close();
                    return;
                }
            }
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
     * RFC 959 section 3.2: server-DTP initiates connection to user-DTP.
     */
    private void establishActiveConnection() throws IOException {
        try {
            SocketChannel channel = SocketChannel.open();
            channel.connect(new InetSocketAddress(activeHost, activePort));

            activeDataConnection = new FTPDataConnection(channel, this);

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Connected to client data port " + activeHost + ":" + activePort);
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
                case NAME_LIST:
                case MACHINE_LISTING:
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
            TransferType type = pendingTransfer.getType();
            for (FTPFileInfo file : files) {
                if (type == TransferType.NAME_LIST) {
                    listing.append(file.getName());
                } else if (type == TransferType.MACHINE_LISTING) {
                    // RFC 3659 section 7: machine-readable format
                    listing.append(file.formatAsMLSEntry());
                } else {
                    listing.append(file.formatAsListingLine());
                }
                listing.append("\r\n");
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
     *
     * @return {@code true} if a data transfer was in progress when aborted
     */
    public synchronized boolean abortTransfer() {
        boolean wasTransferring = (activeDataConnection != null);
        if (wasTransferring) {
            try {
                activeDataConnection.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error closing data connection during abort", e);
            }
        }
        cleanup();
        return wasTransferring;
    }
    
    /**
     * Generates PASV response string per RFC 959 section 4.1.2:
     * "227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)".
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
     * Implements RFC 959 section 3.4.1 stream mode transfer: data sent as a
     * continuous stream, EOF signalled by closing the data connection.
     *
     * <p>For ASCII type (RFC 959 section 3.1.1.1), line endings are converted
     * to the NVT-ASCII CRLF convention for network transmission.
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
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);
                    ByteBuffer converted = convertToNetworkFormat(data);
                    try {
                        while (converted.hasRemaining()) {
                            int written = output.write(converted);
                            if (written == 0) {
                                // Channel may be non-blocking, but we need to write all data
                                Thread.yield();
                            }
                        }
                        totalBytesTransferred += converted.position();
                    } finally {
                        ByteBufferPool.release(converted);
                    }
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
     * Converts data from local format to NVT-ASCII network format per
     * RFC 959 section 3.1.1.1: line endings MUST be CRLF.
     * Single-pass conversion: LF without preceding CR becomes CRLF.
     *
     * @param data input data
     * @return converted data with proper line endings (caller must release via ByteBufferPool)
     */
    private ByteBuffer convertToNetworkFormat(byte[] data) {
        ByteBuffer result = ByteBufferPool.acquire(data.length * 2);
        for (int i = 0; i < data.length; i++) {
            byte b = data[i];
            if (b == '\n' && (i == 0 || data[i - 1] != '\r')) {
                result.put((byte) '\r');
            }
            result.put(b);
        }
        result.flip();
        return result;
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

    // ── Event-driven data transfer ──

    /**
     * Callback for asynchronous transfer completion.
     */
    public interface TransferCallback {
        void transferComplete(long bytesTransferred);
        void transferFailed(IOException cause);
    }

    /**
     * Starts an asynchronous download (RETR) using the endpoint
     * write-pacing API.  The file is read in chunks and sent via the
     * data endpoint; {@code onWriteReady} paces the output.
     *
     * @param controlEndpoint the control connection endpoint (for
     *        its SelectorLoop)
     * @param transfer the pending transfer details
     * @param callback completion callback
     * @throws IOException if setup fails
     */
    public synchronized void startAsyncDownload(
            Endpoint controlEndpoint,
            PendingTransfer transfer,
            TransferCallback callback) throws IOException {

        if (mode == DataConnectionMode.NONE) {
            throw new IOException("No data connection mode configured");
        }

        this.pendingTransfer = transfer;
        this.transferStartTime = System.currentTimeMillis();
        this.totalBytesTransferred = 0;

        switch (mode) {
            case PASSIVE:
                waitForPassiveConnection();
                break;
            case ACTIVE:
                establishActiveConnection();
                break;
            default:
                throw new IOException("Invalid data connection mode");
        }

        FTPFileSystem fs = transfer.getHandler().getFileSystem(
                transfer.getMetadata());
        if (fs == null) {
            throw new IOException("No file system available");
        }

        Path asyncPath = fs.resolvePathForAsyncRead(
                transfer.getPath(),
                transfer.getRestartOffset(),
                transfer.getMetadata());
        AsynchronousFileChannel asyncChannel = null;
        ReadableByteChannel fallbackChannel = null;
        if (asyncPath != null) {
            try {
                asyncChannel = AsynchronousFileChannel.open(
                        asyncPath,
                        StandardOpenOption.READ);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to open AsynchronousFileChannel, falling back to sync", e);
            }
        }
        if (asyncChannel == null) {
            fallbackChannel = fs.openForReading(
                    transfer.getPath(),
                    transfer.getRestartOffset(),
                    transfer.getMetadata());
            if (fallbackChannel == null) {
                throw new IOException("Failed to open file: "
                        + transfer.getPath());
            }
        }

        SelectorLoop loop = controlEndpoint.getSelectorLoop();
        SocketChannel dataSc = activeDataConnection.getChannel();
        dataSc.configureBlocking(false);

        DownloadTransferHandler downloadHandler =
                new DownloadTransferHandler(
                        asyncChannel, fallbackChannel, transfer, callback);
        TCPEndpoint dataEndpoint =
                new TCPEndpoint(downloadHandler);
        dataEndpoint.setChannel(dataSc);
        dataEndpoint.init();
        loop.registerTCP(dataSc, dataEndpoint);
        downloadHandler.setEndpoint(dataEndpoint);
        downloadHandler.writeNextChunk();
    }

    /**
     * Starts an asynchronous directory listing (LIST/NLST) using the
     * endpoint write-pacing API.  Listing data is sent in chunks via
     * onWriteReady to avoid blocking.
     *
     * @param controlEndpoint the control connection endpoint
     * @param transfer the pending transfer details
     * @param callback completion callback
     * @throws IOException if setup fails
     */
    public synchronized void startAsyncListing(
            Endpoint controlEndpoint,
            PendingTransfer transfer,
            TransferCallback callback) throws IOException {

        if (mode == DataConnectionMode.NONE) {
            throw new IOException("No data connection mode configured");
        }

        this.pendingTransfer = transfer;
        this.transferStartTime = System.currentTimeMillis();
        this.totalBytesTransferred = 0;

        switch (mode) {
            case PASSIVE:
                waitForPassiveConnection();
                break;
            case ACTIVE:
                establishActiveConnection();
                break;
            default:
                throw new IOException("Invalid data connection mode");
        }

        FTPFileSystem fs = transfer.getHandler().getFileSystem(
                transfer.getMetadata());
        if (fs == null) {
            throw new IOException("No file system available");
        }

        List<FTPFileInfo> files = fs.listDirectory(
                transfer.getPath(), transfer.getMetadata());
        if (files == null) {
            throw new IOException("Failed to list directory: "
                    + transfer.getPath());
        }

        TransferType transferType = transfer.getType();
        StringBuilder listing = new StringBuilder();
        for (FTPFileInfo file : files) {
            if (transferType == TransferType.NAME_LIST) {
                listing.append(file.getName());
            } else if (transferType == TransferType.MACHINE_LISTING) {
                listing.append(file.formatAsMLSEntry());
            } else {
                listing.append(file.formatAsListingLine());
            }
            listing.append("\r\n");
        }
        byte[] listingBytes = listing.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer listingBuffer = ByteBuffer.wrap(listingBytes);

        SelectorLoop loop = controlEndpoint.getSelectorLoop();
        SocketChannel dataSc = activeDataConnection.getChannel();
        dataSc.configureBlocking(false);

        ListingTransferHandler listingHandler =
                new ListingTransferHandler(listingBuffer, transfer, callback);
        TCPEndpoint dataEndpoint = new TCPEndpoint(listingHandler);
        dataEndpoint.setChannel(dataSc);
        dataEndpoint.init();
        loop.registerTCP(dataSc, dataEndpoint);
        listingHandler.setEndpoint(dataEndpoint);
        listingHandler.sendNextChunk();
    }

    /**
     * Starts an asynchronous upload (STOR) using the endpoint
     * read-pause API.  Data arrives via the data endpoint's
     * {@code receive()} method and is written to the file channel.
     *
     * @param controlEndpoint the control connection endpoint
     * @param transfer the pending transfer details
     * @param callback completion callback
     * @throws IOException if setup fails
     */
    public synchronized void startAsyncUpload(
            Endpoint controlEndpoint,
            PendingTransfer transfer,
            TransferCallback callback) throws IOException {

        if (mode == DataConnectionMode.NONE) {
            throw new IOException("No data connection mode configured");
        }

        this.pendingTransfer = transfer;
        this.transferStartTime = System.currentTimeMillis();
        this.totalBytesTransferred = 0;

        switch (mode) {
            case PASSIVE:
                waitForPassiveConnection();
                break;
            case ACTIVE:
                establishActiveConnection();
                break;
            default:
                throw new IOException("Invalid data connection mode");
        }

        FTPFileSystem fs = transfer.getHandler().getFileSystem(
                transfer.getMetadata());
        if (fs == null) {
            throw new IOException("No file system available");
        }

        String targetPath = transfer.getPath();
        if (transfer.getType() == TransferType.UPLOAD && targetPath.isEmpty()) {
            FTPFileSystem.UniqueNameResult uniqueResult = fs.generateUniqueName(
                    "/", null, transfer.getMetadata());
            if (uniqueResult.getResult() != FTPFileOperationResult.SUCCESS) {
                throw new IOException("Failed to generate unique filename");
            }
            targetPath = uniqueResult.getUniquePath();
        }

        Path asyncPath = fs.resolvePathForAsyncWrite(
                targetPath, transfer.isAppend(), transfer.getMetadata());
        AsynchronousFileChannel asyncChannel = null;
        WritableByteChannel fallbackChannel = null;
        if (asyncPath != null) {
            try {
                StandardOpenOption[] options = transfer.isAppend()
                        ? new StandardOpenOption[]{
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.APPEND}
                        : new StandardOpenOption[]{
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.TRUNCATE_EXISTING};
                asyncChannel = AsynchronousFileChannel.open(asyncPath, options);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to open AsynchronousFileChannel for upload, falling back to sync", e);
            }
        }
        if (asyncChannel == null) {
            fallbackChannel = fs.openForWriting(
                    targetPath, transfer.isAppend(), transfer.getMetadata());
            if (fallbackChannel == null) {
                throw new IOException("Failed to open file: " + targetPath);
            }
        }

        SelectorLoop loop = controlEndpoint.getSelectorLoop();
        SocketChannel dataSc = activeDataConnection.getChannel();
        dataSc.configureBlocking(false);

        long initialPosition = 0;
        if (asyncChannel != null && transfer.isAppend()) {
            try {
                initialPosition = asyncChannel.size();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to get file size for append", e);
            }
        }

        UploadTransferHandler uploadHandler =
                new UploadTransferHandler(
                        asyncChannel, fallbackChannel, transfer, callback,
                        initialPosition);
        TCPEndpoint dataEndpoint =
                new TCPEndpoint(uploadHandler);
        dataEndpoint.setChannel(dataSc);
        dataEndpoint.init();
        loop.registerTCP(dataSc, dataEndpoint);
        uploadHandler.setEndpoint(dataEndpoint);
    }

    /**
     * Event-driven listing handler.  Sends listing data in chunks via
     * onWriteReady to avoid blocking the selector loop.
     */
    private class ListingTransferHandler
            implements ProtocolHandler, Runnable {

        private final ByteBuffer listingBuffer;
        private final PendingTransfer transfer;
        private final TransferCallback callback;
        private Endpoint dataEndpoint;

        ListingTransferHandler(ByteBuffer listingBuffer,
                PendingTransfer transfer,
                TransferCallback callback) {
            this.listingBuffer = listingBuffer;
            this.transfer = transfer;
            this.callback = callback;
        }

        void setEndpoint(Endpoint endpoint) {
            this.dataEndpoint = endpoint;
        }

        @Override
        public void run() {
            sendNextChunk();
        }

        void sendNextChunk() {
            if (!listingBuffer.hasRemaining()) {
                finishListing();
                return;
            }
            dataEndpoint.send(listingBuffer);
            if (listingBuffer.hasRemaining()) {
                dataEndpoint.onWriteReady(this);
            } else {
                finishListing();
            }
        }

        private void finishListing() {
            totalBytesTransferred = listingBuffer.capacity();
            dataEndpoint.onWriteReady(null);
            dataEndpoint.close();
            notifyTransferHandler(true);
            callback.transferComplete(totalBytesTransferred);
        }

        @Override
        public void connected(Endpoint endpoint) {
        }

        @Override
        public void receive(ByteBuffer data) {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void error(Exception e) {
            LOGGER.log(Level.WARNING, "Data connection error during listing", e);
            dataEndpoint.close();
            callback.transferFailed(
                    e instanceof IOException
                            ? (IOException) e
                            : new IOException("Listing failed", e));
        }
    }

    /**
     * Event-driven download handler.  Reads chunks from a file via
     * AsynchronousFileChannel (or fallback sync channel) and sends
     * them via the data endpoint, using onWriteReady to pace output.
     */
    private class DownloadTransferHandler
            implements ProtocolHandler, Runnable {

        private final AsynchronousFileChannel asyncChannel;
        private final ReadableByteChannel fallbackChannel;
        private final PendingTransfer transfer;
        private final TransferCallback callback;
        private final ByteBuffer readBuf;
        private long filePosition;
        private Endpoint dataEndpoint;

        DownloadTransferHandler(AsynchronousFileChannel asyncChannel,
                ReadableByteChannel fallbackChannel,
                PendingTransfer transfer,
                TransferCallback callback) {
            this.asyncChannel = asyncChannel;
            this.fallbackChannel = fallbackChannel;
            this.transfer = transfer;
            this.callback = callback;
            this.readBuf = ByteBuffer.allocate(TRANSFER_BUFFER_SIZE);
            this.filePosition = transfer.getRestartOffset();
        }

        void setEndpoint(Endpoint endpoint) {
            this.dataEndpoint = endpoint;
        }

        @Override
        public void run() {
            writeNextChunk();
        }

        void writeNextChunk() {
            if (asyncChannel != null) {
                writeNextChunkAsync();
            } else {
                writeNextChunkSync();
            }
        }

        private void writeNextChunkAsync() {
            ByteBuffer buf = ByteBufferPool.acquire(TRANSFER_BUFFER_SIZE);
            asyncChannel.read(buf, filePosition, null,
                    new CompletionHandler<Integer, Void>() {
                        @Override
                        public void completed(Integer result, Void attachment) {
                            if (result == null || result <= 0) {
                                ByteBufferPool.release(buf);
                                dataEndpoint.execute(
                                        DownloadTransferHandler.this::closeAndFinish);
                                return;
                            }
                            final int bytesRead = result;
                            filePosition += bytesRead;
                            totalBytesTransferred += bytesRead;
                            buf.flip();
                            dataEndpoint.execute(() -> {
                                try {
                                    dataEndpoint.send(buf);
                                    ByteBufferPool.release(buf);
                                    dataEndpoint.onWriteReady(
                                            DownloadTransferHandler.this);
                                } catch (Exception e) {
                                    ByteBufferPool.release(buf);
                                    handleAsyncError(e);
                                }
                            });
                        }

                        @Override
                        public void failed(Throwable exc, Void attachment) {
                            ByteBufferPool.release(buf);
                            LOGGER.log(Level.WARNING,
                                    "Async file read failed", exc);
                            dataEndpoint.execute(() -> handleAsyncError(exc));
                        }
                    });
        }

        private void writeNextChunkSync() {
            try {
                readBuf.clear();
                int bytesRead = fallbackChannel.read(readBuf);

                if (bytesRead == -1) {
                    finishDownload();
                    return;
                }

                readBuf.flip();
                totalBytesTransferred += bytesRead;
                dataEndpoint.send(readBuf);
                dataEndpoint.onWriteReady(this);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Error during async download", e);
                closeChannels();
                dataEndpoint.close();
                callback.transferFailed(e);
            }
        }

        private void closeAndFinish() {
            closeChannels();
            dataEndpoint.onWriteReady(null);
            dataEndpoint.close();
            notifyTransferHandler(true);
            callback.transferComplete(totalBytesTransferred);
        }

        private void closeChannels() {
            if (asyncChannel != null) {
                try {
                    asyncChannel.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING,
                            "Error closing async file channel", e);
                }
            }
            if (fallbackChannel != null) {
                try {
                    fallbackChannel.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING,
                            "Error closing file channel", e);
                }
            }
        }

        private void finishDownload() {
            closeChannels();
            dataEndpoint.onWriteReady(null);
            dataEndpoint.close();
            notifyTransferHandler(true);
            callback.transferComplete(totalBytesTransferred);
        }

        private void handleAsyncError(Throwable exc) {
            closeChannels();
            dataEndpoint.close();
            IOException ioe = exc instanceof IOException
                    ? (IOException) exc
                    : new IOException("Data transfer error", exc);
            callback.transferFailed(ioe);
        }

        @Override
        public void connected(Endpoint endpoint) {
            // Already connected when registered
        }

        @Override
        public void receive(ByteBuffer data) {
            // Download: we don't expect inbound data
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            // Not used for data connections
        }

        @Override
        public void disconnected() {
            closeChannels();
        }

        @Override
        public void error(Exception e) {
            LOGGER.log(Level.WARNING, "Data connection error", e);
            closeChannels();
            if (e instanceof IOException) {
                callback.transferFailed((IOException) e);
            } else {
                callback.transferFailed(
                        new IOException("Data transfer error", e));
            }
        }
    }

    /**
     * Event-driven upload handler.  Receives data from the data
     * endpoint and writes it via AsynchronousFileChannel (or fallback
     * sync channel).  Pauses reads while a write is in-flight.
     */
    private class UploadTransferHandler implements ProtocolHandler {

        private final AsynchronousFileChannel asyncChannel;
        private final WritableByteChannel fallbackChannel;
        private final PendingTransfer transfer;
        private final TransferCallback callback;
        private long filePosition;
        private boolean writeInFlight;
        private boolean finished;
        private final Queue<ByteBuffer> pendingWrites = new ArrayDeque<>();
        private Endpoint dataEndpoint;

        UploadTransferHandler(AsynchronousFileChannel asyncChannel,
                WritableByteChannel fallbackChannel,
                PendingTransfer transfer,
                TransferCallback callback,
                long initialPosition) {
            this.asyncChannel = asyncChannel;
            this.fallbackChannel = fallbackChannel;
            this.transfer = transfer;
            this.callback = callback;
            this.filePosition = initialPosition;
        }

        void setEndpoint(Endpoint endpoint) {
            this.dataEndpoint = endpoint;
        }

        @Override
        public void connected(Endpoint endpoint) {
            // Already connected when registered
        }

        @Override
        public void receive(ByteBuffer data) {
            if (asyncChannel != null) {
                receiveAsync(data);
            } else {
                receiveSync(data);
            }
        }

        private void receiveAsync(ByteBuffer data) {
            if (data.remaining() == 0) {
                return;
            }
            ByteBuffer buf = ByteBufferPool.acquire(data.remaining());
            buf.put(data);
            buf.flip();
            pendingWrites.add(buf);
            drainPendingWrites();
        }

        private void drainPendingWrites() {
            if (writeInFlight || pendingWrites.isEmpty()) {
                return;
            }
            ByteBuffer buf = pendingWrites.poll();
            if (buf == null) {
                return;
            }
            writeInFlight = true;
            dataEndpoint.pauseRead();
            final long pos = filePosition;
            final int len = buf.remaining();
            asyncChannel.write(buf, pos, null,
                    new CompletionHandler<Integer, Void>() {
                        @Override
                        public void completed(Integer result, Void attachment) {
                            ByteBufferPool.release(buf);
                            if (result != null && result > 0) {
                                filePosition += result;
                                totalBytesTransferred += result;
                            }
                            writeInFlight = false;
                            dataEndpoint.execute(() -> {
                                dataEndpoint.resumeRead();
                                if (!pendingWrites.isEmpty()) {
                                    drainPendingWrites();
                                }
                            });
                        }

                        @Override
                        public void failed(Throwable exc, Void attachment) {
                            ByteBufferPool.release(buf);
                            writeInFlight = false;
                            LOGGER.log(Level.WARNING,
                                    "Async file write failed", exc);
                            dataEndpoint.execute(() -> {
                                if (finished) {
                                    return;
                                }
                                finished = true;
                                closeChannels();
                                dataEndpoint.close();
                                callback.transferFailed(
                                        exc instanceof IOException
                                                ? (IOException) exc
                                                : new IOException(
                                                        "Upload failed", exc));
                            });
                        }
                    });
        }

        private void receiveSync(ByteBuffer data) {
            try {
                int written = 0;
                while (data.hasRemaining()) {
                    written += fallbackChannel.write(data);
                }
                totalBytesTransferred += written;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Error writing upload data", e);
                closeChannels();
                dataEndpoint.close();
                callback.transferFailed(e);
            }
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            // Not used for data connections
        }

        @Override
        public void disconnected() {
            if (finished) {
                return;
            }
            finished = true;
            if (asyncChannel != null) {
                while (!pendingWrites.isEmpty()) {
                    ByteBuffer b = pendingWrites.poll();
                    if (b != null) {
                        ByteBufferPool.release(b);
                    }
                }
            }
            closeChannels();
            notifyTransferHandler(true);
            callback.transferComplete(totalBytesTransferred);
        }

        @Override
        public void error(Exception e) {
            LOGGER.log(Level.WARNING, "Data connection error", e);
            closeChannels();
            if (e instanceof IOException) {
                callback.transferFailed((IOException) e);
            } else {
                callback.transferFailed(
                        new IOException("Data transfer error", e));
            }
        }

        private void closeChannels() {
            if (asyncChannel != null) {
                try {
                    asyncChannel.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING,
                            "Error closing async file channel", e);
                }
            }
            if (fallbackChannel != null) {
                try {
                    fallbackChannel.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING,
                            "Error closing file channel", e);
                }
            }
        }
    }

    private void notifyTransferHandler(boolean success) {
        if (pendingTransfer != null
                && pendingTransfer.getHandler() != null) {
            boolean isUpload =
                    (pendingTransfer.getType() == TransferType.UPLOAD);
            pendingTransfer.getHandler().transferCompleted(
                    pendingTransfer.getPath(),
                    isUpload,
                    totalBytesTransferred,
                    success,
                    pendingTransfer.getMetadata());
        }
    }
}
