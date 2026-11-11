/*
 * ClusterRedirect.java
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

package org.bluezoo.gumdrop.redis.client;

/**
 * A {@code MOVED} or {@code ASK} error reply, which sends a command to
 * another node of a Redis Cluster.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ClusterRedirect {

    private final boolean ask;
    private final int slot;
    private final String host;
    private final int port;

    private ClusterRedirect(boolean ask, int slot, String host, int port) {
        this.ask = ask;
        this.slot = slot;
        this.host = host;
        this.port = port;
    }

    /** True for {@code ASK}, false for {@code MOVED}. */
    boolean isAsk() {
        return ask;
    }

    int getSlot() {
        return slot;
    }

    /** The target host, or null if the reply left it blank (the replying node's own). */
    String getHost() {
        return host;
    }

    int getPort() {
        return port;
    }

    /**
     * Parses an error message such as {@code MOVED 3999 127.0.0.1:6381}.
     *
     * @return the redirect, or null if the message is not a well-formed one
     */
    static ClusterRedirect parse(String error) {
        if (error == null) {
            return null;
        }
        String[] parts = error.trim().split("\\s+");
        if (parts.length != 3) {
            return null;
        }
        boolean ask;
        if (parts[0].equals("MOVED")) {
            ask = false;
        } else if (parts[0].equals("ASK")) {
            ask = true;
        } else {
            return null;
        }
        int slot;
        try {
            slot = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (slot < 0 || slot >= ClusterSlots.SLOT_COUNT) {
            return null;
        }
        // the port follows the last colon, so an IPv6 host may contain colons
        int colon = parts[2].lastIndexOf(':');
        if (colon < 0) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(parts[2].substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        String host = parts[2].substring(0, colon);
        if (host.length() >= 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            host = host.substring(1, host.length() - 1);
        }
        return new ClusterRedirect(ask, slot, host.isEmpty() ? null : host, port);
    }
}
