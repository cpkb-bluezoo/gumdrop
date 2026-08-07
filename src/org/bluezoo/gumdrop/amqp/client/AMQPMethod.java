/*
 * AMQPMethod.java
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

/**
 * AMQP 0-9-1 class and method IDs used by this client (AMQP 0-9-1
 * specification, method tables in §1.8 "Class Grammar" per class, and
 * the RabbitMQ-published {@code amqp0-9-1.stripped.xml} definitions).
 *
 * <p>A method frame's payload begins with a 2-byte class ID and 2-byte
 * method ID, immediately followed by the method's arguments — decoding
 * those arguments requires knowing which method they belong to, which is
 * exactly what these constants are for.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class AMQPMethod {

    private AMQPMethod() {
    }

    // ── connection (class 10) ──
    public static final int CLASS_CONNECTION = 10;
    public static final int CONNECTION_START = 10;
    public static final int CONNECTION_START_OK = 11;
    public static final int CONNECTION_SECURE = 20;
    public static final int CONNECTION_SECURE_OK = 21;
    public static final int CONNECTION_TUNE = 30;
    public static final int CONNECTION_TUNE_OK = 31;
    public static final int CONNECTION_OPEN = 40;
    public static final int CONNECTION_OPEN_OK = 41;
    public static final int CONNECTION_CLOSE = 50;
    public static final int CONNECTION_CLOSE_OK = 51;
    public static final int CONNECTION_BLOCKED = 60;
    public static final int CONNECTION_UNBLOCKED = 61;

    // ── channel (class 20) ──
    public static final int CLASS_CHANNEL = 20;
    public static final int CHANNEL_OPEN = 10;
    public static final int CHANNEL_OPEN_OK = 11;
    public static final int CHANNEL_FLOW = 20;
    public static final int CHANNEL_FLOW_OK = 21;
    public static final int CHANNEL_CLOSE = 40;
    public static final int CHANNEL_CLOSE_OK = 41;

    // ── exchange (class 40) ──
    public static final int CLASS_EXCHANGE = 40;
    public static final int EXCHANGE_DECLARE = 10;
    public static final int EXCHANGE_DECLARE_OK = 11;
    public static final int EXCHANGE_DELETE = 20;
    public static final int EXCHANGE_DELETE_OK = 21;

    // ── queue (class 50) ──
    public static final int CLASS_QUEUE = 50;
    public static final int QUEUE_DECLARE = 10;
    public static final int QUEUE_DECLARE_OK = 11;
    public static final int QUEUE_BIND = 20;
    public static final int QUEUE_BIND_OK = 21;
    public static final int QUEUE_UNBIND = 50;
    public static final int QUEUE_UNBIND_OK = 51;
    public static final int QUEUE_PURGE = 30;
    public static final int QUEUE_PURGE_OK = 31;
    public static final int QUEUE_DELETE = 40;
    public static final int QUEUE_DELETE_OK = 41;

    // ── basic (class 60) ──
    public static final int CLASS_BASIC = 60;
    public static final int BASIC_QOS = 10;
    public static final int BASIC_QOS_OK = 11;
    public static final int BASIC_CONSUME = 20;
    public static final int BASIC_CONSUME_OK = 21;
    public static final int BASIC_CANCEL = 30;
    public static final int BASIC_CANCEL_OK = 31;
    public static final int BASIC_PUBLISH = 40;
    public static final int BASIC_RETURN = 50;
    public static final int BASIC_DELIVER = 60;
    public static final int BASIC_GET = 70;
    public static final int BASIC_GET_OK = 71;
    public static final int BASIC_GET_EMPTY = 72;
    public static final int BASIC_ACK = 80;
    public static final int BASIC_REJECT = 90;
    public static final int BASIC_RECOVER_ASYNC = 100;
    public static final int BASIC_RECOVER = 110;
    public static final int BASIC_RECOVER_OK = 111;
    public static final int BASIC_NACK = 120;

    // ── tx (class 90) ──
    public static final int CLASS_TX = 90;
    public static final int TX_SELECT = 10;
    public static final int TX_SELECT_OK = 11;
    public static final int TX_COMMIT = 20;
    public static final int TX_COMMIT_OK = 21;
    public static final int TX_ROLLBACK = 30;
    public static final int TX_ROLLBACK_OK = 31;

    // ── confirm (class 85, RabbitMQ extension) ──
    public static final int CLASS_CONFIRM = 85;
    public static final int CONFIRM_SELECT = 10;
    public static final int CONFIRM_SELECT_OK = 11;

    /** Packs a class ID and method ID into the {@code (classId << 16) | methodId} key some tables use. */
    public static int key(int classId, int methodId) {
        return (classId << 16) | (methodId & 0xFFFF);
    }
}
