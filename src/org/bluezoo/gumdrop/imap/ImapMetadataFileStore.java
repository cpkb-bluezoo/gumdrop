/*
 * ImapMetadataFileStore.java
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

package org.bluezoo.gumdrop.imap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * File-backed RFC 5464 metadata for one mail user.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ImapMetadataFileStore {

    static final int MAX_ENTRIES_PER_TARGET = 64;
    static final int MAX_VALUE_BYTES = 64 * 1024;

    private final Path root;

    public ImapMetadataFileStore(Path userDirectory) {
        if (userDirectory == null) {
            throw new IllegalArgumentException("userDirectory");
        }
        this.root = userDirectory.resolve(".imap-metadata");
    }

    public String get(String mailboxName, String entryName) throws IOException {
        String key = ImapMetadataEntryNames.canonicalEntryName(entryName);
        Properties props = loadProperties(propertiesPath(mailboxName,
                ImapMetadataScope.fromEntryName(entryName)));
        return props.getProperty(key);
    }

    public Map<String, String> getEntries(String mailboxName,
            List<String> entryNames) throws IOException {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (String entryName : entryNames) {
            String value = get(mailboxName, entryName);
            if (value != null) {
                result.put(entryName, value);
            }
        }
        return result;
    }

    public Map<String, String> listWithDepth(String mailboxName,
            String prefixEntry, int depth) throws IOException {
        Map<String, String> all = loadAllForMailbox(mailboxName);
        String prefix = ImapMetadataEntryNames.canonicalEntryName(prefixEntry);
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : all.entrySet()) {
            String name = e.getKey();
            if (!name.equals(prefix) && !name.startsWith(prefix + "/")) {
                continue;
            }
            if (!name.equals(prefix) && depth == 0) {
                continue;
            }
            if (!name.equals(prefix) && depth == 1) {
                String rest = name.substring(prefix.length() + 1);
                if (rest.contains("/")) {
                    continue;
                }
            }
            result.put(name, e.getValue());
        }
        return result;
    }

    public void set(String mailboxName, String entryName, String value)
            throws IOException {
        if (ImapMetadataEntryNames.isReadOnly(entryName)) {
            throw new IOException("read-only");
        }
        ImapMetadataScope scope = ImapMetadataScope.fromEntryName(entryName);
        if (scope == null) {
            throw new IOException("invalid entry");
        }
        String key = ImapMetadataEntryNames.canonicalEntryName(entryName);
        Path path = propertiesPath(mailboxName, scope);
        Properties props = loadProperties(path);
        if (value == null) {
            props.remove(key);
        } else {
            if (value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
                throw new IOException("too large");
            }
            if (!props.containsKey(key) && props.size() >= MAX_ENTRIES_PER_TARGET) {
                throw new IOException("too many");
            }
            props.setProperty(key, value);
        }
        saveProperties(path, props);
    }

    public void deleteMailbox(String mailboxName) throws IOException {
        Path dir = mailboxDirectory(mailboxName);
        if (Files.exists(dir)) {
            deleteTree(dir);
        }
    }

    public void renameMailbox(String oldName, String newName) throws IOException {
        Path oldDir = mailboxDirectory(oldName);
        Path newDir = mailboxDirectory(newName);
        if (Files.exists(oldDir)) {
            Files.createDirectories(newDir.getParent());
            Files.move(oldDir, newDir);
        }
    }

    private Map<String, String> loadAllForMailbox(String mailboxName)
            throws IOException {
        Map<String, String> merged = new LinkedHashMap<String, String>();
        mergeProps(merged, loadProperties(propertiesPath(mailboxName,
                ImapMetadataScope.SHARED)));
        mergeProps(merged, loadProperties(propertiesPath(mailboxName,
                ImapMetadataScope.PRIVATE)));
        return merged;
    }

    private static void mergeProps(Map<String, String> target, Properties props) {
        for (String name : props.stringPropertyNames()) {
            target.put(name, props.getProperty(name));
        }
    }

    private Path propertiesPath(String mailboxName, ImapMetadataScope scope)
            throws IOException {
        String scopeName = scope == ImapMetadataScope.SHARED
                ? "shared.properties" : "private.properties";
        if (mailboxName == null || mailboxName.isEmpty()) {
            return root.resolve("server").resolve(scopeName);
        }
        return mailboxDirectory(mailboxName).resolve(scopeName);
    }

    private Path mailboxDirectory(String mailboxName) throws IOException {
        String encoded = encodeMailboxName(mailboxName);
        return root.resolve("mailbox").resolve(encoded);
    }

    static String encodeMailboxName(String mailboxName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mailboxName.length(); i++) {
            char c = mailboxName.charAt(i);
            if (c == '/' || c == '\\' || c == '%' || c == ':') {
                sb.append('%');
                sb.append(String.format("%02x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Properties loadProperties(Path path) throws IOException {
        Properties props = new Properties();
        if (!Files.exists(path)) {
            return props;
        }
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        }
        return props;
    }

    private void saveProperties(Path path, Properties props) throws IOException {
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path)) {
            props.store(out, "gumdrop IMAP METADATA");
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
                for (Path child : children) {
                    deleteTree(child);
                }
            }
        }
        Files.deleteIfExists(dir);
    }

    public Set<String> allEntryNames(String mailboxName) throws IOException {
        return new TreeSet<String>(loadAllForMailbox(mailboxName).keySet());
    }
}
