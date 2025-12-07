/*
 * AggregationTemporality.java
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

package org.bluezoo.gumdrop.telemetry.metrics;

/**
 * Defines how metric values are aggregated over time.
 * 
 * <p>This affects how counters and histograms report their values:
 * <ul>
 *   <li>DELTA - Values represent the change since the last export
 *   <li>CUMULATIVE - Values represent the total since the process started
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum AggregationTemporality {

    /**
     * Unspecified temporality (should not be used in practice).
     */
    UNSPECIFIED(0),

    /**
     * Delta temporality: values represent the change since the last export.
     * Preferred for stateless collectors and push-based systems.
     */
    DELTA(1),

    /**
     * Cumulative temporality: values represent the total since process start.
     * Preferred for Prometheus-style scrapers and pull-based systems.
     */
    CUMULATIVE(2);

    private final int protoValue;

    AggregationTemporality(int protoValue) {
        this.protoValue = protoValue;
    }

    /**
     * Returns the OTLP protobuf enum value.
     */
    public int getProtoValue() {
        return protoValue;
    }

    /**
     * Returns the temporality for a given protobuf value.
     */
    public static AggregationTemporality fromProtoValue(int value) {
        for (AggregationTemporality t : values()) {
            if (t.protoValue == value) {
                return t;
            }
        }
        return UNSPECIFIED;
    }

}

