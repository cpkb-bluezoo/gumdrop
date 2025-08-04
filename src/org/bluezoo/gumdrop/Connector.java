/*
 * Connector.java
 * Copyright (C) 2005, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop;

import org.bluezoo.gumdrop.util.EmptyX509TrustManager;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

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

    private List<ServerSocketChannel> serverChannels = new ArrayList<ServerSocketChannel>();
    private Set<String> addresses = null;

    private ExecutorService connectorThreadPool;
    private int threadPoolSize = -1;

    protected boolean secure = false;
    protected SSLContext context;
    protected String keystoreFile, keystorePass, keystoreFormat = "PKCS12";
    protected boolean needClientAuth = false;

    final Connection newConnection(SocketChannel sc, SelectionKey key) throws IOException {
        SSLEngine engine = null;
        if (secure) {
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
     * Configures the addresses this connector should bind to.
     */
    public void setAddresses(String value) {
        if (value == null) {
            addresses = null;
            return;
        }
        addresses = new LinkedHashSet<String>();
        StringTokenizer st = new StringTokenizer(value);
        while (st.hasMoreTokens()) {
            addresses.add(st.nextToken());
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

    public boolean isSecure() {
        return secure;
    }

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

    public boolean isNeedClientAuth() {
        return needClientAuth;
    }

    public void setNeedClientAuth(boolean flag) {
        needClientAuth = flag;
    }

    /**
     * Starts this connector.
     */
    protected void start() {
        if (secure || keystoreFile != null || keystorePass != null) {
            if (keystoreFile == null || keystorePass == null) {
                String message = Server.L10N.getString("err.no_keystore");
                throw new RuntimeException(message);
            }
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
        // Thread pool
        if (threadPoolSize > 0) {
            connectorThreadPool = Executors.newFixedThreadPool(threadPoolSize, this.new ConnectorThreadFactory());
        } else {
            connectorThreadPool = Executors.newCachedThreadPool(this.new ConnectorThreadFactory());
        }
    }

    /**
     * Stops this connector.
     */
    protected void stop() {
        if (connectorThreadPool != null) {
            connectorThreadPool.shutdown();
            connectorThreadPool = null;
        }
    }

    /**
     * Returns a short (one word) description of this connector.
     */
    protected abstract String getDescription();

    /**
     * Returns the IP addresses this connector should be bound to.
     */
    protected Set getAddresses() throws IOException {
        if (addresses == null) {
            addresses = new LinkedHashSet();
            for (Enumeration e1 = NetworkInterface.getNetworkInterfaces(); e1.hasMoreElements(); ) {
                NetworkInterface ni = (NetworkInterface) e1.nextElement();
                for (Enumeration e2 = ni.getInetAddresses(); e2.hasMoreElements(); ) {
                    InetAddress address = (InetAddress) e2.nextElement();
                    addresses.add(address.getHostAddress());
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
            t.setName("connector-" + getPort() + "-" + (threadNum++));
            return t;
        }

    }

}
