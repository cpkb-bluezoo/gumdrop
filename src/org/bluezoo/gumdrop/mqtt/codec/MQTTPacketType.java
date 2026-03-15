/*
 * MQTTPacketType.java
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
 * MQTT control packet types (MQTT 3.1.1 section 2.2.1, MQTT 5.0 section 2.1.2).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum MQTTPacketType {

    CONNECT(1),
    CONNACK(2),
    PUBLISH(3),
    PUBACK(4),
    PUBREC(5),
    PUBREL(6),
    PUBCOMP(7),
    SUBSCRIBE(8),
    SUBACK(9),
    UNSUBSCRIBE(10),
    UNSUBACK(11),
    PINGREQ(12),
    PINGRESP(13),
    DISCONNECT(14),
    AUTH(15);

    private final int value;

    MQTTPacketType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    private static final MQTTPacketType[] LOOKUP = new MQTTPacketType[16];

    static {
        for (MQTTPacketType type : values()) {
            LOOKUP[type.value] = type;
        }
    }

    /**
     * Returns the packet type for the given numeric value.
     *
     * @param value the packet type value (1-15)
     * @return the packet type, or null if invalid
     */
    public static MQTTPacketType fromValue(int value) {
        if (value < 1 || value > 15) {
            return null;
        }
        return LOOKUP[value];
    }
}
