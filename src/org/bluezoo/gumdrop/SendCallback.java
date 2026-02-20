/*
 * SendCallback.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop;

import java.nio.ByteBuffer;

/**
 * Callback interface for handling data sent via an {@link Endpoint}.
 * This allows test classes to intercept and capture sent data without involving the server.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface SendCallback {

    /**
     * Called when data is sent via an endpoint.
     * @param endpoint the endpoint sending the data
     * @param buf the data being sent, or null to close after send
     */
    void onSend(Endpoint endpoint, ByteBuffer buf);

}
