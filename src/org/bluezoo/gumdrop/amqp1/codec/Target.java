/*
 * Target.java
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

package org.bluezoo.gumdrop.amqp1.codec;

import java.util.List;

/**
 * The {@code target} terminus of a link (core specification 3.5.4):
 * where messages go. For a sending client it names the node to publish
 * to; for a receiving client it names the client's own (usually empty)
 * end. Node addresses are broker-specific.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Target extends Terminus {

    public Target() {
    }

    /** @param address the node address */
    public Target(String address) {
        setAddress(address);
    }

    void write(Amqp1Encoder out) {
        commonFields()
                .addSymbols(getCapabilities())
                .writeTo(out, Performative.DESCRIPTOR_TARGET);
    }

    static Target fromDescribed(Object value) throws Amqp1ProtocolException {
        if (value == null) {
            return null;
        }
        List<Object> f = fieldsOf(value, Performative.DESCRIPTOR_TARGET, "target");
        Target t = new Target();
        t.readCommon(f);
        t.getCapabilities().addAll(Fields.symbols(f, 6, "capabilities"));
        return t;
    }
}
