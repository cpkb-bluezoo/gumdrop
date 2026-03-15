/*
 * QoS.java
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

package org.bluezoo.gumdrop.mqtt.codec;

/**
 * MQTT Quality of Service levels.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum QoS {

    /** At most once delivery. No acknowledgment. */
    AT_MOST_ONCE(0),

    /** At least once delivery. PUBACK acknowledgment. */
    AT_LEAST_ONCE(1),

    /** Exactly once delivery. Four-step handshake. */
    EXACTLY_ONCE(2);

    private final int value;

    QoS(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /**
     * Returns the QoS for the given numeric value.
     *
     * @param value 0, 1, or 2
     * @return the QoS level, or null if invalid
     */
    public static QoS fromValue(int value) {
        switch (value) {
            case 0: return AT_MOST_ONCE;
            case 1: return AT_LEAST_ONCE;
            case 2: return EXACTLY_ONCE;
            default: return null;
        }
    }
}
