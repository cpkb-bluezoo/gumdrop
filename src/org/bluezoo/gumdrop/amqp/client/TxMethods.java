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

package org.bluezoo.gumdrop.amqp.client;

import java.nio.ByteBuffer;

/**
 * Encode/decode for {@code tx} class (90) methods — none of the six take
 * any arguments.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class TxMethods {

    private TxMethods() {
    }

    private static ByteBuffer noArgs(int methodId) {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putShort((short) AMQPMethod.CLASS_TX);
        buf.putShort((short) methodId);
        buf.flip();
        return buf;
    }

    static ByteBuffer encodeSelect() { return noArgs(AMQPMethod.TX_SELECT); }
    static ByteBuffer encodeCommit() { return noArgs(AMQPMethod.TX_COMMIT); }
    static ByteBuffer encodeRollback() { return noArgs(AMQPMethod.TX_ROLLBACK); }

    // Server-side replies — used by a server-side implementation.
    static ByteBuffer encodeSelectOk() { return noArgs(AMQPMethod.TX_SELECT_OK); }
    static ByteBuffer encodeCommitOk() { return noArgs(AMQPMethod.TX_COMMIT_OK); }
    static ByteBuffer encodeRollbackOk() { return noArgs(AMQPMethod.TX_ROLLBACK_OK); }
}
