/*
 * PacketProtectionException.java
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

package org.bluezoo.gumdrop.quic.packet;

/**
 * Thrown when AEAD sealing or opening of a QUIC packet fails.
 *
 * <p>Opening fails routinely for reasons that are not implementation
 * bugs: a corrupted or spoofed packet, a packet protected under keys
 * that have since been discarded, or a genuine authentication failure.
 * Callers are expected to log and drop the packet, not to treat this as
 * a connection-fatal error unless the caller's own protocol logic
 * determines otherwise.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PacketProtectionException extends Exception {

    private static final long serialVersionUID = 1L;

    public PacketProtectionException(String message) {
        super(message);
    }

    public PacketProtectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
