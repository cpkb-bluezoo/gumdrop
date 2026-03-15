/*
 * MQTTVersion.java
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
 * MQTT protocol version constants.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum MQTTVersion {

    /** MQTT 3.1.1 (protocol level 4). */
    V3_1_1(4, "MQTT"),

    /** MQTT 5.0 (protocol level 5). */
    V5_0(5, "MQTT");

    private final int protocolLevel;
    private final String protocolName;

    MQTTVersion(int protocolLevel, String protocolName) {
        this.protocolLevel = protocolLevel;
        this.protocolName = protocolName;
    }

    public int getProtocolLevel() {
        return protocolLevel;
    }

    public String getProtocolName() {
        return protocolName;
    }

    /**
     * Returns the version for the given protocol level byte.
     *
     * @param level the protocol level (4 for 3.1.1, 5 for 5.0)
     * @return the version, or null if unrecognized
     */
    public static MQTTVersion fromProtocolLevel(int level) {
        switch (level) {
            case 4: return V3_1_1;
            case 5: return V5_0;
            default: return null;
        }
    }
}
