/*
 * StubTelemetry.java
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

package org.bluezoo.gumdrop.testsupport;

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

/**
 * The telemetry configuration a stub {@link org.bluezoo.gumdrop.Endpoint}
 * returns when a test has not given it one: the default, whose exporter
 * prints log events through {@code java.util.logging} and takes nothing
 * else. An endpoint's configuration is never null.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class StubTelemetry {

    /** The shared default configuration. */
    public static final TelemetryConfig CONFIG = new TelemetryConfig();

    private StubTelemetry() {
    }

}
