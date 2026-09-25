/*
 * Begin.java
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
 * The {@code begin} performative (core specification 2.7.2): indicates
 * that a session has begun on a channel.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Begin extends Performative {

    /** Default {@code handle-max}: 2^32 - 1. */
    public static final long DEFAULT_HANDLE_MAX = 0xFFFFFFFFL;

    private Integer remoteChannel;
    private long nextOutgoingId;
    private long incomingWindow;
    private long outgoingWindow;
    private Long handleMax;
    private List<String> offeredCapabilities = new ArrayList<String>();
    private List<String> desiredCapabilities = new ArrayList<String>();
    private Map<Object, Object> properties = new LinkedHashMap<Object, Object>();

    /**
     * @param nextOutgoingId the transfer-id of the first transfer this session will send
     * @param incomingWindow the initial incoming-window size
     * @param outgoingWindow the initial outgoing-window size
     */
    public Begin(long nextOutgoingId, long incomingWindow, long outgoingWindow) {
        this.nextOutgoingId = nextOutgoingId;
        this.incomingWindow = incomingWindow;
        this.outgoingWindow = outgoingWindow;
    }

    @Override
    public long getDescriptor() {
        return DESCRIPTOR_BEGIN;
    }

    /**
     * The channel the peer used to begin the session this one replies
     * to; {@code null} if this {@code begin} initiates the session.
     */
    public Integer getRemoteChannel() {
        return remoteChannel;
    }

    public void setRemoteChannel(Integer remoteChannel) {
        this.remoteChannel = remoteChannel;
    }

    public long getNextOutgoingId() {
        return nextOutgoingId;
    }

    public long getIncomingWindow() {
        return incomingWindow;
    }

    public long getOutgoingWindow() {
        return outgoingWindow;
    }

    /** The highest link handle the sender will use; {@link #DEFAULT_HANDLE_MAX} if unset. */
    public long getHandleMax() {
        return handleMax == null ? DEFAULT_HANDLE_MAX : handleMax.longValue();
    }

    public void setHandleMax(long handleMax) {
        this.handleMax = Long.valueOf(handleMax);
    }

    public List<String> getOfferedCapabilities() {
        return offeredCapabilities;
    }

    public List<String> getDesiredCapabilities() {
        return desiredCapabilities;
    }

    /** Session properties (symbol keys); never null. */
    public Map<Object, Object> getProperties() {
        return properties;
    }

    @Override
    public void write(Amqp1Encoder out) {
        new Amqp1ListBuilder()
                .addUshort(remoteChannel)
                .addUint(Long.valueOf(nextOutgoingId))
                .addUint(Long.valueOf(incomingWindow))
                .addUint(Long.valueOf(outgoingWindow))
                .addUint(handleMax)
                .addSymbols(offeredCapabilities)
                .addSymbols(desiredCapabilities)
                .addMap(properties)
                .writeTo(out, DESCRIPTOR_BEGIN);
    }

    static Begin fromFields(List<Object> f) throws Amqp1ProtocolException {
        long next = Fields.required(Fields.unsigned(f, 1, "next-outgoing-id"),
                "begin", "next-outgoing-id").longValue();
        long in = Fields.required(Fields.unsigned(f, 2, "incoming-window"),
                "begin", "incoming-window").longValue();
        long outWin = Fields.required(Fields.unsigned(f, 3, "outgoing-window"),
                "begin", "outgoing-window").longValue();
        Begin b = new Begin(next, in, outWin);
        Long remote = Fields.unsigned(f, 0, "remote-channel");
        b.remoteChannel = remote == null ? null : Integer.valueOf(remote.intValue());
        b.handleMax = Fields.unsigned(f, 4, "handle-max");
        b.offeredCapabilities = Fields.symbols(f, 5, "offered-capabilities");
        b.desiredCapabilities = Fields.symbols(f, 6, "desired-capabilities");
        b.properties = Fields.map(f, 7, "properties");
        return b;
    }
}
