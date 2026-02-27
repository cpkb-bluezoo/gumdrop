/*
 * HostsFile.java
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

package org.bluezoo.gumdrop.dns.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Platform-aware parser for the system hosts file.
 *
 * <p>Provides hostname-to-address lookups from the local hosts file,
 * matching the precedence used by {@code getaddrinfo()}: hosts file
 * entries take priority over DNS.
 *
 * <p>This class is a shared singleton. The hosts file is parsed lazily
 * on first access and the result is cached in memory.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HostsFile {

    private static final Logger LOGGER = Logger.getLogger(HostsFile.class.getName());

    private static volatile Map<String, List<InetAddress>> entries;

    private HostsFile() {
    }

    /**
     * Looks up addresses for the given hostname in the hosts file.
     *
     * @param hostname the hostname to look up (case-insensitive)
     * @return the list of addresses, or null if not found
     */
    public static List<InetAddress> lookup(String hostname) {
        Map<String, List<InetAddress>> map = getEntries();
        return map.get(hostname.toLowerCase());
    }

    /**
     * Clears the cached entries, forcing a re-read on next access.
     */
    static void clear() {
        entries = null;
    }

    private static Map<String, List<InetAddress>> getEntries() {
        Map<String, List<InetAddress>> map = entries;
        if (map != null) {
            return map;
        }
        synchronized (HostsFile.class) {
            if (entries != null) {
                return entries;
            }
            entries = parse();
            return entries;
        }
    }

    private static Map<String, List<InetAddress>> parse() {
        Path path = locateHostsFile();
        if (path == null || !Files.exists(path)) {
            return Collections.emptyMap();
        }
        Map<String, List<InetAddress>> map = new HashMap<>();
        try {
            BufferedReader reader = Files.newBufferedReader(path);
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    int comment = line.indexOf('#');
                    if (comment >= 0) {
                        line = line.substring(0, comment);
                    }
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] parts = line.split("\\s+");
                    if (parts.length < 2) {
                        continue;
                    }
                    InetAddress address;
                    try {
                        byte[] addrBytes = parseAddress(parts[0]);
                        if (addrBytes == null) {
                            continue;
                        }
                        address = InetAddress.getByAddress(parts[0], addrBytes);
                    } catch (UnknownHostException e) {
                        continue;
                    }
                    for (int i = 1; i < parts.length; i++) {
                        String name = parts[i].toLowerCase();
                        List<InetAddress> list = map.get(name);
                        if (list == null) {
                            list = new ArrayList<>();
                            map.put(name, list);
                        }
                        list.add(address);
                    }
                }
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not read hosts file: " + path, e);
        }
        return Collections.unmodifiableMap(map);
    }

    private static byte[] parseAddress(String addr) {
        if (addr.indexOf(':') >= 0) {
            return parseIPv6(addr);
        }
        return parseIPv4(addr);
    }

    private static byte[] parseIPv4(String addr) {
        String[] parts = addr.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int val = Integer.parseInt(parts[i]);
                if (val < 0 || val > 255) {
                    return null;
                }
                bytes[i] = (byte) val;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return bytes;
    }

    private static byte[] parseIPv6(String addr) {
        try {
            InetAddress parsed = InetAddress.getByName(addr);
            return parsed.getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static Path locateHostsFile() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null) {
                systemRoot = "C:\\Windows";
            }
            return Paths.get(systemRoot, "System32", "drivers", "etc", "hosts");
        }
        return Paths.get("/etc/hosts");
    }

}
