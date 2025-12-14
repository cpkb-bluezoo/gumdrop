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
 * POP3 (Post Office Protocol version 3) server implementation.
 *
 * <p>This package provides a complete POP3 server implementation for the
 * gumdrop multipurpose Java server framework. The POP3 server allows email
 * clients to retrieve messages from mailboxes using the standard POP3 protocol
 * as defined in RFC 1939, with support for modern extensions.
 *
 * <h2>Architecture</h2>
 *
 * <h3>Core Components</h3>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.pop3.POP3Server} - Server connector that listens for POP3 connections</li>
 *   <li>{@link org.bluezoo.gumdrop.pop3.POP3Connection} - Connection handler implementing the POP3 protocol</li>
 *   <li>{@link org.bluezoo.gumdrop.pop3.POP3Exception} - POP3-specific exceptions</li>
 * </ul>
 *
 * <h3>Mailbox Storage</h3>
 * <p>The POP3 server uses the {@link org.bluezoo.gumdrop.mailbox} package for
 * mailbox storage, which provides a pluggable storage backend:
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.Mailbox} - Interface for accessing mailbox contents</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.MailboxFactory} - Factory for creating mailbox instances</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.mbox.MboxMailbox} - Mbox file format implementation</li>
 * </ul>
 *
 * <h2>Supported RFCs</h2>
 * <ul>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc1939">RFC 1939</a> - POP3 base protocol</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc1957">RFC 1957</a> - Implementation recommendations</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc2195">RFC 2195</a> - CRAM-MD5 authentication</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc2449">RFC 2449</a> - POP3 Extension Mechanism (CAPA command)</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc2595">RFC 2595</a> - TLS for POP3 (STLS command)</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc2831">RFC 2831</a> - DIGEST-MD5 authentication</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc4422">RFC 4422</a> - SASL EXTERNAL mechanism</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc4616">RFC 4616</a> - SASL PLAIN mechanism</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc4752">RFC 4752</a> - GSSAPI/Kerberos authentication</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc5034">RFC 5034</a> - SASL Authentication for POP3 (AUTH command)</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc5802">RFC 5802</a> - SCRAM mechanism</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc6816">RFC 6816</a> - UTF-8 support</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc7628">RFC 7628</a> - OAUTHBEARER SASL mechanism</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc7677">RFC 7677</a> - SCRAM-SHA-256 mechanism</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc8314">RFC 8314</a> - Require TLS for email access</li>
 * </ul>
 *
 * <h2>Supported Commands</h2>
 *
 * <h3>Required Commands (RFC 1939)</h3>
 * <ul>
 *   <li>{@code USER} - Specify username for authentication</li>
 *   <li>{@code PASS} - Provide password for authentication</li>
 *   <li>{@code STAT} - Get mailbox statistics (count and size)</li>
 *   <li>{@code LIST} - List messages with sizes</li>
 *   <li>{@code RETR} - Retrieve a message</li>
 *   <li>{@code DELE} - Mark a message for deletion</li>
 *   <li>{@code NOOP} - No operation</li>
 *   <li>{@code RSET} - Reset session (undelete all messages)</li>
 *   <li>{@code QUIT} - End session and update mailbox</li>
 * </ul>
 *
 * <h3>Optional Commands (RFC 1939)</h3>
 * <ul>
 *   <li>{@code APOP} - Challenge-response authentication</li>
 *   <li>{@code TOP} - Retrieve message headers and N body lines</li>
 *   <li>{@code UIDL} - Get unique message identifiers</li>
 * </ul>
 *
 * <h3>Extension Commands (RFC 2449, RFC 2595, RFC 5034, RFC 6816)</h3>
 * <ul>
 *   <li>{@code AUTH} - SASL authentication (PLAIN, LOGIN, CRAM-MD5, DIGEST-MD5,
 *       SCRAM-SHA-256, OAUTHBEARER, GSSAPI, EXTERNAL, NTLM)</li>
 *   <li>{@code CAPA} - List server capabilities</li>
 *   <li>{@code STLS} - Upgrade connection to TLS</li>
 *   <li>{@code UTF8} - Enable UTF-8 mode</li>
 * </ul>
 *
 * <h2>POP3 Protocol States</h2>
 *
 * <h3>AUTHORIZATION</h3>
 * <p>Initial state after connection. Client must authenticate using:
 * <ul>
 *   <li>USER/PASS commands, or</li>
 *   <li>APOP command (challenge-response), or</li>
 *   <li>AUTH command with SASL mechanism</li>
 * </ul>
 *
 * <h3>TRANSACTION</h3>
 * <p>After successful authentication. Client can:
 * <ul>
 *   <li>Retrieve messages (RETR, TOP)</li>
 *   <li>Mark messages for deletion (DELE)</li>
 *   <li>Get mailbox statistics (STAT, LIST, UIDL)</li>
 *   <li>Reset deletions (RSET)</li>
 * </ul>
 *
 * <h3>UPDATE</h3>
 * <p>After QUIT command. Server:
 * <ul>
 *   <li>Commits message deletions</li>
 *   <li>Releases mailbox lock</li>
 *   <li>Closes connection</li>
 * </ul>
 *
 * <h2>Security Features</h2>
 *
 * <h3>Transport Security</h3>
 * <ul>
 *   <li><b>POP3S</b> - Implicit TLS on port 995 (secure=true)</li>
 *   <li><b>STARTTLS</b> - Upgrade to TLS via STLS command (secure=false with SSL context)</li>
 * </ul>
 *
 * <h3>Authentication</h3>
 * <p>All authentication mechanisms use the standard Gumdrop
 * {@link org.bluezoo.gumdrop.auth.Realm} interface:
 *
 * <table border="1" cellpadding="5">
 *   <caption>Authentication Mechanisms</caption>
 *   <tr><th>Mechanism</th><th>Realm Method</th><th>RFC</th><th>Description</th></tr>
 *   <tr><td>USER/PASS</td><td>{@code passwordMatch()}</td><td>RFC 1939</td><td>Basic authentication</td></tr>
 *   <tr><td>APOP</td><td>{@code getPassword()}</td><td>RFC 1939</td><td>MD5 challenge-response</td></tr>
 *   <tr><td>AUTH PLAIN</td><td>{@code passwordMatch()}</td><td>RFC 4616</td><td>SASL PLAIN mechanism</td></tr>
 *   <tr><td>AUTH LOGIN</td><td>{@code passwordMatch()}</td><td>-</td><td>Legacy SASL LOGIN</td></tr>
 *   <tr><td>AUTH CRAM-MD5</td><td>{@code getPassword()}</td><td>RFC 2195</td><td>HMAC-MD5 challenge-response</td></tr>
 *   <tr><td>AUTH DIGEST-MD5</td><td>{@code getDigestHA1()}</td><td>RFC 2831</td><td>HTTP Digest-style auth</td></tr>
 *   <tr><td>AUTH SCRAM-SHA-256</td><td>{@code getPassword()}</td><td>RFC 7677</td><td>Salted challenge-response</td></tr>
 *   <tr><td>AUTH OAUTHBEARER</td><td>{@code validateBearerToken()}</td><td>RFC 7628</td><td>OAuth 2.0 Bearer tokens</td></tr>
 *   <tr><td>AUTH GSSAPI</td><td>SASL/Kerberos</td><td>RFC 4752</td><td>Enterprise SSO</td></tr>
 *   <tr><td>AUTH EXTERNAL</td><td>Certificate</td><td>RFC 4422</td><td>TLS client certificate</td></tr>
 *   <tr><td>AUTH NTLM</td><td>{@code getPassword()}</td><td>-</td><td>Windows domain auth</td></tr>
 * </table>
 *
 * <h3>Attack Mitigation</h3>
 * <ul>
 *   <li>Login delay enforcement after failed authentication</li>
 *   <li>Session timeout protection</li>
 *   <li>Line length limits to prevent buffer overflow</li>
 *   <li>Mailbox locking to prevent concurrent access</li>
 * </ul>
 *
 * <h2>Configuration Example</h2>
 *
 * <pre>{@code
 * <!-- POP3 with STARTTLS support (port 110) -->
 * <server class="org.bluezoo.gumdrop.pop3.POP3Server" port="110"
 *     realm="myRealm"
 *     loginDelay="3000"
 *     enableAPOP="true"
 *     enableUTF8="true"
 *     keystoreFile="/path/to/keystore.p12"
 *     keystorePass="password"/>
 *
 * <!-- POP3S with implicit TLS (port 995) -->
 * <server class="org.bluezoo.gumdrop.pop3.POP3Server" port="995"
 *     secure="true"
 *     realm="myRealm"
 *     keystoreFile="/path/to/keystore.p12"
 *     keystorePass="password"/>
 * }</pre>
 *
 * <p>Note: The {@code mailboxFactory} must be configured programmatically or via a
 * custom configuration extension, as the standard gumdroprc format uses
 * attributes for simple properties only.
 *
 * <h2>Creating Custom Mailbox Implementations</h2>
 *
 * <p>To integrate with your own email storage system, implement the
 * {@link org.bluezoo.gumdrop.mailbox.Mailbox} interface:
 *
 * <pre>
 * public class MyCustomMailbox implements Mailbox &#123;
 *     &#64;Override
 *     public void open(String username) throws IOException &#123;
 *         // Open mailbox for user
 *     &#125;
 *
 *     &#64;Override
 *     public void close(boolean expunge) throws IOException &#123;
 *         // Close and optionally commit deletions
 *     &#125;
 *
 *     &#64;Override
 *     public int getMessageCount() throws IOException &#123;
 *         // Return number of messages
 *     &#125;
 *
 *     // ... implement other methods
 * &#125;
 * </pre>
 *
 * <p>Then create a {@link org.bluezoo.gumdrop.mailbox.MailboxFactory}:
 *
 * <pre>
 * public class MyCustomMailboxFactory implements MailboxFactory &#123;
 *     &#64;Override
 *     public Mailbox createMailbox() &#123;
 *         return new MyCustomMailbox();
 *     &#125;
 * &#125;
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <ul>
 *   <li>Each POP3 connection gets its own {@link org.bluezoo.gumdrop.mailbox.Mailbox} instance</li>
 *   <li>Mailboxes use file locking to prevent concurrent access</li>
 *   <li>Connection handlers are thread-safe via synchronization</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.pop3.POP3Server
 * @see org.bluezoo.gumdrop.pop3.POP3Connection
 * @see org.bluezoo.gumdrop.mailbox.Mailbox
 * @see org.bluezoo.gumdrop.mailbox.mbox.MboxMailbox
 * @see org.bluezoo.gumdrop.auth.Realm
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1939">RFC 1939 - POP3</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5034">RFC 5034 - SASL for POP3</a>
 */
package org.bluezoo.gumdrop.pop3;

