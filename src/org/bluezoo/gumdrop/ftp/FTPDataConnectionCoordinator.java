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
import java.net.UnknownHostException;
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
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.util.ByteBufferPool;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPEndpoint;
import org.bluezoo.gumdrop.TimerHandle;

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

    // Async data-connection acquisition state (guarded by 'this'). When a
    // transfer is requested in passive mode before the client's data
    // connection has arrived, the continuation is parked here rather than
    // blocking the selector-loop thread, and a timeout timer is armed on the
    // control endpoint so a client that never connects cannot stall the loop.
    private DataConnectionReady waitingContinuation;
    private TransferCallback waitingCallback;
    private Endpoint waitingControlEndpoint;
    private TimerHandle connectionTimeout;

    /**
     * How long the server waits for the client to open the data connection
     * before failing the transfer. RFC 959 mandates no fixed value; 30s
     * matches the previous (blocking) behaviour.
     */
    private static final long DATA_CONNECTION_TIMEOUT_MS = 30_000L;
    
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
     * <p>RFC 4217 section 10: unless {@link FTPListener#isAllowActiveModeBounce()}
     * is enabled, the data address must match the control connection client IP.
     *
     * @param host client's host address
     * @param port client's port number
     * @return true if active mode was configured, false if rejected
     */
    public synchronized boolean setupActiveMode(String host, int port) {
        try {
            InetAddress dataAddress = InetAddress.getByName(host);
            if (!isActiveDataAddressAllowed(dataAddress)) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning("Active mode data address " + dataAddress
                            + " does not match control connection from "
                            + controlClientAddress + "; rejecting");
                }
                return false;
            }
        } catch (UnknownHostException e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Active mode rejected: unknown host " + host);
            }
            return false;
        }

        cleanup(); // Clean up any existing setup
        
        this.activeHost = host;
        this.activePort = port;
        this.mode = DataConnectionMode.ACTIVE;
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Active mode configured to " + host + ":" + port);
        }
        return true;
    }

    /**
     * RFC 4217 section 10: active-mode data address must match the control
     * client's IP unless the listener explicitly allows FTP bounce.
     */
    private boolean isActiveDataAddressAllowed(InetAddress dataAddress) {
        FTPListener server = controlConnection.getServer();
        if (server != null && server.isAllowActiveModeBounce()) {
            return true;
        }
        if (controlClientAddress == null) {
            return false;
        }
        return controlClientAddress.equals(dataAddress);
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
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error accepting data connection", e);
            try {
                dataConnection.close();
            } catch (Exception closeEx) {
                // Ignore close errors
            }
            return;
        }

        // This runs on the accept-selector thread. If a transfer is already
        // waiting for its data connection, hand the connection to it on the
        // control connection's loop thread; otherwise queue it (the connection
        // arrived before the transfer command, which is legal). The poll in
        // acquireDataConnection() and this hand-off are mutually exclusive on
        // 'this', so there is no lost-wakeup race.
        Endpoint controlEndpoint;
        TransferCallback callback;
        DataConnectionReady continuation;
        synchronized (this) {
            if (waitingContinuation == null) {
                incomingDataConnections.offer(dataConnection);
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Data connection queued (arrived before command)");
                }
                return;
            }
            controlEndpoint = waitingControlEndpoint;
            callback = waitingCallback;
            continuation = waitingContinuation;
            waitingControlEndpoint = null;
            waitingCallback = null;
            waitingContinuation = null;
        }

        final Endpoint ep = controlEndpoint;
        final TransferCallback cb = callback;
        final DataConnectionReady cont = continuation;
        ep.execute(new Runnable() {
            @Override
            public void run() {
                cancelConnectionTimeout();
                deliverDataConnection(ep, cb, cont, dataConnection);
            }
        });
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

    // ── Event-driven data transfer ──

    /**
     * Callback for asynchronous transfer completion.
     */
    public interface TransferCallback {
        void transferComplete(long bytesTransferred);
        void transferFailed(IOException cause);
    }

    /**
     * Continuation run (on the control connection's loop thread) once a data
     * connection is available. Implementations perform the post-connection
     * transfer setup that used to follow the blocking wait for the data
     * connection.
     */
    private interface DataConnectionReady {
        void ready(FTPDataConnection connection) throws IOException;
    }

    /**
     * Obtains the data connection <em>without blocking the selector loop</em>
     * and then runs {@code continuation} on the control loop once it is
     * available.
     *
     * <ul>
     * <li>Passive mode: if the client has already connected, the queued
     *     connection is used immediately; otherwise the continuation is parked
     *     and a timeout timer is armed. When the client connects,
     *     {@link #acceptDataConnection} resumes the continuation on the loop.
     *     Previously this blocked the loop thread for up to 30s in a
     *     {@code poll()} — a trivial denial of service.</li>
     * <li>Active mode: the outbound connect (which blocks) is offloaded to the
     *     shared {@link StorageExecutor} rather than run on the loop, and the
     *     continuation resumes on the loop when the connect completes.</li>
     * </ul>
     *
     * @param controlEndpoint the control connection endpoint (for its loop and
     *        timer scheduling)
     * @param callback the transfer completion/failure callback
     * @param continuation the post-connection setup, run on the loop thread
     */
    private void acquireDataConnection(Endpoint controlEndpoint,
            TransferCallback callback, DataConnectionReady continuation) {
        switch (mode) {
            case PASSIVE: {
                FTPDataConnection existing;
                synchronized (this) {
                    existing = incomingDataConnections.poll();
                    if (existing == null) {
                        // Park until the client connects (or we time out).
                        waitingControlEndpoint = controlEndpoint;
                        waitingCallback = callback;
                        waitingContinuation = continuation;
                        connectionTimeout = controlEndpoint.scheduleTimer(
                                DATA_CONNECTION_TIMEOUT_MS,
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        onDataConnectionTimeout();
                                    }
                                });
                    }
                }
                if (existing != null) {
                    deliverDataConnection(controlEndpoint, callback,
                            continuation, existing);
                }
                break;
            }
            case ACTIVE:
                connectActiveModeAsync(controlEndpoint, callback, continuation);
                break;
            default:
                failTransfer(controlEndpoint, callback,
                        new IOException("No data connection mode configured"));
        }
    }

    /**
     * Runs the post-connection continuation on the loop thread, recording the
     * active data connection first so the existing transfer-setup code can find
     * it. On setup failure the transfer is cleaned up and reported as failed.
     */
    private void deliverDataConnection(Endpoint controlEndpoint,
            TransferCallback callback, DataConnectionReady continuation,
            FTPDataConnection connection) {
        activeDataConnection = connection;
        try {
            continuation.ready(connection);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "FTP data transfer setup failed", e);
            cleanup();
            callback.transferFailed(e);
        }
    }

    /**
     * Opens the active-mode data connection on the shared storage pool (a
     * blocking connect must never run on the loop), then resumes on the loop.
     */
    private void connectActiveModeAsync(final Endpoint controlEndpoint,
            final TransferCallback callback,
            final DataConnectionReady continuation) {
        final String host = activeHost;
        final int port = activePort;
        try {
            InetAddress dataAddress = InetAddress.getByName(host);
            if (!isActiveDataAddressAllowed(dataAddress)) {
                failTransfer(controlEndpoint, callback, new IOException(
                        "Active mode data address does not match control client"));
                return;
            }
        } catch (UnknownHostException e) {
            failTransfer(controlEndpoint, callback, new IOException(
                    "Active mode data address is unknown: " + host, e));
            return;
        }
        Gumdrop gumdrop = Gumdrop.getInstance();
        StorageExecutor exec =
                (gumdrop != null) ? gumdrop.getStorageExecutor() : null;
        if (exec == null) {
            failTransfer(controlEndpoint, callback, new IOException(
                    "Server not started; cannot open active data connection"));
            return;
        }
        exec.submit(controlEndpoint, new Callable<FTPDataConnection>() {
            @Override
            public FTPDataConnection call() throws IOException {
                SocketChannel channel = SocketChannel.open();
                channel.socket().connect(new InetSocketAddress(host, port),
                        (int) DATA_CONNECTION_TIMEOUT_MS);
                return new FTPDataConnection(channel,
                        FTPDataConnectionCoordinator.this);
            }
        }, new StorageExecutor.Callback<FTPDataConnection>() {
            @Override
            public void completed(FTPDataConnection connection) {
                deliverDataConnection(controlEndpoint, callback,
                        continuation, connection);
            }
            @Override
            public void failed(Throwable error) {
                IOException e = (error instanceof IOException)
                        ? (IOException) error
                        : new IOException("Active data connection failed",
                                error);
                cleanup();
                callback.transferFailed(e);
            }
        });
    }

    /**
     * Fails a transfer on the control loop (used when acquisition cannot even
     * begin). Cleans up and notifies the callback.
     */
    private void failTransfer(final Endpoint controlEndpoint,
            final TransferCallback callback, final IOException error) {
        controlEndpoint.execute(new Runnable() {
            @Override
            public void run() {
                cleanup();
                callback.transferFailed(error);
            }
        });
    }

    /**
     * Fires (on the loop) when no data connection arrived within
     * {@link #DATA_CONNECTION_TIMEOUT_MS}. If the transfer is still waiting it
     * is failed; otherwise the connection already arrived and this is a no-op.
     */
    private void onDataConnectionTimeout() {
        Endpoint controlEndpoint;
        TransferCallback callback;
        synchronized (this) {
            if (waitingContinuation == null) {
                return; // connection already delivered
            }
            controlEndpoint = waitingControlEndpoint;
            callback = waitingCallback;
            waitingControlEndpoint = null;
            waitingCallback = null;
            waitingContinuation = null;
            connectionTimeout = null;
        }
        LOGGER.warning("Timeout waiting for FTP client data connection");
        cleanup();
        callback.transferFailed(
                new IOException("Timeout waiting for client data connection"));
    }

    /**
     * Cancels the data-connection timeout timer, if armed.
     */
    private synchronized void cancelConnectionTimeout() {
        if (connectionTimeout != null) {
            connectionTimeout.cancel();
            connectionTimeout = null;
        }
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

        acquireDataConnection(controlEndpoint, callback,
                new DataConnectionReady() {
                    @Override
                    public void ready(FTPDataConnection connection)
                            throws IOException {
                        beginDownload(controlEndpoint, transfer, callback);
                    }
                });
    }

    private void beginDownload(Endpoint controlEndpoint,
            PendingTransfer transfer, TransferCallback callback)
            throws IOException {
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

        acquireDataConnection(controlEndpoint, callback,
                new DataConnectionReady() {
                    @Override
                    public void ready(FTPDataConnection connection)
                            throws IOException {
                        beginListing(controlEndpoint, transfer, callback);
                    }
                });
    }

    private void beginListing(Endpoint controlEndpoint,
            PendingTransfer transfer, TransferCallback callback)
            throws IOException {
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

        acquireDataConnection(controlEndpoint, callback,
                new DataConnectionReady() {
                    @Override
                    public void ready(FTPDataConnection connection)
                            throws IOException {
                        beginUpload(controlEndpoint, transfer, callback);
                    }
                });
    }

    private void beginUpload(Endpoint controlEndpoint,
            PendingTransfer transfer, TransferCallback callback)
            throws IOException {
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
        private final FTPAsciiLineEndings asciiCodec;
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
            this.asciiCodec = isAsciiType(transfer)
                    ? new FTPAsciiLineEndings() : null;
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
                                    if (asciiCodec != null) {
                                        ByteBuffer encoded =
                                                asciiCodec.encode(buf);
                                        dataEndpoint.send(encoded);
                                        ByteBufferPool.release(encoded);
                                    } else {
                                        dataEndpoint.send(buf);
                                    }
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
                if (asciiCodec != null) {
                    ByteBuffer encoded = asciiCodec.encode(readBuf);
                    dataEndpoint.send(encoded);
                    ByteBufferPool.release(encoded);
                } else {
                    dataEndpoint.send(readBuf);
                }
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
        private final boolean asciiMode;
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
            this.asciiMode = isAsciiType(transfer);
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
            ByteBuffer buf;
            if (asciiMode) {
                buf = FTPAsciiLineEndings.decode(data);
                if (!buf.hasRemaining()) {
                    // Chunk was only CR bytes; nothing to write.
                    ByteBufferPool.release(buf);
                    return;
                }
            } else {
                buf = ByteBufferPool.acquire(data.remaining());
                buf.put(data);
                buf.flip();
            }
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
                ByteBuffer out = asciiMode
                        ? FTPAsciiLineEndings.decode(data) : data;
                try {
                    int written = 0;
                    while (out.hasRemaining()) {
                        written += fallbackChannel.write(out);
                    }
                    totalBytesTransferred += written;
                } finally {
                    if (asciiMode) {
                        ByteBufferPool.release(out);
                    }
                }
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

    /**
     * Returns whether the transfer uses TYPE A (ASCII), which requires
     * NVT-ASCII line-ending conversion per RFC 959 section 3.1.1.1.
     */
    private static boolean isAsciiType(PendingTransfer transfer) {
        return transfer.getMetadata().getTransferType()
                == FTPConnectionMetadata.FTPTransferType.ASCII;
    }
}
