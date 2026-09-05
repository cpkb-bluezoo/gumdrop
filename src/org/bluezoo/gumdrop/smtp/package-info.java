/*
 * package-info.java
 * Copyright (C) 2005, 2025 Chris Burdess
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
 * SMTP (RFC 5321) server for receiving and relaying email.
 *
 * <p>{@link org.bluezoo.gumdrop.smtp.SMTPService} is the abstract base
 * for SMTP application services, owning configuration and creating
 * per-connection handlers; {@link org.bluezoo.gumdrop.smtp.SMTPListener}
 * is the TCP transport listener; {@link
 * org.bluezoo.gumdrop.smtp.SMTPProtocolHandler} handles one session and
 * its command processing. The protocol flow is modeled as a sequence of
 * stages ({@link org.bluezoo.gumdrop.smtp.handler}): each stage hands
 * the application a handler interface exposing only the commands legal
 * at that point, and a state interface to accept or reject them, so
 * out-of-order responses aren't possible to write.
 *
 * <p>{@link org.bluezoo.gumdrop.smtp.SMTPPipeline} lets message content
 * be processed as it streams in, without buffering the whole message --
 * {@link org.bluezoo.gumdrop.smtp.auth.AuthPipeline} (SPF/DKIM/DMARC) is
 * one such pipeline, obtained from a handler's {@code getPipeline()}.
 * {@link org.bluezoo.gumdrop.smtp.DeliveryRequirements} collects the
 * delivery constraints a sender can request (REQUIRETLS, MT-PRIORITY,
 * FUTURERELEASE, DELIVERBY, DSN return/envelope-ID) that a relaying
 * handler must respect.
 *
 * <h2>Extensions supported</h2>
 *
 * <p>STARTTLS, AUTH (SASL), SIZE, 8BITMIME, SMTPUTF8, PIPELINING,
 * CHUNKING/BINARYMIME (BDAT), ENHANCEDSTATUSCODES, DSN, LIMITS,
 * REQUIRETLS, MT-PRIORITY, FUTURERELEASE, DELIVERBY, and Postfix's
 * XCLIENT.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5321">RFC 5321 - SMTP</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6409">RFC 6409 - Message Submission</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3207">RFC 3207 - STARTTLS</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4954">RFC 4954 - SMTP AUTH</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8314">RFC 8314 - Implicit TLS</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1870">RFC 1870 - SIZE</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6152">RFC 6152 - 8BITMIME</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6531">RFC 6531 - SMTPUTF8</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2920">RFC 2920 - PIPELINING</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3030">RFC 3030 - CHUNKING/BINARYMIME</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2034">RFC 2034 - ENHANCEDSTATUSCODES</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3461">RFC 3461 - DSN</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9422">RFC 9422 - LIMITS</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8689">RFC 8689 - REQUIRETLS</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6710">RFC 6710 - MT-PRIORITY</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4865">RFC 4865 - FUTURERELEASE</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2852">RFC 2852 - DELIVERBY</a>
 * @see org.bluezoo.gumdrop.smtp.handler
 * @see org.bluezoo.gumdrop.smtp.auth
 * @see org.bluezoo.gumdrop.smtp.client
 */
package org.bluezoo.gumdrop.smtp;
