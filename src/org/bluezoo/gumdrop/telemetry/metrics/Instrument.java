/*
 * Instrument.java
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
 * Base interface for all metric instruments.
 * An instrument is a tool for recording measurements.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Instrument {

    /**
     * Returns the instrument name.
     */
    String getName();

    /**
     * Returns the instrument description.
     */
    String getDescription();

    /**
     * Returns the unit of measurement.
     */
    String getUnit();

    /**
     * Collects the current metric data for export.
     * Resets delta values if using delta temporality.
     *
     * @param temporality the aggregation temporality to use
     * @return the metric data, or null if no data to export
     */
    MetricData collect(AggregationTemporality temporality);

}

