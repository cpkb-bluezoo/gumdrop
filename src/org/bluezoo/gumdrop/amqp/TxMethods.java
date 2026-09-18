/*
 * TxMethods.java
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
 * Encode/decode for {@code tx} class (90) methods — none of the six take
 * any arguments.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class TxMethods {

    private TxMethods() {
    }

    private static ByteBuffer noArgs(int methodId) {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putShort((short) AmqpMethod.CLASS_TX);
        buf.putShort((short) methodId);
        buf.flip();
        return buf;
    }

    public static ByteBuffer encodeSelect() { return noArgs(AmqpMethod.TX_SELECT); }
    public static ByteBuffer encodeCommit() { return noArgs(AmqpMethod.TX_COMMIT); }
    public static ByteBuffer encodeRollback() { return noArgs(AmqpMethod.TX_ROLLBACK); }

    // Server-side replies — used by a server-side implementation.
    public static ByteBuffer encodeSelectOk() { return noArgs(AmqpMethod.TX_SELECT_OK); }
    public static ByteBuffer encodeCommitOk() { return noArgs(AmqpMethod.TX_COMMIT_OK); }
    public static ByteBuffer encodeRollbackOk() { return noArgs(AmqpMethod.TX_ROLLBACK_OK); }
}
