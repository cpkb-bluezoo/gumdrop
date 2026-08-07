/*
 * FlowListener.java
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

package org.bluezoo.gumdrop.amqp.client.handler;

/**
 * Notified when the broker sends {@code channel.flow} on this channel,
 * asking the client to pause or resume sending content (typically
 * {@code basic.publish}) — e.g. because the broker is under memory
 * pressure. The client automatically acknowledges with {@code
 * channel.flow-ok} regardless of whether a listener is registered;
 * actually pausing publishes is the application's responsibility (this
 * client does not queue or block writes on its own).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientChannel#setFlowListener
 * @see ClientChannel#flow
 */
public interface FlowListener {

    /**
     * @param active {@code false} means stop publishing on this channel
     *      until a subsequent call with {@code true}
     */
    void onFlow(boolean active);
}
