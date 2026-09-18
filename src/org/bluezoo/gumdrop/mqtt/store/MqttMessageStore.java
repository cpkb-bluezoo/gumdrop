/*
 * MqttMessageStore.java
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

package org.bluezoo.gumdrop.mqtt.store;

/**
 * Factory for MQTT message payload storage.
 *
 * <p>Provides {@link MqttMessageWriter} instances for accumulating
 * inbound PUBLISH payloads. The default implementation
 * ({@link InMemoryMessageStore}) buffers payloads in memory;
 * subclasses may override to provide file-backed or database-backed
 * storage for large messages.
 *
 * <p>Obtained from {@link org.bluezoo.gumdrop.mqtt.server.MqttServer#createMessageStore()},
 * which subclasses may override to supply a custom implementation.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MqttMessageWriter
 * @see MqttMessageContent
 * @see InMemoryMessageStore
 */
public interface MqttMessageStore {

    /**
     * Creates a new writer for accumulating message payload data.
     *
     * <p>The caller writes payload bytes via the
     * {@link java.nio.channels.WritableByteChannel} interface, then
     * calls {@link MqttMessageWriter#commit()} to obtain a readable
     * {@link MqttMessageContent} handle, or
     * {@link MqttMessageWriter#discard()} to abandon the write.
     *
     * @return a new message writer
     */
    MqttMessageWriter createWriter();
}
