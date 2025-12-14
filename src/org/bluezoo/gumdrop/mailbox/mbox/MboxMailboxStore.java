/*
 * MboxMailboxStore.java
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

package org.bluezoo.gumdrop.mailbox.mbox;

import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxAttribute;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Mail store implementation using mbox-format mailbox files.
 * 
 * <p>This implementation provides multi-folder mailbox support for IMAP
 * using mbox files. Each mailbox is a single file in the standard Unix
 * mbox format (RFC 4155).
 * 
 * <p>Directory structure:
 * <pre>
 * root/
 *   username/
 *     INBOX.mbox          (the INBOX mailbox)
 *     Sent.mbox           (the Sent mailbox)
 *     folder/             (directory for nested mailboxes)
 *       subfolder.mbox    (nested mailbox)
 *     .subscriptions      (list of subscribed mailboxes)
 * </pre>
 * 
 * <p>Only files with the configured extension (default: ".mbox") are
 * considered mailbox files. Directories are used only for hierarchy.
 * 
 * <p><b>Security:</b> All paths are sandboxed to prevent directory traversal
 * attacks. Paths containing ".." or absolute references are rejected.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MboxMailbox
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4155">RFC 4155 - The application/mbox Media Type</a>
 */
public class MboxMailboxStore implements MailboxStore {

    private static final Logger LOGGER = Logger.getLogger(MboxMailboxStore.class.getName());

    /** The default file extension for mbox files */
    public static final String DEFAULT_EXTENSION = ".mbox";

    /** The hierarchy delimiter for mailbox names */
    private static final char HIERARCHY_DELIMITER = '/';
    
    /** Pattern for valid mailbox name characters */
    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_.\\-]+$");
    
    /** File storing subscribed mailbox list */
    private static final String SUBSCRIPTIONS_FILE = ".subscriptions";
    
    /** The inbox mailbox name (case-insensitive matching) */
    private static final String INBOX = "INBOX";

    private final Path rootDirectory;
    private final String extension;
    
    private Path userDirectory;
    private String username;
    private Set<String> subscriptions;
    private boolean open;

    /**
     * Creates a new mbox mail store with default extension.
     * 
     * @param rootDirectory the root directory for all user mailboxes
     */
    public MboxMailboxStore(Path rootDirectory) {
        this(rootDirectory, DEFAULT_EXTENSION);
    }

    /**
     * Creates a new mbox mail store with custom extension.
     * 
     * @param rootDirectory the root directory for all user mailboxes
     * @param extension the file extension for mbox files (e.g., ".mbox")
     */
    public MboxMailboxStore(Path rootDirectory, String extension) {
        if (rootDirectory == null) {
            throw new IllegalArgumentException("Root directory cannot be null");
        }
        if (extension == null || extension.isEmpty()) {
            throw new IllegalArgumentException("Extension cannot be null or empty");
        }
        if (!extension.startsWith(".")) {
            extension = "." + extension;
        }
        this.rootDirectory = rootDirectory.toAbsolutePath().normalize();
        this.extension = extension;
        this.subscriptions = new HashSet<>();
        this.open = false;
    }

    /**
     * Creates a new mbox mail store.
     * 
     * @param rootDirectory the root directory for all user mailboxes
     */
    public MboxMailboxStore(File rootDirectory) {
        this(rootDirectory.toPath());
    }

    /**
     * Returns the file extension used for mbox files.
     * 
     * @return the extension (including leading dot)
     */
    public String getExtension() {
        return extension;
    }

    @Override
    public void open(String username) throws IOException {
        if (open) {
            throw new IOException("Store is already open");
        }
        
        // Sanitize username to prevent directory traversal
        String sanitized = sanitizePathComponent(username);
        if (sanitized.isEmpty()) {
            throw new IOException("Invalid username");
        }
        
        this.username = username;
        this.userDirectory = resolveSafePath(rootDirectory, sanitized);
        
        // Create user directory if it doesn't exist
        if (!Files.exists(userDirectory)) {
            Files.createDirectories(userDirectory);
        }
        
        if (!Files.isDirectory(userDirectory)) {
            throw new IOException("User path is not a directory: " + userDirectory);
        }
        
        // Ensure INBOX exists
        Path inboxPath = userDirectory.resolve(INBOX + extension);
        if (!Files.exists(inboxPath)) {
            Files.createFile(inboxPath);
        }
        
        // Load subscriptions
        loadSubscriptions();
        
        this.open = true;
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Opened mail store for user: " + username);
        }
    }

    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        
        // Save subscriptions
        saveSubscriptions();
        
        this.username = null;
        this.userDirectory = null;
        this.subscriptions.clear();
        this.open = false;
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Closed mail store");
        }
    }

    @Override
    public char getHierarchyDelimiter() {
        return HIERARCHY_DELIMITER;
    }

    @Override
    public List<String> listMailboxes(String reference, String pattern) throws IOException {
        ensureOpen();
        
        String fullPattern = reference + pattern;
        List<String> result = new ArrayList<>();
        
        // Handle empty pattern - return hierarchy delimiter info
        if (pattern.isEmpty()) {
            return result;
        }
        
        // Convert IMAP wildcards to regex
        String regex = convertWildcardToRegex(fullPattern);
        Pattern compiledPattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        
        // Scan for mbox files
        scanMailboxes(userDirectory, "", compiledPattern, result);
        
        // Sort results
        Collections.sort(result);
        
        return result;
    }

    /**
     * Scans for mailbox files using iterative depth-first traversal.
     * Decodes filesystem-encoded mailbox names back to Unicode.
     */
    private void scanMailboxes(Path directory, String prefix, Pattern pattern, List<String> result) {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return;
        }
        
        // Stack-based iterative traversal
        Deque<ScanState> stack = new ArrayDeque<>();
        stack.push(new ScanState(directory.toFile(), prefix));
        
        while (!stack.isEmpty()) {
            ScanState state = stack.pop();
            File[] children = state.directory.listFiles();
            if (children == null) {
                continue;
            }
            
            for (File child : children) {
                String fileName = child.getName();
                
                if (child.isFile() && fileName.endsWith(extension)) {
                    // This is a mailbox file - decode the name
                    String encodedName = fileName.substring(0, fileName.length() - extension.length());
                    String mailboxName = MailboxNameCodec.decode(encodedName);
                    String fullName = state.prefix.isEmpty() ? mailboxName : state.prefix + HIERARCHY_DELIMITER + mailboxName;
                    
                    if (pattern.matcher(fullName).matches()) {
                        result.add(fullName);
                    }
                } else if (child.isDirectory() && !fileName.startsWith(".")) {
                    // Push subdirectory onto stack for later processing
                    // Decode directory name back to mailbox name component
                    String decodedDir = MailboxNameCodec.decode(fileName);
                    String newPrefix = state.prefix.isEmpty() ? decodedDir : state.prefix + HIERARCHY_DELIMITER + decodedDir;
                    stack.push(new ScanState(child, newPrefix));
                }
            }
        }
    }

    /**
     * Checks if a directory contains any mailbox files.
     */
    private boolean hasMailboxChildren(File directory) {
        File[] children = directory.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (child.isFile() && child.getName().endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * State holder for mailbox scanning traversal.
     */
    private static class ScanState {
        final File directory;
        final String prefix;

        ScanState(File directory, String prefix) {
            this.directory = directory;
            this.prefix = prefix;
        }
    }

    @Override
    public List<String> listSubscribed(String reference, String pattern) throws IOException {
        ensureOpen();
        
        String fullPattern = reference + pattern;
        String regex = convertWildcardToRegex(fullPattern);
        Pattern compiledPattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        
        List<String> result = new ArrayList<>();
        for (String mailbox : subscriptions) {
            if (compiledPattern.matcher(mailbox).matches()) {
                result.add(mailbox);
            }
        }
        
        Collections.sort(result);
        return result;
    }

    @Override
    public void subscribe(String mailboxName) throws IOException {
        ensureOpen();
        String normalized = normalizeMailboxName(mailboxName);
        subscriptions.add(normalized);
        saveSubscriptions();
    }

    @Override
    public void unsubscribe(String mailboxName) throws IOException {
        ensureOpen();
        String normalized = normalizeMailboxName(mailboxName);
        subscriptions.remove(normalized);
        saveSubscriptions();
    }

    @Override
    public Mailbox openMailbox(String mailboxName, boolean readOnly) throws IOException {
        ensureOpen();
        
        String normalized = normalizeMailboxName(mailboxName);
        Path mailboxPath = resolveMailboxPath(normalized);
        
        if (!Files.exists(mailboxPath)) {
            throw new IOException("Mailbox does not exist: " + mailboxName);
        }
        
        if (!Files.isRegularFile(mailboxPath)) {
            throw new IOException("Mailbox path is not a file: " + mailboxName);
        }
        
        return new MboxMailbox(mailboxPath, normalized, readOnly);
    }

    @Override
    public void createMailbox(String mailboxName) throws IOException {
        ensureOpen();
        
        String normalized = normalizeMailboxName(mailboxName);
        
        // Don't allow creating INBOX (it always exists)
        if (INBOX.equalsIgnoreCase(normalized)) {
            throw new IOException("Cannot create INBOX");
        }
        
        Path mailboxPath = resolveMailboxPath(normalized);
        
        if (Files.exists(mailboxPath)) {
            throw new IOException("Mailbox already exists: " + mailboxName);
        }
        
        // Ensure parent directory exists
        Path parent = mailboxPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        
        // Create empty mbox file
        Files.createFile(mailboxPath);
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Created mailbox: " + normalized);
        }
    }

    @Override
    public void deleteMailbox(String mailboxName) throws IOException {
        ensureOpen();
        
        String normalized = normalizeMailboxName(mailboxName);
        
        // Don't allow deleting INBOX
        if (INBOX.equalsIgnoreCase(normalized)) {
            throw new IOException("Cannot delete INBOX");
        }
        
        Path mailboxPath = resolveMailboxPath(normalized);
        
        if (!Files.exists(mailboxPath)) {
            throw new IOException("Mailbox does not exist: " + mailboxName);
        }
        
        // Check if mailbox is empty (size == 0)
        if (Files.size(mailboxPath) > 0) {
            throw new IOException("Mailbox is not empty: " + mailboxName);
        }
        
        // Delete the file
        Files.delete(mailboxPath);
        
        // Remove from subscriptions
        subscriptions.remove(normalized);
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Deleted mailbox: " + normalized);
        }
    }

    @Override
    public void renameMailbox(String oldName, String newName) throws IOException {
        ensureOpen();
        
        String normalizedOld = normalizeMailboxName(oldName);
        String normalizedNew = normalizeMailboxName(newName);
        
        // Special case: renaming INBOX moves its contents but keeps INBOX
        boolean isInboxRename = INBOX.equalsIgnoreCase(normalizedOld);
        
        Path oldPath = resolveMailboxPath(normalizedOld);
        Path newPath = resolveMailboxPath(normalizedNew);
        
        if (!Files.exists(oldPath)) {
            throw new IOException("Source mailbox does not exist: " + oldName);
        }
        
        if (Files.exists(newPath)) {
            throw new IOException("Destination mailbox already exists: " + newName);
        }
        
        // Ensure parent directory exists
        Path newParent = newPath.getParent();
        if (newParent != null && !Files.exists(newParent)) {
            Files.createDirectories(newParent);
        }
        
        if (isInboxRename) {
            // Move contents of INBOX to new location, then recreate empty INBOX
            Files.move(oldPath, newPath);
            Files.createFile(oldPath);
        } else {
            Files.move(oldPath, newPath);
        }
        
        // Update subscriptions
        if (subscriptions.remove(normalizedOld)) {
            subscriptions.add(normalizedNew);
        }
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Renamed mailbox: " + normalizedOld + " -> " + normalizedNew);
        }
    }

    @Override
    public Set<MailboxAttribute> getMailboxAttributes(String mailboxName) throws IOException {
        ensureOpen();
        
        String normalized = normalizeMailboxName(mailboxName);
        Path mailboxPath = resolveMailboxPath(normalized);
        
        Set<MailboxAttribute> attributes = EnumSet.noneOf(MailboxAttribute.class);
        
        if (!Files.exists(mailboxPath)) {
            attributes.add(MailboxAttribute.NONEXISTENT);
            return attributes;
        }
        
        if (!Files.isRegularFile(mailboxPath)) {
            attributes.add(MailboxAttribute.NOSELECT);
            return attributes;
        }
        
        // Check for "child" mailboxes (files in a directory with same base name)
        Path parent = mailboxPath.getParent();
        String baseName = normalized.contains(String.valueOf(HIERARCHY_DELIMITER)) ?
            normalized.substring(normalized.lastIndexOf(HIERARCHY_DELIMITER) + 1) : normalized;
        Path childDir = parent.resolve(baseName);
        
        if (Files.isDirectory(childDir)) {
            boolean hasChildren = hasMailboxChildren(childDir.toFile());
            if (hasChildren) {
                attributes.add(MailboxAttribute.HASCHILDREN);
            } else {
                attributes.add(MailboxAttribute.HASNOCHILDREN);
            }
        } else {
            attributes.add(MailboxAttribute.HASNOCHILDREN);
        }
        
        return attributes;
    }

    @Override
    public String getQuotaRoot(String mailboxName) throws IOException {
        ensureOpen();
        // All mailboxes share the user's quota root
        return username;
    }

    @Override
    public Quota getQuota(String quotaRoot) throws IOException {
        ensureOpen();
        
        if (!username.equals(quotaRoot)) {
            return null;
        }
        
        // Calculate storage used
        long[] stats = calculateDirectoryStats(userDirectory, extension);
        
        final long storageUsed = stats[0] / 1024; // Convert to KB
        final long messageCount = stats[1];
        
        return new Quota() {
            @Override
            public String getRoot() {
                return username;
            }
            
            @Override
            public long getStorageUsed() {
                return storageUsed;
            }
            
            @Override
            public long getStorageLimit() {
                return -1; // Unlimited
            }
            
            @Override
            public long getMessageCount() {
                return messageCount;
            }
            
            @Override
            public long getMessageLimit() {
                return -1; // Unlimited
            }
        };
    }

    // ========================================================================
    // Private Helper Methods
    // ========================================================================

    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("Mail store is not open");
        }
    }

    /**
     * Normalizes a mailbox name.
     * INBOX is case-insensitive and normalized to uppercase.
     */
    private String normalizeMailboxName(String name) {
        if (name == null || name.isEmpty()) {
            return INBOX;
        }
        
        // Normalize INBOX to uppercase
        if (INBOX.equalsIgnoreCase(name)) {
            return INBOX;
        }
        
        // Normalize any path component that is INBOX
        String[] parts = name.split(String.valueOf(HIERARCHY_DELIMITER));
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                result.append(HIERARCHY_DELIMITER);
            }
            if (INBOX.equalsIgnoreCase(parts[i])) {
                result.append(INBOX);
            } else {
                result.append(parts[i]);
            }
        }
        
        return result.toString();
    }

    /**
     * Resolves a mailbox name to a file path.
     * Mailbox name components are encoded for filesystem safety.
     */
    private Path resolveMailboxPath(String mailboxName) throws IOException {
        String[] parts = mailboxName.split(String.valueOf(HIERARCHY_DELIMITER));
        
        Path current = userDirectory;
        for (int i = 0; i < parts.length - 1; i++) {
            // Encode the component for filesystem safety
            String encoded = MailboxNameCodec.encode(parts[i]);
            String sanitized = sanitizePathComponent(encoded);
            if (sanitized.isEmpty()) {
                throw new IOException("Invalid mailbox name component: " + parts[i]);
            }
            current = resolveSafePath(current, sanitized);
        }
        
        // Last component gets the extension
        String encoded = MailboxNameCodec.encode(parts[parts.length - 1]);
        String lastPart = sanitizePathComponent(encoded);
        if (lastPart.isEmpty()) {
            throw new IOException("Invalid mailbox name: " + mailboxName);
        }
        
        return resolveSafePath(current, lastPart + extension);
    }

    /**
     * Safely resolves a path, preventing directory traversal.
     */
    private Path resolveSafePath(Path base, String component) throws IOException {
        Path resolved = base.resolve(component).normalize();
        
        if (!resolved.startsWith(base)) {
            throw new IOException("Invalid path component (directory traversal attempt): " + component);
        }
        
        return resolved;
    }

    /**
     * Sanitizes a path component to prevent security issues.
     */
    private String sanitizePathComponent(String component) {
        if (component == null) {
            return "";
        }
        
        // Remove leading/trailing whitespace
        component = component.trim();
        
        // Reject dangerous patterns
        if (component.isEmpty() || component.equals(".") || component.equals("..")) {
            return "";
        }
        
        if (component.contains("/") || component.contains("\\") || 
            component.contains("\0") || component.contains(":")) {
            return "";
        }
        
        return component;
    }

    /**
     * Converts IMAP wildcard pattern to regex.
     */
    private String convertWildcardToRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        regex.append("^");
        
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*':
                    regex.append(".*");
                    break;
                case '%':
                    regex.append("[^").append(HIERARCHY_DELIMITER).append("]*");
                    break;
                case '.':
                case '(':
                case ')':
                case '[':
                case ']':
                case '{':
                case '}':
                case '\\':
                case '^':
                case '$':
                case '|':
                case '?':
                case '+':
                    regex.append("\\").append(c);
                    break;
                default:
                    regex.append(c);
            }
        }
        
        regex.append("$");
        return regex.toString();
    }

    /**
     * Loads subscriptions from file.
     */
    private void loadSubscriptions() throws IOException {
        subscriptions.clear();
        
        Path subFile = userDirectory.resolve(SUBSCRIPTIONS_FILE);
        if (Files.exists(subFile)) {
            try (BufferedReader reader = Files.newBufferedReader(subFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        subscriptions.add(line);
                    }
                }
            }
        }
        
        // Always subscribe to INBOX
        subscriptions.add(INBOX);
    }

    /**
     * Saves subscriptions to file.
     */
    private void saveSubscriptions() throws IOException {
        Path subFile = userDirectory.resolve(SUBSCRIPTIONS_FILE);
        
        try (BufferedWriter writer = Files.newBufferedWriter(subFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (String sub : subscriptions) {
                writer.write(sub);
                writer.newLine();
            }
        }
    }

    /**
     * Calculates storage statistics for a directory tree.
     * Uses iterative depth-first traversal with a stack.
     * Returns an array where [0] = total size in bytes, [1] = file count.
     */
    private long[] calculateDirectoryStats(Path directory, String fileExtension) throws IOException {
        long totalSize = 0L;
        long fileCount = 0L;
        
        Deque<File> stack = new ArrayDeque<>();
        stack.push(directory.toFile());
        
        while (!stack.isEmpty()) {
            File current = stack.pop();
            File[] children = current.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                if (child.isDirectory()) {
                    stack.push(child);
                } else if (child.isFile()) {
                    String name = child.getName();
                    if (name.endsWith(fileExtension)) {
                        totalSize += child.length();
                        fileCount++;
                    }
                }
            }
        }
        
        return new long[]{totalSize, fileCount};
    }
}
