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
 * Asynchronous SPF (RFC 7208), DKIM (RFC 6376), and DMARC (RFC 7489)
 * email authentication for the SMTP server.
 *
 * <p>{@link org.bluezoo.gumdrop.smtp.auth.AuthPipeline} implements
 * {@link org.bluezoo.gumdrop.smtp.SMTPPipeline}: returned from a
 * handler's {@code getPipeline()}, it runs the SPF check on MAIL FROM
 * (after a DNS lookup), hashes the message body from the raw bytes as
 * they stream past for DKIM signature verification, and evaluates DMARC
 * policy (combining the SPF/DKIM results with domain alignment) once the
 * message is complete -- results arrive via {@link
 * org.bluezoo.gumdrop.smtp.auth.SPFCallback}, {@link
 * org.bluezoo.gumdrop.smtp.auth.DKIMCallback}, and {@link
 * org.bluezoo.gumdrop.smtp.auth.DMARCCallback} respectively, registered
 * on the pipeline's builder. Message headers are parsed via {@link
 * org.bluezoo.gumdrop.mime.rfc5322.MessageParser}; a caller-supplied
 * {@code MessageHandler} can observe the same parse to process content
 * without a second pass.
 *
 * <p>{@link org.bluezoo.gumdrop.smtp.auth.AuthVerdict} (PASS, REJECT,
 * QUARANTINE, NONE) is the combined authentication decision the DMARC
 * callback receives, for the handler to act on.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.smtp.SMTPPipeline
 * @see org.bluezoo.gumdrop.smtp.auth.AuthPipeline
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7208">RFC 7208 - SPF</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6376">RFC 6376 - DKIM</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7489">RFC 7489 - DMARC</a>
 */
package org.bluezoo.gumdrop.smtp.auth;
