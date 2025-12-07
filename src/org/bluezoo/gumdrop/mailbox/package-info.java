/*
 * package-info.java
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

/**
 * Mailbox storage abstraction for mail access protocols.
 *
 * <p>This package provides common interfaces for accessing mailbox contents,
 * shared across multiple mail access protocols including POP3 and IMAP.
 *
 * <h2>Core Interfaces</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.Mailbox} - Interface for single mailbox operations</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.MailboxStore} - Interface for multi-folder mail stores (IMAP)</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.MailboxFactory} - Factory for creating mailbox/store instances</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.MessageDescriptor} - Interface for message metadata</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.SimpleMessageDescriptor} - Basic implementation for POP3</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.IMAPMessageDescriptor} - Extended interface for IMAP</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.MailboxNameCodec} - Encodes mailbox names for filesystem safety</li>
 * </ul>
 *
 * <h2>Mailbox Name Encoding</h2>
 *
 * <p>Mailbox names may contain Unicode characters (e.g., "Données", "日本語") and
 * characters that are invalid on certain filesystems. The {@link org.bluezoo.gumdrop.mailbox.MailboxNameCodec}
 * class provides encoding/decoding using a modified Quoted-Printable format:
 * <ul>
 *   <li>All non-ASCII characters are encoded as UTF-8 bytes in {@code =XX} hex format</li>
 *   <li>Path separators ({@code /}, {@code \}) are always encoded</li>
 *   <li>Windows-forbidden characters ({@code : * ? " &lt; &gt; |}) are encoded</li>
 *   <li>The escape character ({@code =}) itself is encoded</li>
 * </ul>
 *
 * <p>This ensures mailbox names are safely stored on all major filesystems
 * (Unix, Windows, macOS) while preserving the original Unicode names.
 *
 * <h2>Usage Patterns</h2>
 *
 * <h3>POP3 (Single Mailbox)</h3>
 * <pre>{@code
 * MailboxFactory factory = new MboxMailboxFactory(new File("/var/mail"));
 * Mailbox mailbox = factory.createMailbox();
 * mailbox.open(username);
 * // Access messages...
 * mailbox.close(true); // expunge deleted messages
 * }</pre>
 *
 * <h3>IMAP (Multiple Mailboxes)</h3>
 * <pre>{@code
 * MailboxFactory factory = ...;
 * MailboxStore store = factory.createStore();
 * store.open(username);
 * 
 * // List mailboxes
 * List<String> folders = store.listMailboxes("", "*");
 * 
 * // Open a specific folder
 * Mailbox inbox = store.openMailbox("INBOX", false);
 * // Access messages, flags, search...
 * 
 * store.close();
 * }</pre>
 *
 * <h2>Available Implementations</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.mbox} - Standard Unix mbox file format</li>
 * </ul>
 *
 * <h2>Protocol Support</h2>
 * <table border="1" cellpadding="5">
 *   <caption>Feature Support by Protocol</caption>
 *   <tr><th>Feature</th><th>POP3</th><th>IMAP</th></tr>
 *   <tr><td>Single mailbox</td><td>✓</td><td>✓</td></tr>
 *   <tr><td>Multiple mailboxes</td><td>✗</td><td>✓</td></tr>
 *   <tr><td>Message flags</td><td>Deleted only</td><td>Full support</td></tr>
 *   <tr><td>Message search</td><td>✗</td><td>✓</td></tr>
 *   <tr><td>Append messages</td><td>✗</td><td>✓</td></tr>
 *   <tr><td>Copy/Move</td><td>✗</td><td>✓</td></tr>
 * </table>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.pop3.POP3Server
 * @see org.bluezoo.gumdrop.imap.IMAPServer
 */
package org.bluezoo.gumdrop.mailbox;
