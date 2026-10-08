/*
 * TeeExporter.java
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

package org.bluezoo.gumdrop.telemetry;

import org.bluezoo.gumdrop.telemetry.metrics.MetricData;

import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Delivers everything it is given to two exporters. A failure in one
 * does not skip the other. More than two destinations are a nest of
 * tees, and the same exporter may be a child of more than one tee.
 *
 * <pre>
 * telemetry.setExporter(new TeeExporter(new QlogExporter(dir), new DefaultExporter()));
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class TeeExporter implements TelemetryExporter {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.telemetry.L10N");
    // Raw JUL: a failing child must not be reported through the pipeline it sits in.
    private static final Logger logger = Logger.getLogger(TeeExporter.class.getName());

    private final TelemetryExporter first;
    private final TelemetryExporter second;

    /**
     * Creates a tee.
     *
     * @param first one child
     * @param second the other
     */
    public TeeExporter(TelemetryExporter first, TelemetryExporter second) {
        if (first == null || second == null) {
            throw new IllegalArgumentException("a tee needs two exporters");
        }
        this.first = first;
        this.second = second;
    }

    @Override
    public void init(TelemetryConfig config) {
        try {
            first.init(config);
        } catch (RuntimeException e) {
            failed(first, e);
        }
        try {
            second.init(config);
        } catch (RuntimeException e) {
            failed(second, e);
        }
    }

    @Override
    public void export(Trace trace) {
        try {
            first.export(trace);
        } catch (RuntimeException e) {
            failed(first, e);
        }
        try {
            second.export(trace);
        } catch (RuntimeException e) {
            failed(second, e);
        }
    }

    @Override
    public void export(LogRecord record) {
        try {
            first.export(record);
        } catch (RuntimeException e) {
            failed(first, e);
        }
        try {
            second.export(record);
        } catch (RuntimeException e) {
            failed(second, e);
        }
    }

    @Override
    public void export(List<MetricData> metrics) {
        try {
            first.export(metrics);
        } catch (RuntimeException e) {
            failed(first, e);
        }
        try {
            second.export(metrics);
        } catch (RuntimeException e) {
            failed(second, e);
        }
    }

    @Override
    public boolean accepts(LogLevel level) {
        return first.accepts(level) || second.accepts(level);
    }

    @Override
    public boolean acceptsTraces() {
        return first.acceptsTraces() || second.acceptsTraces();
    }

    @Override
    public void flush() {
        try {
            first.flush();
        } catch (RuntimeException e) {
            failed(first, e);
        }
        try {
            second.flush();
        } catch (RuntimeException e) {
            failed(second, e);
        }
    }

    @Override
    public void forceFlush() {
        try {
            first.forceFlush();
        } catch (RuntimeException e) {
            failed(first, e);
        }
        try {
            second.forceFlush();
        } catch (RuntimeException e) {
            failed(second, e);
        }
    }

    @Override
    public void shutdown() {
        try {
            first.shutdown();
        } catch (RuntimeException e) {
            failed(first, e);
        }
        try {
            second.shutdown();
        } catch (RuntimeException e) {
            failed(second, e);
        }
    }

    private static void failed(TelemetryExporter child, RuntimeException e) {
        logger.log(Level.WARNING, MessageFormat.format(L10N.getString("warn.exporter_failed"),
                child.getClass().getName(), e.toString()), e);
    }

}
