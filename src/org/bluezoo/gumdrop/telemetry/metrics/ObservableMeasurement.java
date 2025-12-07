/*
 * ObservableMeasurement.java
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
 * Interface for recording measurements in observable instrument callbacks.
 * Passed to the callback function at collection time.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ObservableMeasurement {

    /**
     * Records a long value.
     *
     * @param value the value to record
     */
    void record(long value);

    /**
     * Records a long value with attributes.
     *
     * @param value the value to record
     * @param attributes the attributes for this measurement
     */
    void record(long value, Attributes attributes);

    /**
     * Records a double value.
     *
     * @param value the value to record
     */
    void record(double value);

    /**
     * Records a double value with attributes.
     *
     * @param value the value to record
     * @param attributes the attributes for this measurement
     */
    void record(double value, Attributes attributes);

}

