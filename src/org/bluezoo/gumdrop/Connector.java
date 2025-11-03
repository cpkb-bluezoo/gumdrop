/*
 * Connector.java
 * Copyright (C) 2005, 2025 Chris Burdess
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

package org.bluezoo.gumdrop;

import org.bluezoo.gumdrop.util.EmptyX509TrustManager;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;

/**
 * Abstract base class for connection factories.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class Connector {

    private static final Logger LOGGER = Logger.getLogger(Connection.class.getName());

    protected static final Map<TimeUnit,String> TIME_UNITS;
    static {
        TIME_UNITS = new HashMap<>();
        TIME_UNITS.put(TimeUnit.DAYS, "d");
        TIME_UNITS.put(TimeUnit.HOURS, "h");
        TIME_UNITS.put(TimeUnit.MINUTES, "m");
        TIME_UNITS.put(TimeUnit.SECONDS, "s");
        TIME_UNITS.put(TimeUnit.MILLISECONDS, "ms");
        TIME_UNITS.put(TimeUnit.MICROSECONDS, "us");
        TIME_UNITS.put(TimeUnit.NANOSECONDS, "ns");
    }

    private List<ServerSocketChannel> serverChannels = new ArrayList<ServerSocketChannel>();
    private Set<InetAddress> addresses = null;

    private final ThreadPoolExecutor connectorThreadPool;

    protected boolean secure = false;
    protected SSLContext context;
    protected String keystoreFile, keystorePass, keystoreFormat = "PKCS12";
    protected boolean needClientAuth = false;

    protected Connector() {
        connectorThreadPool = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), new ConnectorThreadFactory());
    }

    final Connection newConnection(SocketChannel sc, SelectionKey key) throws IOException {
        SSLEngine engine = null;
        // Always create SSLEngine if SSL context is available (regardless of secure flag)
        // This enables STARTTLS capability even on secure=false connectors
        if (context != null) {
            InetSocketAddress peerAddress = (InetSocketAddress) sc.getRemoteAddress();
            String peerHost = peerAddress.getHostName();
            int peerPort = peerAddress.getPort();
            engine = context.createSSLEngine(peerHost, peerPort);
            engine.setUseClientMode(false); // we are a server
            if (needClientAuth) {
                engine.setNeedClientAuth(true);
            } else {
                engine.setWantClientAuth(true);
            }
        }
        Connection connection = newConnection(sc, engine);
        connection.channel = sc;
        connection.key = key;
        connection.threadPool = connectorThreadPool;
        connection.init();
        return connection;
    }

    /**
     * Returns a new connection for the given channel.
     * @param channel the socket channel
     * @param engine if this connector is secure, the SSL engine to use for
     * wrapping the connection
     */
    protected abstract Connection newConnection(SocketChannel channel, SSLEngine engine);

    /**
     * Returns the thread pool used by connections created by this connector
     * to service incoming requests.
     */
    public ThreadPoolExecutor getConnectorThreadPool() {
        return connectorThreadPool;
    }

    /**
     * Configures the addresses this connector should bind to.
     */
    public void setAddresses(String value) {
        if (value == null) {
            addresses = null;
            return;
        }
        addresses = new LinkedHashSet<>();
        StringTokenizer st = new StringTokenizer(value);
        while (st.hasMoreTokens()) {
            String host = st.nextToken();
            try {
                addresses.add(InetAddress.getByName(host));
            } catch (UnknownHostException e) {
                String message = SelectorLoop.L10N.getString("err.unknown_host");
                message = MessageFormat.format(message, host);
                LOGGER.log(Level.SEVERE, message, e);
            }
        }
    }

    public List<ServerSocketChannel> getServerChannels() {
        return serverChannels;
    }

    void addServerChannel(ServerSocketChannel channel) {
        serverChannels.add(channel);
    }

    void closeServerChannels() throws IOException {
        for (Iterator<ServerSocketChannel> i = serverChannels.iterator(); i.hasNext(); ) {
            i.next().close();
        }
    }

    /**
     * Indicates whether connections created by this connector should use
     * transport layer security.
     */
    public boolean isSecure() {
        return secure;
    }

    /**
     * Sets whether connections created by this connector should use
     * transport layer security.
     * If you set this to true, you should also specify a key store for the
     * server certificate.
     * @param flag whether this connector should be secure
     */
    public void setSecure(boolean flag) {
        secure = flag;
    }

    public void setKeystoreFile(String file) {
        keystoreFile = file;
    }

    public void setKeystorePass(String pass) {
        keystorePass = pass;
    }

    public void setKeystoreFormat(String format) {
        keystoreFormat = format;
    }

    public void setNeedClientAuth(boolean flag) {
        needClientAuth = flag;
    }

    /**
     * Determines whether to accept a connection from the specified remote address.
     * This method is called for each incoming connection before resources are allocated.
     * Implementations can use this to implement IP filtering, rate limiting, or other
     * connection policies.
     * 
     * @param remoteAddress the remote socket address attempting to connect
     * @return true to accept the connection, false to reject it
     */
    public boolean acceptConnection(InetSocketAddress remoteAddress) {
        // Default implementation accepts all connections
        // Subclasses can override for specific filtering policies
        return true;
    }

    public void setCorePoolSize(int corePoolSize) {
        connectorThreadPool.setCorePoolSize(corePoolSize);
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        connectorThreadPool.setMaximumPoolSize(maximumPoolSize);
    }

    public String getKeepAlive() {
        TimeUnit timeUnit = TimeUnit.NANOSECONDS;
        long t = connectorThreadPool.getKeepAliveTime(timeUnit);
        if (t == 0L) {
            timeUnit = TimeUnit.MILLISECONDS;
        } else {
            if (t % 1000L == 0L) {
                timeUnit = TimeUnit.MICROSECONDS;
                t = t / 1000L;
            }
            if (t % 1000L == 0L) {
                timeUnit = TimeUnit.MILLISECONDS;
                t = t / 1000L;
            }
            if (t % 1000L == 0L) {
                timeUnit = TimeUnit.SECONDS;
                t = t / 1000L;
            }
            if (t % 60L == 0L) {
                timeUnit = TimeUnit.MINUTES;
                t = t / 60L;
            }
            if (t % 60L == 0L) {
                timeUnit = TimeUnit.HOURS;
                t = t / 60L;
            }
            if (t % 24L == 0L) {
                timeUnit = TimeUnit.DAYS;
                t = t / 24L;
            }
        }
        return new StringBuilder().append(t).append(TIME_UNITS.get(timeUnit)).toString();
    }

    public void setKeepAlive(String keepAlive) {
        String time = keepAlive;
        TimeUnit timeUnit = null;
        for (TimeUnit tu : TimeUnit.values()) {
            String suffix = TIME_UNITS.get(tu);
            if (time.endsWith(suffix)) {
                timeUnit = tu;
                time = time.substring(0, time.length() - suffix.length());
                break;
            }
        }
        if (timeUnit != null) {
            try {
                long keepAliveTime = Long.parseLong(time);
                connectorThreadPool.setKeepAliveTime(keepAliveTime, timeUnit);
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        String message = SelectorLoop.L10N.getString("err.bad_keepalive");
        message = MessageFormat.format(message, keepAlive);
        throw new IllegalArgumentException(message);
    }

    /**
     * Starts this connector.
     */
    protected void start() {
        // Validation: secure=true REQUIRES SSL configuration
        if (secure && (keystoreFile == null || keystorePass == null)) {
            String message = SelectorLoop.L10N.getString("err.no_keystore");
            throw new RuntimeException("Secure connector requires keystore configuration: " + message);
        }
        
        // Create SSL context if SSL configuration is provided (for secure=true or STARTTLS capability)
        if (keystoreFile != null && keystorePass != null) {
            try {
                KeyStore ks = KeyStore.getInstance(keystoreFormat);
                InputStream in = new FileInputStream(keystoreFile);
                char[] pass = keystorePass.toCharArray();
                ks.load(in, pass);
                in.close();
                KeyManagerFactory f = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                f.init(ks, pass);
                context = SSLContext.getInstance("TLS");
                KeyManager[] km = f.getKeyManagers();
                TrustManager[] tm = new TrustManager[1];
                tm[0] = new EmptyX509TrustManager();
                SecureRandom random = new SecureRandom();
                context.init(km, tm, random);
            } catch (Exception e) {
                RuntimeException e2 = new RuntimeException();
                e2.initCause(e);
                throw e2;
            }
        }
    }

    /**
     * Stops this connector.
     */
    protected void stop() {
        connectorThreadPool.shutdown();
    }

    /**
     * Returns a short (one word) description of this connector.
     */
    protected abstract String getDescription();

    /**
     * Returns the IP addresses this connector should be bound to.
     */
    protected Set<InetAddress> getAddresses() throws IOException {
        if (addresses == null) {
            addresses = new LinkedHashSet<>();
            for (Enumeration<NetworkInterface> e1 = NetworkInterface.getNetworkInterfaces(); e1.hasMoreElements(); ) {
                NetworkInterface ni = e1.nextElement();
                for (Enumeration<InetAddress> e2 = ni.getInetAddresses(); e2.hasMoreElements(); ) {
                    addresses.add(e2.nextElement());
                }
            }
        }
        return addresses;
    }

    /**
     * Returns the port number this factory should be bound to.
     */
    protected abstract int getPort();

    /**
     * ThreadFactory with connector naming strategy.
     */
    class ConnectorThreadFactory implements ThreadFactory {

        private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
        private long threadNum = 0L;

        @Override public Thread newThread(Runnable r) {
            Thread t = defaultFactory.newThread(r);
            t.setName(getDescription() + "-" + getPort() + "-" + (threadNum++));
            return t;
        }

    }

}
