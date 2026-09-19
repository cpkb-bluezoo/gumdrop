/*
 * ZoneFileWriter.java
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

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Writes a {@link MutableZone} to a BIND-style zone file using NIO.
 *
 * <p>Must run on a {@link org.bluezoo.gumdrop.StorageExecutor} worker thread,
 * not on a {@link org.bluezoo.gumdrop.SelectorLoop}.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ZoneFileWriter {

    private static final int BUFFER_SIZE = 8192;

    private ZoneFileWriter() {
    }

    static void writeAtomic(Path target, MutableZone zone) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = absolute.resolveSibling(absolute.getFileName().toString() + ".tmp");
        try (FileChannel channel = FileChannel.open(temp,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            appendLine(buffer, channel, "$ORIGIN " + zone.getOrigin());
            appendLine(buffer, channel, "$TTL " + zone.getDefaultTtl());
            List<String> owners = new ArrayList<String>(zone.ownerNames());
            Collections.sort(owners, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return a.compareTo(b);
                }
            });
            for (int i = 0; i < owners.size(); i++) {
                String owner = owners.get(i);
                List<DnsResourceRecord> records = zone.recordsAt(owner);
                for (int j = 0; j < records.size(); j++) {
                    appendLine(buffer, channel, formatRecord(zone, owner,
                            records.get(j)));
                }
            }
            flushBuffer(buffer, channel);
            channel.force(true);
        }
        Files.move(temp, absolute, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static void appendLine(ByteBuffer buffer, FileChannel channel, String line)
            throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        int offset = 0;
        while (offset < bytes.length) {
            if (!buffer.hasRemaining()) {
                flushBuffer(buffer, channel);
            }
            int chunk = Math.min(buffer.remaining(), bytes.length - offset);
            buffer.put(bytes, offset, chunk);
            offset += chunk;
        }
    }

    private static void flushBuffer(ByteBuffer buffer, FileChannel channel)
            throws IOException {
        buffer.flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        buffer.clear();
    }

    private static String formatRecord(MutableZone zone, String owner,
                                       DnsResourceRecord rr) {
        String origin = zone.getOrigin();
        int defaultTtl = zone.getDefaultTtl();
        String ownerToken = ownerToken(owner, origin);
        int ttl = rr.getTTL();
        StringBuilder sb = new StringBuilder();
        sb.append(ownerToken);
        if (ttl != defaultTtl) {
            sb.append(' ').append(ttl);
        }
        sb.append(" IN ").append(rr.getType().name());
        switch (rr.getType()) {
            case A:
                sb.append(' ').append(rr.getAddress().getHostAddress());
                break;
            case AAAA:
                sb.append(' ').append(rr.getAddress().getHostAddress());
                break;
            case NS:
                sb.append(' ').append(rr.getTargetName());
                break;
            case CNAME:
                sb.append(' ').append(rr.getTargetName());
                break;
            case PTR:
                sb.append(' ').append(rr.getTargetName());
                break;
            case MX:
                sb.append(' ').append(rr.getMXPreference()).append(' ')
                        .append(rr.getMXExchange());
                break;
            case TXT:
                sb.append(" \"").append(escapeTxt(rr.getText())).append('"');
                break;
            case SOA: {
                ZoneFile.SoaData soa = zone.getSoaData();
                sb.append(' ').append(soa.mname).append(' ')
                        .append(soa.rname).append(' ')
                        .append(soa.serial).append(' ')
                        .append(soa.refresh).append(' ')
                        .append(soa.retry).append(' ')
                        .append(soa.expire).append(' ')
                        .append(soa.minimum);
                break;
            }
            default:
                throw new IllegalArgumentException("unsupported type for export: "
                        + rr.getType());
        }
        return sb.toString();
    }

    private static String ownerToken(String owner, String origin) {
        if (owner.equals(origin)) {
            return "@";
        }
        if (owner.endsWith("." + origin)) {
            String rel = owner.substring(0, owner.length() - origin.length() - 1);
            return rel.isEmpty() ? "@" : rel;
        }
        return owner;
    }

    private static String escapeTxt(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
