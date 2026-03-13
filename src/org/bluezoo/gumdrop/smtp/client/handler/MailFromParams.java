/*
 * MailFromParams.java
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

package org.bluezoo.gumdrop.smtp.client.handler;

/**
 * MAIL FROM extension parameters for SMTP client.
 *
 * <p>Encapsulates optional parameters that can be appended to the
 * MAIL FROM command when the server advertises the corresponding
 * EHLO extensions.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientSession#mailFrom
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6152">RFC 6152 — 8BITMIME</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3030">RFC 3030 — BINARYMIME</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6531">RFC 6531 — SMTPUTF8</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3461">RFC 3461 — DSN</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8689">RFC 8689 — REQUIRETLS</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6710">RFC 6710 — MT-PRIORITY</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4865">RFC 4865 — FUTURERELEASE</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2852">RFC 2852 — DELIVERBY</a>
 */
public class MailFromParams {

    /** RFC 6152 / RFC 3030 — BODY parameter value: "7BIT", "8BITMIME", or "BINARYMIME". */
    public String body;

    /** RFC 6531 — if true, appends SMTPUTF8 parameter. */
    public boolean smtpUtf8;

    /** RFC 3461 §4.3 — RET parameter: "FULL" or "HDRS". */
    public String ret;

    /** RFC 3461 §4.4 — ENVID parameter (xtext-encoded envelope id). */
    public String envid;

    /** RFC 8689 — if true, appends REQUIRETLS parameter. */
    public boolean requireTls;

    /** RFC 6710 — MT-PRIORITY value (integer), or null if not set. */
    public Integer mtPriority;

    /** RFC 4865 — HOLDFOR value in seconds, or 0 to omit. */
    public long holdFor;

    /** RFC 4865 — HOLDUNTIL value (ISO 8601 date-time), or null. */
    public String holdUntil;

    /** RFC 2852 — BY parameter (e.g. "120;R" or "120;N"), or null. */
    public String by;

}
