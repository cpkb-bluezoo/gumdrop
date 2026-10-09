/*
 * EventLogger.java
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

import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Emits operational log events for one class, the instrumentation scope,
 * whose messages come from one resource bundle. Obtained from the
 * runtime's {@link TelemetryConfig#getLogger}, never constructed, so
 * that every event reaches the exporters that configuration composed.
 *
 * <pre>
 * private final EventLogger events = config.getLogger(Stream.class, L10N);
 * ...
 * events.warn("warn.server_push_failed").attr("uri", uri).thrown(e).emit();
 * </pre>
 *
 * <p>Events of {@code FINE} and finer stay on {@link java.util.logging}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class EventLogger {

    // Raw JUL: a failing exporter must not be reported through itself.
    private static final Logger logger = Logger.getLogger(EventLogger.class.getName());

    // Set while a thread is inside an exporter, so that an exporter that
    // emits an event on the way does not come back in through here.
    private static final ThreadLocal<boolean[]> EMITTING = new ThreadLocal<boolean[]>() {
        @Override
        protected boolean[] initialValue() {
            return new boolean[1];
        }
    };

    private final TelemetryConfig config;
    private final String scope;
    private final ResourceBundle bundle;

    EventLogger(TelemetryConfig config, String scope, ResourceBundle bundle) {
        this.config = config;
        this.scope = scope;
        this.bundle = bundle;
    }

    /**
     * Returns the instrumentation scope: the name of the emitting class.
     *
     * @return the scope
     */
    public String getScope() {
        return scope;
    }

    /**
     * Returns the resource bundle event keys are looked up in.
     *
     * @return the bundle
     */
    public ResourceBundle getResourceBundle() {
        return bundle;
    }

    /**
     * Returns whether any configured exporter takes events of a level,
     * for a caller that would otherwise build an expensive one for nothing.
     *
     * @param level the level
     * @return true if an exporter accepts it
     */
    public boolean accepts(LogLevel level) {
        return config.accepts(level);
    }

    /**
     * Starts an informational event.
     *
     * @param key the resource-bundle key naming the event
     * @return the record, to add attributes to and {@linkplain LogRecord#emit emit}
     */
    public LogRecord info(String key) {
        return new LogRecord(this, LogLevel.INFO, key);
    }

    /**
     * Starts a warning event.
     *
     * @param key the resource-bundle key naming the event
     * @return the record, to add attributes to and {@linkplain LogRecord#emit emit}
     */
    public LogRecord warn(String key) {
        return new LogRecord(this, LogLevel.WARN, key);
    }

    /**
     * Starts an error event.
     *
     * @param key the resource-bundle key naming the event
     * @return the record, to add attributes to and {@linkplain LogRecord#emit emit}
     */
    public LogRecord error(String key) {
        return new LogRecord(this, LogLevel.ERROR, key);
    }

    void emit(LogRecord record) {
        if (!config.accepts(record.getLevel())) {
            return;
        }
        boolean[] emitting = EMITTING.get();
        if (emitting[0]) {
            return;
        }
        emitting[0] = true;
        try {
            config.getExporter().export(record);
        } catch (RuntimeException e) {
            logger.log(Level.FINE, e.toString(), e);
        } finally {
            emitting[0] = false;
        }
    }

}
