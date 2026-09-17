/*
 * ConfirmMethods.java
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

package org.bluezoo.gumdrop.amqp;

import java.nio.ByteBuffer;

/**
 * Encode/decode for the {@code confirm} class (85, RabbitMQ extension —
 * publisher confirms).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ConfirmMethods {

    private ConfirmMethods() {
    }

    /** {@code confirm.select} (85,10) — sent by the client. */
    public static ByteBuffer encodeSelect(boolean noWait) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 1);
        buf.putShort((short) AmqpMethod.CLASS_CONFIRM);
        buf.putShort((short) AmqpMethod.CONFIRM_SELECT);
        buf.put(AmqpBits.pack(noWait));
        buf.flip();
        return buf;
    }

    /** {@code confirm.select-ok} (85,11) — sent by the server; no arguments. */
    public static void decodeSelectOk(ByteBuffer payload) {
        // No arguments.
    }

    public static boolean decodeSelect(ByteBuffer payload) {
        return AmqpBits.unpack(payload.get(), 0); // no-wait
    }

    public static ByteBuffer encodeSelectOk() {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putShort((short) AmqpMethod.CLASS_CONFIRM);
        buf.putShort((short) AmqpMethod.CONFIRM_SELECT_OK);
        buf.flip();
        return buf;
    }
}
