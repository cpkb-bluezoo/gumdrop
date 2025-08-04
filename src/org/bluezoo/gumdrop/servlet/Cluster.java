/*
 * Cluster.java
 * Copyright (C) 2005 Chris Burdess
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

package org.bluezoo.gumdrop.servlet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * This component is used to discover other gumdrop nodes on the network.
 * It is the basis for routing distributed sessions between nodes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class Cluster extends Thread {

    final Container container;
    final InetAddress local;
    final InetAddress group;
    final int port;
    final MulticastSocket multicaster;
    final Map<InetAddress, Long> nodes;

    Cluster(Container container) throws IOException {
        super("cluster");
        this.container = container;
        setDaemon(true);

        // Interface to be considered local
        InetAddress localhost = InetAddress.getLocalHost();
        InetAddress[] locals = InetAddress.getAllByName(localhost.getHostName());
        InetAddress l = null;
        for (int i = 0; i < locals.length; i++) {
            // Get first non-IPv6 address
            if (locals[i].getHostAddress().indexOf(':') == -1) // TODO IPv6
            {
                l = locals[i];
                break;
            }
        }
        if (l == null) {
            l = localhost;
        }
        local = l;

        // Multicaster
        port = container.clusterPort;
        group = InetAddress.getByName(container.clusterGroupAddress);
        multicaster = new MulticastSocket(port);
        multicaster.joinGroup(group);
        multicaster.setSoTimeout(5000);
        nodes = new LinkedHashMap<InetAddress, Long>();
    }

    public void run() {
        try {
            while (!isInterrupted()) {
                // ping
                ping();

                // receive
                int len = Math.min(1024, multicaster.getReceiveBufferSize());
                byte[] buf = new byte[len];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    while (!isInterrupted()) {
                        multicaster.receive(packet);
                        InetAddress remote = packet.getAddress();
                        if (!remote.equals(local)) {
                            // Receive session
                            len = packet.getLength();
                            if (len > 0) {
                                int off = packet.getOffset();
                                byte[] data = packet.getData();
                                receive(data, off, len);
                            }
                            // Have we heard from this node before?
                            if (!nodes.containsKey(remote)) {
                                replicateAll();
                            }
                            // Update cluster membership
                            nodes.put(remote, Long.valueOf(System.currentTimeMillis()));
                        }
                    }
                } catch (SocketTimeoutException e) {
                    // Fall through
                }
                // Reap expired nodes
                long now = System.currentTimeMillis();
                Collection<InetAddress> addrs = new ArrayList<InetAddress>(nodes.keySet());
                for (InetAddress remote : addrs) {
                    long t = nodes.get(remote);
                    if (now - t > 15000) {
                        nodes.remove(remote);
                    }
                }
            }
            multicaster.leaveGroup(group);
        } catch (IOException e) {
            Context.LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    synchronized void ping() throws IOException {
        byte[] buf = new byte[0];
        DatagramPacket packet = new DatagramPacket(buf, buf.length, group, port);
        multicaster.send(packet);
    }

    synchronized void replicate(Context context, Session session) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        sink.write(context.digest);
        ObjectOutputStream oo = new ObjectOutputStream(sink);
        oo.writeObject(session);
        oo.flush();
        byte[] buf = sink.toByteArray();
        DatagramPacket packet = new DatagramPacket(buf, buf.length, group, port);
        multicaster.send(packet);
    }

    void receive(byte[] buf, int off, int len) throws IOException {
        byte[] digest = new byte[16]; // XXX MD5
        ByteArrayInputStream in = new ByteArrayInputStream(buf, off, len);
        in.read(digest, 0, digest.length);
        ObjectInputStream oi = new ObjectInputStream(in);
        try {
            Session session = (Session) oi.readObject();
            Context context = container.getContextByDigest(digest);
            if (context != null) {
                synchronized (context) {
                    context.sessions.put(session.id, session);
                    // notify listeners?
                }
            }
        } catch (ClassNotFoundException e) {
            IOException e2 = new IOException(e.getMessage());
            e2.initCause(e);
            throw e2;
        }
    }

    void replicateAll() throws IOException {
        for (Context context : container.contexts) {
            synchronized (context.sessions) {
                for (Session session : context.sessions.values()) {
                    replicate(context, session);
                }
            }
        }
    }

}
