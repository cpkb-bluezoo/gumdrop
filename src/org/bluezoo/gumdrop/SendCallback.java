/*
 * Copyright (C) 2025 Mimecast Services Limited, Chris Burdess <dog@gnu.org>
 *
 * This program is dual licensed under the GNU General Public License version 3 
 * and is also available under a commercial license from Mimecast Services Limited.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Commercial license: Businesses and organizations who wish to use this software
 * under different terms should contact Mimecast Services Limited.
 */
package org.bluezoo.gumdrop;

import java.nio.ByteBuffer;

/**
 * Callback interface for handling data sent via {@link Connection#send(ByteBuffer)}.
 * This allows test classes to intercept and capture sent data without involving the server.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface SendCallback {

    /**
     * Called when data is sent via a connection.
     * @param connection the connection sending the data
     * @param buf the data being sent, or null to close after send
     */
    void onSend(Connection connection, ByteBuffer buf);

}
