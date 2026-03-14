/*
 * ProtoDefaultHandler.java
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

package org.bluezoo.gumdrop.grpc.proto;

/**
 * Default implementation of {@link ProtoMessageHandler} that does nothing.
 * Subclass to implement only the methods you need.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ProtoDefaultHandler implements ProtoMessageHandler {

    @Override
    public void setLocator(ProtoLocator locator) {
    }

    @Override
    public void startMessage(String typeName) throws ProtoParseException {
    }

    @Override
    public void endMessage() throws ProtoParseException {
    }

    @Override
    public void field(String name, Object value) throws ProtoParseException {
    }

    @Override
    public void startField(String name, String typeName) throws ProtoParseException {
    }

    @Override
    public void endField() throws ProtoParseException {
    }
}
