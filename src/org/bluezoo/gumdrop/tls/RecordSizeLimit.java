/*
 * RecordSizeLimit.java
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
 * RFC 8449 {@code record_size_limit} extension values and validation.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8449">RFC 8449</a>
 */
public final class RecordSizeLimit {

    /** Default plaintext record limit when the extension is absent (2^14). */
    public static final int DEFAULT = 16384;

    /** Minimum value peers may advertise (RFC 8449 section 4). */
    public static final int MINIMUM = 64;

    /** Maximum plaintext fragment size TLS allows (2^14). */
    public static final int ABSOLUTE_MAX = 16384;

    private RecordSizeLimit() {
    }

    /**
     * Returns the inbound limit this endpoint enforces: configured limit
     * when the extension is enabled, otherwise {@link #DEFAULT}.
     *
     * @param configuredLimit configured limit from {@link HandshakeConfig}
     * @param extensionEnabled whether to send {@code record_size_limit}
     * @return the limit to enforce on received plaintext
     */
    public static int localInboundLimit(int configuredLimit, boolean extensionEnabled) {
        if (!extensionEnabled) {
            return DEFAULT;
        }
        return clampLocal(configuredLimit);
    }

    /**
     * Clamps a configured local limit to the RFC-permitted range.
     *
     * @param configuredLimit raw configured value
     * @return a value in [{@link #MINIMUM}, {@link #ABSOLUTE_MAX}]
     */
    public static int clampLocal(int configuredLimit) {
        int value = configuredLimit <= 0 ? DEFAULT : configuredLimit;
        if (value < MINIMUM) {
            return MINIMUM;
        }
        if (value > ABSOLUTE_MAX) {
            return ABSOLUTE_MAX;
        }
        return value;
    }

    /**
     * Parses and validates a peer's {@code record_size_limit} extension body.
     *
     * @param extBody the two-octet extension_data
     * @return the validated limit
     * @throws HandshakeFormatException if the encoding or value is invalid
     */
    public static int decodeExtensionValue(byte[] extBody) throws HandshakeFormatException {
        if (extBody.length != 2) {
            throw new HandshakeFormatException("invalid record_size_limit length");
        }
        int value = ((extBody[0] & 0xff) << 8) | (extBody[1] & 0xff);
        if (value < MINIMUM || value > ABSOLUTE_MAX) {
            throw new HandshakeFormatException("invalid record_size_limit value");
        }
        return value;
    }

    /**
     * Encodes a limit for the {@code record_size_limit} extension.
     *
     * @param limit a value already clamped via {@link #clampLocal(int)}
     * @return two-octet extension_data
     */
    public static byte[] encodeExtensionValue(int limit) {
        return new byte[] { (byte) ((limit >> 8) & 0xff), (byte) (limit & 0xff) };
    }
}
