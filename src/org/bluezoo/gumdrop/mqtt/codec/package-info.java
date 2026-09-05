/*
 * package-info.java
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

/**
 * MQTT packet encoding and decoding, for both 3.1.1 and 5.0 (the wire
 * difference between them is properties, {@link
 * org.bluezoo.gumdrop.mqtt.codec.MQTTProperties}, present only in 5.0).
 *
 * <p>{@link org.bluezoo.gumdrop.mqtt.codec.MQTTFrameParser} is a
 * streaming push-parser delivering decoded packets to a {@link
 * org.bluezoo.gumdrop.mqtt.codec.MQTTEventHandler}; {@link
 * org.bluezoo.gumdrop.mqtt.codec.MQTTPacketEncoder} writes them back.
 * {@link org.bluezoo.gumdrop.mqtt.codec.VariableLengthEncoding}
 * implements MQTT's variable-length integer encoding for remaining
 * length and property-length fields; {@link
 * org.bluezoo.gumdrop.mqtt.codec.MQTTPacketType} and {@link
 * org.bluezoo.gumdrop.mqtt.codec.QoS} hold the packet type and quality-
 * of-service constants; {@link org.bluezoo.gumdrop.mqtt.codec.ConnectPacket}
 * is the decoded CONNECT packet both client and broker build a session
 * from.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.mqtt
 * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html">MQTT 5.0</a>
 */
package org.bluezoo.gumdrop.mqtt.codec;
