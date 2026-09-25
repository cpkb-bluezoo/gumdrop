/*
 * Open.java
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
 * The {@code open} performative (core specification 2.7.1): each peer
 * sends one to negotiate connection parameters. Both peers may send it
 * immediately after the protocol header exchange without waiting for the
 * other.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Open extends Performative {

    /** Default {@code max-frame-size}: unlimited (2^32 - 1). */
    public static final long DEFAULT_MAX_FRAME_SIZE = 0xFFFFFFFFL;
    /** Default {@code channel-max}. */
    public static final int DEFAULT_CHANNEL_MAX = 65535;

    private String containerId;
    private String hostname;
    private Long maxFrameSize;
    private Integer channelMax;
    private Long idleTimeOut;
    private List<String> outgoingLocales = new ArrayList<String>();
    private List<String> incomingLocales = new ArrayList<String>();
    private List<String> offeredCapabilities = new ArrayList<String>();
    private List<String> desiredCapabilities = new ArrayList<String>();
    private Map<Object, Object> properties = new LinkedHashMap<Object, Object>();

    public Open(String containerId) {
        this.containerId = containerId;
    }

    @Override
    public long getDescriptor() {
        return DESCRIPTOR_OPEN;
    }

    /** The identifier of the sending container. */
    public String getContainerId() {
        return containerId;
    }

    /** The name of the host the sender is connecting to, or {@code null}. */
    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    /** The largest frame the sender can receive; {@link #DEFAULT_MAX_FRAME_SIZE} if unset. */
    public long getMaxFrameSize() {
        return maxFrameSize == null ? DEFAULT_MAX_FRAME_SIZE : maxFrameSize.longValue();
    }

    public void setMaxFrameSize(long maxFrameSize) {
        this.maxFrameSize = Long.valueOf(maxFrameSize);
    }

    /** The highest channel number the sender will use; {@link #DEFAULT_CHANNEL_MAX} if unset. */
    public int getChannelMax() {
        return channelMax == null ? DEFAULT_CHANNEL_MAX : channelMax.intValue();
    }

    public void setChannelMax(int channelMax) {
        this.channelMax = Integer.valueOf(channelMax);
    }

    /**
     * The idle timeout in milliseconds after which the sender will close
     * the connection if it receives no frames, or 0 if it has none.
     */
    public long getIdleTimeOut() {
        return idleTimeOut == null ? 0L : idleTimeOut.longValue();
    }

    public void setIdleTimeOut(long idleTimeOut) {
        this.idleTimeOut = Long.valueOf(idleTimeOut);
    }

    public List<String> getOutgoingLocales() {
        return outgoingLocales;
    }

    public List<String> getIncomingLocales() {
        return incomingLocales;
    }

    public List<String> getOfferedCapabilities() {
        return offeredCapabilities;
    }

    public List<String> getDesiredCapabilities() {
        return desiredCapabilities;
    }

    /** Connection properties (symbol keys); never null. */
    public Map<Object, Object> getProperties() {
        return properties;
    }

    @Override
    public void write(Amqp1Encoder out) {
        if (containerId == null) {
            throw new IllegalStateException("open requires a container-id");
        }
        new Amqp1ListBuilder()
                .addString(containerId)
                .addString(hostname)
                .addUint(maxFrameSize)
                .addUshort(channelMax)
                .addUint(idleTimeOut)
                .addSymbols(outgoingLocales)
                .addSymbols(incomingLocales)
                .addSymbols(offeredCapabilities)
                .addSymbols(desiredCapabilities)
                .addMap(properties)
                .writeTo(out, DESCRIPTOR_OPEN);
    }

    static Open fromFields(List<Object> f) throws Amqp1ProtocolException {
        Open o = new Open(Fields.required(Fields.string(f, 0, "container-id"),
                "open", "container-id"));
        o.hostname = Fields.string(f, 1, "hostname");
        o.maxFrameSize = Fields.unsigned(f, 2, "max-frame-size");
        Long channelMax = Fields.unsigned(f, 3, "channel-max");
        o.channelMax = channelMax == null ? null : Integer.valueOf(channelMax.intValue());
        o.idleTimeOut = Fields.unsigned(f, 4, "idle-time-out");
        o.outgoingLocales = Fields.symbols(f, 5, "outgoing-locales");
        o.incomingLocales = Fields.symbols(f, 6, "incoming-locales");
        o.offeredCapabilities = Fields.symbols(f, 7, "offered-capabilities");
        o.desiredCapabilities = Fields.symbols(f, 8, "desired-capabilities");
        o.properties = Fields.map(f, 9, "properties");
        return o;
    }
}
