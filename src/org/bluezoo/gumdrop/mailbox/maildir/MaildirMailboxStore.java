/*
 * MaildirMailboxStore.java
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

package org.bluezoo.gumdrop.mailbox.maildir;

import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxNameCodec;
import org.bluezoo.gumdrop.mailbox.MailboxStore;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mail store implementation using Maildir++ format.
 * 
 * <p>This implementation provides multi-folder mailbox support for IMAP
 * using the Maildir++ format. Each mailbox is a directory containing
 * {@code cur/}, {@code new/}, and {@code tmp/} subdirectories.
 * 
 * <p>Directory structure (Maildir++):
 * <pre>
 * root/
 *   username/
 *     cur/               (INBOX messages)
 *     new/               (INBOX new messages)
 *     tmp/               (INBOX temp files)
 *     .Sent/
 *       cur/ new/ tmp/   (Sent folder)
 *     .Drafts/
 *       cur/ new/ tmp/   (Drafts folder)
 *     .folder.subfolder/
 *       cur/ new/ tmp/   (nested folder)
 *     .subscriptions     (subscribed mailboxes)
 * </pre>
 * 
 * <p>The hierarchy delimiter is "/" but folder directories use "." prefix
 * and "." as separator (Maildir++ convention).
 * 
 * <p><b>Security:</b> All paths are sandboxed to prevent directory traversal
 * attacks. Paths containing ".." or absolute references are rejected.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MaildirMailbox
 * @see <a href="https://en.wikipedia.org/wiki/Maildir">Maildir on Wikipedia</a>
 */
public class MaildirMailboxStore implements MailboxStore {

    private static final Logger LOGGER = Logger.getLogger(MaildirMailboxStore.class.getName());

    /** The hierarchy delimiter for mailbox names (IMAP visible) */
    private static final char HIERARCHY_DELIMITER = '/';
    
    /** The folder prefix for Maildir++ subfolders */
    private static final char MAILDIR_FOLDER_PREFIX = '.';
    
    /** File storing subscribed mailbox list */
    private static final String SUBSCRIPTIONS_FILE = ".subscriptions";
    
    /** The inbox mailbox name (case-insensitive matching) */
    private static final String INBOX = "INBOX";

    private final Path rootDirectory;
    
    private Path userDirectory;
    private String username;
    private Set<String> subscriptions;
    private boolean open;

    /**
     * Creates a new Maildir mail store.
     * 
     * @param rootDirectory the root directory for all user mailboxes
     */
    public MaildirMailboxStore(Path rootDirectory) {
        if (rootDirectory == null) {
            throw new IllegalArgumentException("Root directory cannot be null");
        }
        this.rootDirectory = rootDirectory;
        this.open = false;
    }

    @Override
    public void open(String username) throws IOException {
        if (username == null || username.isEmpty()) {
            throw new IllegalArgumentException("Username cannot be null or empty");
        }
        
        // Validate username - no path separators or special characters
        if (username.contains("/") || username.contains("\\") || 
            username.contains("..") || username.startsWith(".")) {
            throw new IOException("Invalid username: " + username);
        }

        this.username = username;
        this.userDirectory = rootDirectory.resolve(username);

        // Create user directory and INBOX structure if needed
        Path inboxCur = userDirectory.resolve("cur");
        Path inboxNew = userDirectory.resolve("new");
        Path inboxTmp = userDirectory.resolve("tmp");
        
        Files.createDirectories(inboxCur);
        Files.createDirectories(inboxNew);
        Files.createDirectories(inboxTmp);

        // Load subscriptions
        loadSubscriptions();

        this.open = true;
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Opened Maildir store for user: " + username);
        }
    }

    @Override
    public void close() throws IOException {
        if (open) {
            saveSubscriptions();
            open = false;
            username = null;
            userDirectory = null;
            subscriptions = null;
        }
    }

    /**
     * Ensures the store is open.
     */
    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("Mail store is not open");
        }
    }

    @Override
    public char getHierarchyDelimiter() {
        return HIERARCHY_DELIMITER;
    }

    /**
     * Converts an IMAP mailbox name to a Maildir++ directory name.
     * INBOX -> (root)
     * Sent -> .Sent
     * folder/subfolder -> .folder.subfolder
     * 
     * <p>Mailbox name components are encoded for filesystem safety using
     * {@link MailboxNameCodec} before being combined into the directory name.
     */
    private String mailboxToDirectoryName(String mailboxName) {
        if (mailboxName.equalsIgnoreCase(INBOX)) {
            return ""; // INBOX is the root Maildir
        }
        
        // Split by hierarchy delimiter, encode each component, then join with Maildir++ separator
        String[] parts = mailboxName.split(String.valueOf(HIERARCHY_DELIMITER));
        StringBuilder result = new StringBuilder();
        result.append(MAILDIR_FOLDER_PREFIX);
        
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                result.append(MAILDIR_FOLDER_PREFIX);
            }
            // Encode each component for filesystem safety
            result.append(MailboxNameCodec.encode(parts[i]));
        }
        
        return result.toString();
    }

    /**
     * Converts a Maildir++ directory name to an IMAP mailbox name.
     * (root) -> INBOX
     * .Sent -> Sent
     * .folder.subfolder -> folder/subfolder
     * 
     * <p>Directory name components are decoded from filesystem-safe encoding
     * using {@link MailboxNameCodec}.
     */
    private String directoryToMailboxName(String dirName) {
        if (dirName.isEmpty()) {
            return INBOX;
        }
        
        // Remove leading dot
        if (dirName.startsWith(".")) {
            dirName = dirName.substring(1);
        }
        
        // Split by Maildir++ separator, decode each component, then join with hierarchy delimiter
        String[] parts = dirName.split("\\.");
        StringBuilder result = new StringBuilder();
        
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                result.append(HIERARCHY_DELIMITER);
            }
            // Decode each component from filesystem encoding
            result.append(MailboxNameCodec.decode(parts[i]));
        }
        
        return result.toString();
    }

    /**
     * Resolves the path to a mailbox directory.
     */
    private Path resolveMailboxPath(String mailboxName) throws IOException {
        String dirName = mailboxToDirectoryName(mailboxName);
        Path mailboxPath;
        
        if (dirName.isEmpty()) {
            mailboxPath = userDirectory;
        } else {
            mailboxPath = userDirectory.resolve(dirName);
        }
        
        // Security check - ensure path is within user directory
        Path normalized = mailboxPath.normalize();
        if (!normalized.startsWith(userDirectory)) {
            throw new IOException("Invalid mailbox path: " + mailboxName);
        }
        
        return normalized;
    }

    /**
     * Checks if a directory is a valid Maildir (has cur, new, tmp).
     */
    private boolean isValidMaildir(Path path) {
        if (!Files.isDirectory(path)) {
            return false;
        }
        return Files.isDirectory(path.resolve("cur")) &&
               Files.isDirectory(path.resolve("new")) &&
               Files.isDirectory(path.resolve("tmp"));
    }

    @Override
    public List<String> listMailboxes(String reference, String pattern) throws IOException {
        ensureOpen();
        
        String fullPattern = reference + pattern;
        List<String> result = new ArrayList<>();

        // Use stack-based iteration to find mailboxes
        Deque<ScanState> stack = new ArrayDeque<>();
        stack.push(new ScanState(userDirectory.toFile(), ""));

        while (!stack.isEmpty()) {
            ScanState state = stack.pop();
            File[] children = state.directory.listFiles();
            if (children == null) {
                continue;
            }

            for (File child : children) {
                String fileName = child.getName();
                
                if (child.isDirectory()) {
                    // Check if this is a Maildir++ subfolder (starts with .)
                    if (fileName.startsWith(".") && !fileName.equals(".") && !fileName.equals("..")) {
                        // Skip special files
                        if (fileName.equals(".subscriptions") || fileName.equals(".uidlist") || 
                            fileName.equals(".keywords")) {
                            continue;
                        }
                        
                        Path maildirPath = child.toPath();
                        if (isValidMaildir(maildirPath)) {
                            String mailboxName = directoryToMailboxName(fileName);
                            if (matchesPattern(mailboxName, fullPattern)) {
                                result.add(mailboxName);
                            }
                        }
                    } else if (state.prefix.isEmpty() && 
                               (fileName.equals("cur") || fileName.equals("new") || fileName.equals("tmp"))) {
                        // This is INBOX
                        if (matchesPattern(INBOX, fullPattern)) {
                            if (!result.contains(INBOX)) {
                                result.add(INBOX);
                            }
                        }
                    }
                }
            }
        }

        // Sort results
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        
        return result;
    }

    /**
     * Matches a mailbox name against an IMAP pattern.
     * Supports * (any chars) and % (any chars except delimiter).
     */
    private boolean matchesPattern(String name, String pattern) {
        return matchesPatternRecursive(name, 0, pattern, 0);
    }

    private boolean matchesPatternRecursive(String name, int nameIdx, String pattern, int patIdx) {
        while (patIdx < pattern.length()) {
            char pc = pattern.charAt(patIdx);
            
            if (pc == '*') {
                // * matches any sequence including delimiter
                patIdx++;
                if (patIdx >= pattern.length()) {
                    return true; // * at end matches everything
                }
                for (int i = nameIdx; i <= name.length(); i++) {
                    if (matchesPatternRecursive(name, i, pattern, patIdx)) {
                        return true;
                    }
                }
                return false;
                
            } else if (pc == '%') {
                // % matches any sequence except delimiter
                patIdx++;
                if (patIdx >= pattern.length()) {
                    // % at end - check no more delimiters
                    for (int i = nameIdx; i < name.length(); i++) {
                        if (name.charAt(i) == HIERARCHY_DELIMITER) {
                            return false;
                        }
                    }
                    return true;
                }
                for (int i = nameIdx; i <= name.length(); i++) {
                    if (i > nameIdx && name.charAt(i - 1) == HIERARCHY_DELIMITER) {
                        break; // Can't match past delimiter
                    }
                    if (matchesPatternRecursive(name, i, pattern, patIdx)) {
                        return true;
                    }
                }
                return false;
                
            } else {
                if (nameIdx >= name.length()) {
                    return false;
                }
                char nc = name.charAt(nameIdx);
                // Case-insensitive for INBOX, case-sensitive otherwise
                if (Character.toLowerCase(nc) != Character.toLowerCase(pc)) {
                    return false;
                }
                nameIdx++;
                patIdx++;
            }
        }
        
        return nameIdx >= name.length();
    }

    // -- Subscriptions --

    private void loadSubscriptions() throws IOException {
        subscriptions = new HashSet<>();
        Path subscriptionsPath = userDirectory.resolve(SUBSCRIPTIONS_FILE);
        
        if (!Files.exists(subscriptionsPath)) {
            // Auto-subscribe to INBOX
            subscriptions.add(INBOX);
            return;
        }

        BufferedReader reader = Files.newBufferedReader(subscriptionsPath, StandardCharsets.UTF_8);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    subscriptions.add(line);
                }
            }
        } finally {
            reader.close();
        }
    }

    private void saveSubscriptions() throws IOException {
        if (subscriptions == null) {
            return;
        }
        
        Path subscriptionsPath = userDirectory.resolve(SUBSCRIPTIONS_FILE);
        
        BufferedWriter writer = Files.newBufferedWriter(subscriptionsPath, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            List<String> sorted = new ArrayList<>(subscriptions);
            Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
            for (String mailbox : sorted) {
                writer.write(mailbox);
                writer.newLine();
            }
        } finally {
            writer.close();
        }
    }

    @Override
    public List<String> listSubscribed(String reference, String pattern) throws IOException {
        ensureOpen();
        
        String fullPattern = reference + pattern;
        List<String> result = new ArrayList<>();
        
        for (String mailbox : subscriptions) {
            if (matchesPattern(mailbox, fullPattern)) {
                result.add(mailbox);
            }
        }
        
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    @Override
    public void subscribe(String mailboxName) throws IOException {
        ensureOpen();
        subscriptions.add(normalizeMailboxName(mailboxName));
    }

    @Override
    public void unsubscribe(String mailboxName) throws IOException {
        ensureOpen();
        subscriptions.remove(normalizeMailboxName(mailboxName));
    }

    /**
     * Normalizes a mailbox name (INBOX is case-insensitive).
     */
    private String normalizeMailboxName(String name) {
        if (name.equalsIgnoreCase(INBOX)) {
            return INBOX;
        }
        return name;
    }

    @Override
    public Mailbox openMailbox(String mailboxName, boolean readOnly) throws IOException {
        ensureOpen();
        
        String normalized = normalizeMailboxName(mailboxName);
        Path mailboxPath = resolveMailboxPath(normalized);
        
        if (!isValidMaildir(mailboxPath)) {
            throw new IOException("Mailbox does not exist: " + mailboxName);
        }
        
        return new MaildirMailbox(mailboxPath, normalized, readOnly);
    }

    @Override
    public void createMailbox(String mailboxName) throws IOException {
        ensureOpen();
        
        String normalized = normalizeMailboxName(mailboxName);
        if (normalized.equals(INBOX)) {
            throw new IOException("Cannot create INBOX");
        }
        
        Path mailboxPath = resolveMailboxPath(normalized);
        
        if (Files.exists(mailboxPath)) {
            throw new IOException("Mailbox already exists: " + mailboxName);
        }
        
        // Create Maildir structure
        Files.createDirectories(mailboxPath.resolve("cur"));
        Files.createDirectories(mailboxPath.resolve("new"));
        Files.createDirectories(mailboxPath.resolve("tmp"));
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Created mailbox: " + mailboxName);
        }
    }

    @Override
    public void deleteMailbox(String mailboxName) throws IOException {
        ensureOpen();
        
        String normalized = normalizeMailboxName(mailboxName);
        if (normalized.equals(INBOX)) {
            throw new IOException("Cannot delete INBOX");
        }
        
        Path mailboxPath = resolveMailboxPath(normalized);
        
        if (!Files.exists(mailboxPath)) {
            throw new IOException("Mailbox does not exist: " + mailboxName);
        }
        
        // Check if mailbox is empty
        Path curPath = mailboxPath.resolve("cur");
        File curDir = curPath.toFile();
        File[] messages = curDir.listFiles();
        if (messages != null && messages.length > 0) {
            throw new IOException("Mailbox is not empty: " + mailboxName);
        }
        
        // Delete Maildir structure
        deleteDirectory(mailboxPath.toFile());
        
        // Remove from subscriptions
        subscriptions.remove(normalized);
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Deleted mailbox: " + mailboxName);
        }
    }

    /**
     * Recursively deletes a directory.
     */
    private void deleteDirectory(File dir) throws IOException {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteDirectory(child);
                } else {
                    if (!child.delete()) {
                        throw new IOException("Failed to delete file: " + child);
                    }
                }
            }
        }
        if (!dir.delete()) {
            throw new IOException("Failed to delete directory: " + dir);
        }
    }

    @Override
    public void renameMailbox(String oldName, String newName) throws IOException {
        ensureOpen();
        
        String normalizedOld = normalizeMailboxName(oldName);
        String normalizedNew = normalizeMailboxName(newName);
        
        if (normalizedOld.equals(INBOX)) {
            throw new IOException("Cannot rename INBOX");
        }
        if (normalizedNew.equals(INBOX)) {
            throw new IOException("Cannot rename to INBOX");
        }
        
        Path oldPath = resolveMailboxPath(normalizedOld);
        Path newPath = resolveMailboxPath(normalizedNew);
        
        if (!Files.exists(oldPath)) {
            throw new IOException("Mailbox does not exist: " + oldName);
        }
        if (Files.exists(newPath)) {
            throw new IOException("Mailbox already exists: " + newName);
        }
        
        Files.move(oldPath, newPath);
        
        // Update subscriptions
        if (subscriptions.remove(normalizedOld)) {
            subscriptions.add(normalizedNew);
        }
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Renamed mailbox: " + normalizedOld + " -> " + normalizedNew);
        }
    }

    @Override
    public Set<String> getMailboxAttributes(String mailboxName) throws IOException {
        ensureOpen();
        
        Set<String> attributes = new HashSet<>();
        String normalized = normalizeMailboxName(mailboxName);
        Path mailboxPath = resolveMailboxPath(normalized);
        
        if (!isValidMaildir(mailboxPath)) {
            attributes.add("Noselect");
            return attributes;
        }
        
        // Check for children (other .folder* directories)
        boolean hasChildren = false;
        String dirName = mailboxToDirectoryName(normalized);
        String prefix = dirName.isEmpty() ? "." : dirName + ".";
        
        File userDir = userDirectory.toFile();
        File[] children = userDir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && child.getName().startsWith(prefix)) {
                    if (isValidMaildir(child.toPath())) {
                        hasChildren = true;
                        break;
                    }
                }
            }
        }
        
        if (hasChildren) {
            attributes.add("HasChildren");
        } else {
            attributes.add("HasNoChildren");
        }
        
        return attributes;
    }

    @Override
    public String getQuotaRoot(String mailboxName) throws IOException {
        ensureOpen();
        return username; // User-level quota
    }

    @Override
    public Quota getQuota(String quotaRoot) throws IOException {
        ensureOpen();
        
        if (!quotaRoot.equals(username)) {
            return null;
        }
        
        // Calculate storage usage
        long[] stats = calculateStorageUsage();
        final long totalSize = stats[0];
        final long messageCount = stats[1];
        
        return new Quota() {
            @Override
            public String getRoot() {
                return username;
            }
            
            @Override
            public long getStorageUsed() {
                return totalSize / 1024; // Convert to KB
            }
            
            @Override
            public long getStorageLimit() {
                return -1; // No limit
            }
            
            @Override
            public long getMessageCount() {
                return messageCount;
            }
            
            @Override
            public long getMessageLimit() {
                return -1; // No limit
            }
        };
    }

    /**
     * Calculates total storage usage for the user.
     * Returns [total_bytes, message_count].
     */
    private long[] calculateStorageUsage() {
        long totalSize = 0;
        long messageCount = 0;
        
        Deque<File> stack = new ArrayDeque<>();
        stack.push(userDirectory.toFile());
        
        while (!stack.isEmpty()) {
            File current = stack.pop();
            File[] children = current.listFiles();
            if (children == null) {
                continue;
            }
            
            for (File child : children) {
                if (child.isDirectory()) {
                    String name = child.getName();
                    if (name.equals("cur") || name.equals("new")) {
                        // Count messages in cur and new directories
                        File[] messages = child.listFiles();
                        if (messages != null) {
                            for (File msg : messages) {
                                if (msg.isFile() && !msg.getName().startsWith(".")) {
                                    totalSize += msg.length();
                                    messageCount++;
                                }
                            }
                        }
                    } else if (!name.equals("tmp")) {
                        stack.push(child);
                    }
                }
            }
        }
        
        return new long[]{totalSize, messageCount};
    }

    /**
     * State holder for directory scanning.
     */
    private static class ScanState {
        final File directory;
        final String prefix;

        ScanState(File directory, String prefix) {
            this.directory = directory;
            this.prefix = prefix;
        }
    }

}

