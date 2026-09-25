/*
 * Source.java
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
 * The {@code source} terminus of a link (core specification 3.5.3): where
 * messages come from. For a sending client it names the client's own
 * (usually empty) end; for a receiving client it names the node to
 * consume from. Node addresses are broker-specific.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Source extends Terminus {

    /** Distribution mode: each message goes to one consumer (a queue). */
    public static final String DISTRIBUTION_MOVE = "move";
    /** Distribution mode: each consumer sees every message (a topic). */
    public static final String DISTRIBUTION_COPY = "copy";

    private String distributionMode;
    private Map<Object, Object> filter = new LinkedHashMap<Object, Object>();
    private DeliveryState defaultOutcome;
    private List<String> outcomes = new ArrayList<String>();

    public Source() {
    }

    /** @param address the node address */
    public Source(String address) {
        setAddress(address);
    }

    /** {@link #DISTRIBUTION_MOVE} or {@link #DISTRIBUTION_COPY}, or {@code null}. */
    public String getDistributionMode() {
        return distributionMode;
    }

    public void setDistributionMode(String distributionMode) {
        this.distributionMode = distributionMode;
    }

    /**
     * Message filters, keyed by symbol name. Values are usually
     * {@link Amqp1Described} (for example a selector filter).
     */
    public Map<Object, Object> getFilter() {
        return filter;
    }

    /** The outcome applied to messages that are never disposed of, or {@code null}. */
    public DeliveryState getDefaultOutcome() {
        return defaultOutcome;
    }

    public void setDefaultOutcome(DeliveryState defaultOutcome) {
        this.defaultOutcome = defaultOutcome;
    }

    /** Outcomes (symbols such as {@code amqp:accepted:list}) the source supports. */
    public List<String> getOutcomes() {
        return outcomes;
    }

    void write(Amqp1Encoder out) {
        Amqp1Encoder outcome = null;
        if (defaultOutcome != null) {
            outcome = new Amqp1Encoder();
            defaultOutcome.write(outcome);
        }
        Amqp1ListBuilder list = commonFields()
                .addSymbol(distributionMode)
                .addMap(filter);
        if (outcome == null) {
            list.addNull();
        } else {
            list.addEncoded(outcome);
        }
        list.addSymbols(outcomes)
                .addSymbols(getCapabilities())
                .writeTo(out, Performative.DESCRIPTOR_SOURCE);
    }

    static Source fromDescribed(Object value) throws Amqp1ProtocolException {
        if (value == null) {
            return null;
        }
        List<Object> f = fieldsOf(value, Performative.DESCRIPTOR_SOURCE, "source");
        Source s = new Source();
        s.readCommon(f);
        s.distributionMode = Fields.symbol(f, 6, "distribution-mode");
        s.filter = Fields.map(f, 7, "filter");
        s.defaultOutcome = DeliveryState.fromDescribed(Fields.get(f, 8));
        s.outcomes = Fields.symbols(f, 9, "outcomes");
        s.getCapabilities().addAll(Fields.symbols(f, 10, "capabilities"));
        return s;
    }
}
