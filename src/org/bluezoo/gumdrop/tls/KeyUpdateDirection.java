/*
 * KeyUpdateDirection.java
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

package org.bluezoo.gumdrop.tls;

/**
 * Which direction's application traffic secret a post-handshake
 * {@code KeyUpdate} (RFC 8446 section 4.6.3/7.2) ratcheted.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum KeyUpdateDirection {

    /** The peer's traffic key (their {@code KeyUpdate} was received) -- update the read key. */
    READ,

    /** This side's own traffic key (a {@code KeyUpdate} was sent) -- update the write key. */
    WRITE

}
