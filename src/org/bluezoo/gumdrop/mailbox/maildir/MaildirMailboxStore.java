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
import org.bluezoo.gumdrop.mailbox.MailboxAttribute;
import org.bluezoo.gumdrop.mailbox.MailboxNameCodec;
import org.bluezoo.gumdrop.mailbox.MailboxRuntime;
import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
import org.bluezoo.gumdrop.mailbox.index.MailboxIndexKey;
import org.bluezoo.gumdrop.mailbox.index.MailboxIndexer;
import org.bluezoo.gumdrop.mailbox.index.MailboxWatcher;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.MessageFormat;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
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

    /** Chunk size used when copying message content between mailboxes. */
    private static final int COPY_BUFFER_SIZE = 65536;
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.mailbox.L10N");

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

        // Eagerly warm search indexes for this user's mailboxes in the
        // background rather than only rebuilding lazily on first SELECT
        // (issue #163). Submitted unconditionally for every mailbox: a
        // mailbox whose index is already current is cheap to confirm
        // (loadOrBuildSearchIndex()'s fast incremental path), so no
        // separate staleness pre-check is needed here.
        enqueueEagerIndexWarming();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("fine.opened_maildir_store"), username));
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
                final Path maildirPath = resolveMailboxPath(mailboxName);
                final Path indexPath = maildirPath.resolve(".gidx");
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
                    lastModified = Files.getLastModifiedTime(maildirPath).toMillis();
                } catch (IOException e) {
                    lastModified = 0L;
                }
                indexer.submitBackground(new MailboxIndexKey(indexPath), isInbox, lastModified, work);

                // Persistent watch on cur/ and new/ so mail delivered by
                // another process still triggers a background catch-up
                // index job even without a live session (issue #163).
                if (watcher != null) {
                    MailboxWatcher.ChangeListener onChange = new MailboxWatcher.ChangeListener() {
                        @Override
                        public void onChange(String changed) {
                            long lm;
                            try {
                                lm = Files.getLastModifiedTime(maildirPath).toMillis();
                            } catch (IOException e) {
                                lm = System.currentTimeMillis();
                            }
                            indexer.submitBackground(new MailboxIndexKey(indexPath), isInbox, lm, work);
                        }
                    };
                    watcher.register(maildirPath.resolve("cur"), null, onChange);
                    watcher.register(maildirPath.resolve("new"), null, onChange);
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, MessageFormat.format(
                        L10N.getString("fine.skipping_eager_index_warm"), mailboxName), e);
            }
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
        
        // Parse hierarchy delimiter-separated components, encode each, then join with Maildir++ separator
        StringBuilder result = new StringBuilder();
        result.append(MAILDIR_FOLDER_PREFIX);
        
        int partStart = 0;
        int nameLen = mailboxName.length();
        boolean first = true;
        while (partStart <= nameLen) {
            int partEnd = mailboxName.indexOf(HIERARCHY_DELIMITER, partStart);
            if (partEnd < 0) {
                partEnd = nameLen;
            }
            String part = mailboxName.substring(partStart, partEnd);
            if (!first) {
                result.append(MAILDIR_FOLDER_PREFIX);
            }
            first = false;
            // Encode each component for filesystem safety
            result.append(MailboxNameCodec.encode(part));
            partStart = partEnd + 1;
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
        
        // Parse dot-separated components, decode each, then join with hierarchy delimiter
        StringBuilder result = new StringBuilder();
        int partStart = 0;
        int nameLen = dirName.length();
        boolean first = true;
        while (partStart <= nameLen) {
            int partEnd = dirName.indexOf('.', partStart);
            if (partEnd < 0) {
                partEnd = nameLen;
            }
            String part = dirName.substring(partStart, partEnd);
            if (!first) {
                result.append(HIERARCHY_DELIMITER);
            }
            first = false;
            // Decode each component from filesystem encoding
            result.append(MailboxNameCodec.decode(part));
            partStart = partEnd + 1;
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
        
        // Security check - ensure path is within user directory,
        // resolving symlinks to prevent mailbox escape via planted links.
        Path normalized = mailboxPath.normalize();
        if (!normalized.startsWith(userDirectory)) {
            throw new IOException("Invalid mailbox path: " + mailboxName);
        }
        Path canonicalUser = userDirectory.toRealPath();
        if (Files.exists(normalized)) {
            if (!normalized.toRealPath().startsWith(canonicalUser)) {
                throw new IOException("Invalid mailbox path: " + mailboxName);
            }
        } else {
            Path parent = normalized.getParent();
            if (parent != null && Files.exists(parent)
                    && !parent.toRealPath().startsWith(canonicalUser)) {
                throw new IOException("Invalid mailbox path: " + mailboxName);
            }
        }

        return normalized;
    }

    @Override
    public List<String> listMailboxes(String reference, String pattern) throws IOException {
        ensureOpen();

        String fullPattern = reference + pattern;
        List<String> result = new ArrayList<>();

        if (MaildirLayout.isMaildir(userDirectory) && matchesPattern(INBOX, fullPattern)) {
            result.add(INBOX);
        }
        List<Path> subfolders = MaildirLayout.listSubfolders(userDirectory);
        for (Path folder : subfolders) {
            String mailboxName = directoryToMailboxName(folder.getFileName().toString());
            if (matchesPattern(mailboxName, fullPattern)) {
                result.add(mailboxName);
            }
        }

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
        
        if (!MaildirLayout.isMaildir(mailboxPath)) {
            throw new IOException("Mailbox does not exist: " + mailboxName);
        }
        
        return new MaildirMailbox(mailboxPath, normalized, readOnly);
    }

    /**
     * Copies messages between mailboxes by appending each source message to
     * the destination. When the destination is the source mailbox itself the
     * already-open instance is used, so its UID list stays authoritative.
     */
    @Override
    public Map<Integer, Long> copyMessages(Mailbox source,
            List<Integer> messageNumbers, String destinationMailbox)
            throws IOException {
        boolean sameMailbox = normalizeMailboxName(destinationMailbox)
                .equals(normalizeMailboxName(source.getName()));
        Mailbox destination = sameMailbox
                ? source : openMailbox(destinationMailbox, false);
        try {
            Map<Integer, Long> uids = new LinkedHashMap<Integer, Long>();
            for (int i = 0; i < messageNumbers.size(); i++) {
                Integer number = messageNumbers.get(i);
                long uid = copyMessage(source, number.intValue(),
                        destination);
                uids.put(number, Long.valueOf(uid));
            }
            return uids;
        } finally {
            if (!sameMailbox) {
                destination.close(false);
            }
        }
    }

    /**
     * Moves messages: a copy followed by removal of exactly the moved
     * source messages. If the copy fails the source is left untouched.
     */
    @Override
    public Map<Integer, Long> moveMessages(Mailbox source,
            List<Integer> messageNumbers, String destinationMailbox)
            throws IOException {
        Map<Integer, Long> uids = copyMessages(source, messageNumbers,
                destinationMailbox);
        source.expungeMessages(messageNumbers);
        return uids;
    }

    private long copyMessage(Mailbox source, int messageNumber,
            Mailbox destination) throws IOException {
        Set<Flag> flags = EnumSet.noneOf(Flag.class);
        flags.addAll(source.getFlags(messageNumber));
        flags.remove(Flag.RECENT);
        OffsetDateTime date = null;
        MessageDescriptor descriptor = source.getMessage(messageNumber);
        if (descriptor instanceof MaildirMessageDescriptor) {
            date = ((MaildirMessageDescriptor) descriptor).getInternalDate();
        }

        destination.startAppendMessage(flags, date);
        ReadableByteChannel in = source.getMessageContent(messageNumber);
        try {
            ByteBuffer buf = ByteBuffer.allocate(COPY_BUFFER_SIZE);
            while (in.read(buf) >= 0) {
                buf.flip();
                destination.appendMessageContent(buf);
                buf.clear();
            }
        } finally {
            in.close();
        }
        return destination.endAppendMessage();
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
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("info.mailbox_created"), mailboxName));
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
        
        // Only a real Maildir may be deleted: anything else under the user
        // directory is not ours to remove.
        if (!MaildirLayout.isMaildir(mailboxPath)) {
            throw new IOException("Mailbox does not exist: " + mailboxName);
        }
        
        // Check if mailbox is empty
        if (MaildirLayout.hasMessages(mailboxPath)) {
            throw new IOException("Mailbox is not empty: " + mailboxName);
        }

        // Delete Maildir structure
        MaildirLayout.deleteTree(mailboxPath);

        // Remove from subscriptions
        subscriptions.remove(normalized);
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("info.mailbox_deleted"), mailboxName));
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
        resolveMailboxPath(normalizedNew);
        
        if (!Files.exists(oldPath)) {
            throw new IOException("Mailbox does not exist: " + oldName);
        }

        // RFC 3501 section 6.3.5: the inferior names must be renamed too
        // (foo/bar becomes zap/bar when foo becomes zap). Work out every
        // move, and check that none of the targets is taken, before
        // touching anything.
        List<String[]> renames = new ArrayList<String[]>();
        renames.add(new String[] { normalizedOld, normalizedNew });
        String inferiorPrefix = normalizedOld + HIERARCHY_DELIMITER;
        for (String name : listMailboxes("", "*")) {
            if (name.startsWith(inferiorPrefix)) {
                renames.add(new String[] { name,
                        normalizedNew + name.substring(normalizedOld.length()) });
            }
        }
        List<Path[]> moves = new ArrayList<Path[]>();
        for (String[] rename : renames) {
            Path target = resolveMailboxPath(rename[1]);
            if (Files.exists(target)) {
                throw new IOException("Mailbox already exists: " + rename[1]);
            }
            moves.add(new Path[] { resolveMailboxPath(rename[0]), target });
        }

        // All or nothing: a move that fails undoes those already made.
        List<Path[]> done = new ArrayList<Path[]>();
        boolean complete = false;
        try {
            for (Path[] move : moves) {
                Files.move(move[0], move[1]);
                done.add(move);
            }
            complete = true;
        } finally {
            if (!complete) {
                undoMoves(done);
            }
        }

        // Update subscriptions
        for (String[] rename : renames) {
            if (subscriptions.remove(rename[0])) {
                subscriptions.add(rename[1]);
            }
        }
        
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

    @Override
    public Set<MailboxAttribute> getMailboxAttributes(String mailboxName) throws IOException {
        ensureOpen();
        
        Set<MailboxAttribute> attributes = EnumSet.noneOf(MailboxAttribute.class);
        String normalized = normalizeMailboxName(mailboxName);
        Path mailboxPath = resolveMailboxPath(normalized);
        
        if (!MaildirLayout.isMaildir(mailboxPath)) {
            attributes.add(MailboxAttribute.NOSELECT);
            return attributes;
        }
        
        // Check for children (other .folder* directories)
        boolean hasChildren = false;
        String dirName = mailboxToDirectoryName(normalized);
        String prefix = dirName.isEmpty() ? "." : dirName + ".";
        
        for (Path folder : MaildirLayout.listSubfolders(userDirectory)) {
            if (folder.getFileName().toString().startsWith(prefix)) {
                hasChildren = true;
                break;
            }
        }

        if (hasChildren) {
            attributes.add(MailboxAttribute.HASCHILDREN);
        } else {
            attributes.add(MailboxAttribute.HASNOCHILDREN);
        }
        
        return attributes;
    }

}
