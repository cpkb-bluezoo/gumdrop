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
import org.bluezoo.gumdrop.mailbox.MailboxRuntime;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.index.MailboxIndexKey;
import org.bluezoo.gumdrop.mailbox.index.MailboxIndexer;
import org.bluezoo.gumdrop.mailbox.index.MailboxWatcher;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.mailbox.L10N");

    /** The default file extension for mbox files */
    public static final String DEFAULT_EXTENSION = ".mbox";

    /** The hierarchy delimiter for mailbox names */
    private static final char HIERARCHY_DELIMITER = '/';
    
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

        // Eagerly warm search indexes for this user's mailboxes in the
        // background rather than only rebuilding lazily on first SELECT
        // (issue #163). Submitted unconditionally for every mailbox: a
        // mailbox whose index is already current is cheap to confirm
        // (loadOrBuildSearchIndex()'s fast incremental path), so no
        // separate staleness pre-check is needed here.
        enqueueEagerIndexWarming();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("fine.opened_mbox_store"), username));
        }
    }

    private void enqueueEagerIndexWarming() {
        MailboxIndexer indexer = MailboxRuntime.getIndexer();
        if (indexer == null) {
            return;
        }
        List<String> names;
        try {
            names = listMailboxes("", "*");
        } catch (IOException e) {
            LOGGER.log(Level.FINE,
                    L10N.getString("fine.could_not_enumerate_mailboxes_eager_warm"), e);
            return;
        }
        MailboxWatcher watcher = MailboxRuntime.getWatcher();
        for (String mailboxName : names) {
            try {
                final Path mboxFilePath = resolveMailboxPath(mailboxName);
                final Path indexPath = searchIndexOf(mboxFilePath);
                final boolean isInbox = INBOX.equalsIgnoreCase(mailboxName);
                final String mbName = mailboxName;
                final MailboxIndexer.IndexWork work = new MailboxIndexer.IndexWork() {
                    @Override
                    public void run() throws Exception {
                        Mailbox mb = openMailbox(mbName, false);
                        mb.close(false);
                    }
                };
                long lastModified;
                try {
                    lastModified = Files.getLastModifiedTime(mboxFilePath).toMillis();
                } catch (IOException e) {
                    lastModified = 0L;
                }
                indexer.submitBackground(new MailboxIndexKey(indexPath), isInbox, lastModified, work);

                // Persistent watch so mail delivered by another process
                // (no live session touching this mailbox) still triggers
                // a background catch-up index job (issue #163).
                if (watcher != null) {
                    Path parent = mboxFilePath.getParent();
                    String fileName = mboxFilePath.getFileName().toString();
                    watcher.register(parent, fileName, new MailboxWatcher.ChangeListener() {
                        @Override
                        public void onChange(String changed) {
                            long lm;
                            try {
                                lm = Files.getLastModifiedTime(mboxFilePath).toMillis();
                            } catch (IOException e) {
                                lm = System.currentTimeMillis();
                            }
                            indexer.submitBackground(new MailboxIndexKey(indexPath), isInbox, lm, work);
                        }
                    });
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, MessageFormat.format(
                        L10N.getString("fine.skipping_eager_index_warm"), mailboxName), e);
            }
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
            LOGGER.fine(L10N.getString("fine.closed_mbox_store"));
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
        
        // Scan for mbox files
        scanMailboxes(userDirectory, fullPattern, result);
        
        // Sort results
        Collections.sort(result);
        
        return result;
    }

    /**
     * Adds the mailboxes matching {@code pattern} to {@code result},
     * decoding filesystem-encoded names back to Unicode.
     */
    private void scanMailboxes(Path directory, String pattern, List<String> result)
            throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        for (Path file : MboxLayout.findMailboxFiles(directory, extension)) {
            String fullName = mailboxNameOf(directory.relativize(file));
            if (matchesPattern(fullName, pattern)) {
                result.add(fullName);
            }
        }
    }

    /**
     * Converts a mailbox file's path relative to the user directory to its
     * mailbox name: directories are hierarchy components, and the file name
     * loses its extension.
     */
    private String mailboxNameOf(Path relative) {
        StringBuilder name = new StringBuilder();
        int count = relative.getNameCount();
        for (int i = 0; i < count; i++) {
            String part = relative.getName(i).toString();
            if (i == count - 1) {
                part = part.substring(0, part.length() - extension.length());
            }
            if (i > 0) {
                name.append(HIERARCHY_DELIMITER);
            }
            name.append(MailboxNameCodec.decode(part));
        }
        return name.toString();
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
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("info.mailbox_created"), normalized));
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
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("info.mailbox_deleted"), normalized));
        }
    }

    /**
     * Returns the directory that holds the inferiors of a mailbox: named
     * like the mailbox file without its extension, beside it.
     */
    private Path hierarchyDirectory(Path mailboxFile) {
        String fileName = mailboxFile.getFileName().toString();
        return mailboxFile.resolveSibling(
                fileName.substring(0, fileName.length() - extension.length()));
    }

    /** Returns the search index kept beside a mailbox file. */
    private static Path searchIndexOf(Path mailboxFile) {
        return mailboxFile.resolveSibling(mailboxFile.getFileName() + ".gidx");
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

        // RFC 3501 section 6.3.5: the inferior names must be renamed too,
        // and the exception is INBOX, whose inferiors stay where they are.
        // They live in the directory beside the mailbox file, which moves as
        // one.
        Path oldDir = hierarchyDirectory(oldPath);
        Path newDir = hierarchyDirectory(newPath);
        boolean moveInferiors = !isInboxRename && Files.isDirectory(oldDir);
        if (moveInferiors) {
            if (Files.exists(newDir)) {
                throw new IOException(
                        "Destination hierarchy already exists: " + newName);
            }
            if (newDir.startsWith(oldDir)) {
                throw new IOException("Cannot rename " + oldName
                        + " to one of its own inferiors: " + newName);
            }
        }

        // Ensure parent directory exists
        Path newParent = newPath.getParent();
        if (newParent != null && !Files.exists(newParent)) {
            Files.createDirectories(newParent);
        }

        // Every move made, so that a failure part way can undo them.
        List<Path[]> moves = new ArrayList<Path[]>();
        if (moveInferiors) {
            moves.add(new Path[] { oldDir, newDir });
        }
        // The search index describes the messages, so it goes with them.
        Path oldIndex = searchIndexOf(oldPath);
        if (Files.exists(oldIndex)) {
            moves.add(new Path[] { oldIndex, searchIndexOf(newPath) });
        }
        moves.add(new Path[] { oldPath, newPath });

        List<Path[]> done = new ArrayList<Path[]>();
        boolean complete = false;
        try {
            for (Path[] move : moves) {
                Files.move(move[0], move[1]);
                done.add(move);
            }
            if (isInboxRename) {
                // INBOX always exists: recreate it empty
                Files.createFile(oldPath);
            }
            complete = true;
        } finally {
            if (!complete) {
                undoMoves(done);
            }
        }
        
        renameSubscriptions(normalizedOld, normalizedNew, isInboxRename);
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("info.mailbox_renamed"), normalizedOld, normalizedNew));
        }
    }

    /**
     * Moves back, most recent first, the moves of a rename that failed part
     * way. A move that cannot be undone is logged and the rest carry on, so
     * as much as possible is restored.
     */
    private void undoMoves(List<Path[]> done) {
        for (int i = done.size() - 1; i >= 0; i--) {
            Path[] move = done.get(i);
            try {
                Files.move(move[1], move[0]);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, MessageFormat.format(
                        L10N.getString("warn.rename_rollback_failed"),
                        move[1], move[0]), e);
            }
        }
    }

    /**
     * Follows a rename in the subscription list: the mailbox and its
     * inferiors take their new names. When INBOX is renamed the old name is
     * kept, since INBOX still exists, and only inferiors are left alone.
     */
    private void renameSubscriptions(String oldName, String newName,
            boolean keepOld) {
        String inferiorPrefix = oldName + HIERARCHY_DELIMITER;
        for (String subscribed : new ArrayList<String>(subscriptions)) {
            String renamed = null;
            if (subscribed.equals(oldName)) {
                renamed = newName;
            } else if (!keepOld && subscribed.startsWith(inferiorPrefix)) {
                renamed = newName + subscribed.substring(oldName.length());
            }
            if (renamed != null) {
                if (!keepOld) {
                    subscriptions.remove(subscribed);
                }
                subscriptions.add(renamed);
            }
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
        
        // Child mailboxes live in a directory named like the mailbox file
        // without its extension. Derive that from the file itself so the
        // encoded name is used, exactly as resolveMailboxPath produced it.
        Path childDir = hierarchyDirectory(mailboxPath);
        
        if (Files.isDirectory(childDir)) {
            boolean hasChildren = MboxLayout.hasMailboxFiles(childDir, extension);
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
        long[] stats = MboxLayout.measure(userDirectory, extension);
        
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
        
        // Normalize any path component that is INBOX by iterating through delimiter-separated parts
        StringBuilder result = new StringBuilder();
        int start = 0;
        int length = name.length();
        boolean first = true;
        while (start <= length) {
            int end = name.indexOf(HIERARCHY_DELIMITER, start);
            if (end < 0) {
                end = length;
            }
            String part = name.substring(start, end);
            if (!first) {
                result.append(HIERARCHY_DELIMITER);
            }
            first = false;
            if (INBOX.equalsIgnoreCase(part)) {
                result.append(INBOX);
            } else {
                result.append(part);
            }
            start = end + 1;
        }
        
        return result.toString();
    }

    /**
     * Resolves a mailbox name to a file path.
     * Mailbox name components are encoded for filesystem safety.
     */
    private Path resolveMailboxPath(String mailboxName) throws IOException {
        // Count the number of parts to determine which is last
        int partCount = 1;
        for (int i = 0; i < mailboxName.length(); i++) {
            if (mailboxName.charAt(i) == HIERARCHY_DELIMITER) {
                partCount++;
            }
        }
        
        Path current = userDirectory;
        int start = 0;
        int length = mailboxName.length();
        int partIndex = 0;
        
        while (start <= length) {
            int end = mailboxName.indexOf(HIERARCHY_DELIMITER, start);
            if (end < 0) {
                end = length;
            }
            String part = mailboxName.substring(start, end);
            String encoded = MailboxNameCodec.encode(part);
            String sanitized = sanitizePathComponent(encoded);
            
            if (sanitized.isEmpty()) {
                throw new IOException("Invalid mailbox name component: " + part);
            }
            
            if (partIndex == partCount - 1) {
                // Last component gets the extension
                return resolveSafePath(current, sanitized + extension);
            } else {
                current = resolveSafePath(current, sanitized);
            }
            
            partIndex++;
            start = end + 1;
        }
        
        // Should not reach here
        throw new IOException("Invalid mailbox name: " + mailboxName);
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
     * Matches a mailbox name against an IMAP pattern.
     * Supports {@code *} (any chars, including hierarchy delimiter) and
     * {@code %} (any chars except delimiter). Comparison is case-insensitive.
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
                if (Character.toLowerCase(nc) != Character.toLowerCase(pc)) {
                    return false;
                }
                nameIdx++;
                patIdx++;
            }
        }

        return nameIdx >= name.length();
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
}
