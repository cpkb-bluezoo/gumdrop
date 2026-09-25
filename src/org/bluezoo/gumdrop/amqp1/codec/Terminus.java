/*
 * Terminus.java
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fields common to the two ends of a link, {@link Source} and
 * {@link Target} (core specification 3.5.3 and 3.5.4): the node
 * address and how long the terminus and its state should live.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class Terminus {

    /** Terminus durability: no state is retained. */
    public static final long DURABLE_NONE = 0;
    /** Terminus durability: the terminus configuration is retained. */
    public static final long DURABLE_CONFIGURATION = 1;
    /** Terminus durability: configuration and unsettled state are retained. */
    public static final long DURABLE_UNSETTLED_STATE = 2;

    /** Expiry policy: the terminus expires when the link detaches (the default). */
    public static final String EXPIRY_LINK_DETACH = "link-detach";
    public static final String EXPIRY_SESSION_END = "session-end";
    public static final String EXPIRY_CONNECTION_CLOSE = "connection-close";
    public static final String EXPIRY_NEVER = "never";

    private String address;
    private Long durable;
    private String expiryPolicy;
    private Long timeout;
    private boolean dynamic;
    private Map<Object, Object> dynamicNodeProperties = new LinkedHashMap<Object, Object>();
    private List<String> capabilities = new ArrayList<String>();

    /** The address of the node, or {@code null} if none or dynamic. */
    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    /** The durability, one of the {@code DURABLE_} constants; none if unset. */
    public long getDurable() {
        return durable == null ? DURABLE_NONE : durable.longValue();
    }

    public void setDurable(long durable) {
        this.durable = Long.valueOf(durable);
    }

    /** The expiry policy, or {@code null} for the default ({@link #EXPIRY_LINK_DETACH}). */
    public String getExpiryPolicy() {
        return expiryPolicy;
    }

    public void setExpiryPolicy(String expiryPolicy) {
        this.expiryPolicy = expiryPolicy;
    }

    /** Seconds the terminus survives after its expiry policy triggers; 0 if unset. */
    public long getTimeout() {
        return timeout == null ? 0L : timeout.longValue();
    }

    public void setTimeout(long timeout) {
        this.timeout = Long.valueOf(timeout);
    }

    /** Whether the peer should create the node dynamically on request. */
    public boolean isDynamic() {
        return dynamic;
    }

    public void setDynamic(boolean dynamic) {
        this.dynamic = dynamic;
    }

    public Map<Object, Object> getDynamicNodeProperties() {
        return dynamicNodeProperties;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    /** Adds the seven fields shared by both terminus types, in order. */
    Amqp1ListBuilder commonFields() {
        return new Amqp1ListBuilder()
                .addString(address)
                .addUint(durable)
                .addSymbol(expiryPolicy)
                .addUint(timeout)
                .addBoolean(dynamic ? Boolean.TRUE : null)
                .addMap(dynamicNodeProperties);
    }

    void readCommon(List<Object> f) throws Amqp1ProtocolException {
        address = Fields.string(f, 0, "address");
        durable = Fields.unsigned(f, 1, "durable");
        expiryPolicy = Fields.symbol(f, 2, "expiry-policy");
        timeout = Fields.unsigned(f, 3, "timeout");
        Boolean d = Fields.bool(f, 4, "dynamic");
        dynamic = d != null && d.booleanValue();
        dynamicNodeProperties = Fields.map(f, 5, "dynamic-node-properties");
    }

    /** Unwraps a described terminus field to its field list, checking the descriptor. */
    @SuppressWarnings("unchecked")
    static List<Object> fieldsOf(Object value, long descriptor, String name)
            throws Amqp1ProtocolException {
        if (!(value instanceof Amqp1Described)) {
            throw new Amqp1ProtocolException(name + " is not a described type");
        }
        Amqp1Described d = (Amqp1Described) value;
        if (!(d.getDescriptor() instanceof Long)
                || ((Long) d.getDescriptor()).longValue() != descriptor
                || !(d.getValue() instanceof List)) {
            throw new Amqp1ProtocolException(name + " has unsupported descriptor "
                    + d.getDescriptor());
        }
        return (List<Object>) d.getValue();
    }
}
