/*
 * ResponseRecorder.java
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

import java.util.ArrayList;
import java.util.List;

/**
 * Records the events of a server response for test doubles of
 * {@link org.bluezoo.gumdrop.http.server.HttpResponse}: the final status
 * (interim 1xx responses and their fields are ignored) and every field
 * given after it, header or trailer, in order.
 *
 * <p>A stub forwards its {@code status}, {@code header}, {@code endHeaders},
 * {@code bodyContent} and {@code endMessage} events to the matching methods
 * here and asserts on {@link #getStatus()}, {@link #getValue} and so on.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ResponseRecorder {

    private int status;
    private boolean interim;
    private boolean started;
    private boolean complete;
    private final List<String[]> fields = new ArrayList<String[]>();

    /** Records a status; 1xx starts an interim response that is ignored. */
    public void status(int code) {
        if (started) {
            return;
        }
        if (code >= 100 && code < 200) {
            interim = true;
            return;
        }
        interim = false;
        status = code;
        started = true;
    }

    /** Records a field of the final response (header or trailer). */
    public void header(String name, String value) {
        if (started && !interim) {
            fields.add(new String[] { name, value });
        }
    }

    /** An interim response is over once its header section ends. */
    public void endHeaders() {
        interim = false;
    }

    /** The first body chunk starts a response that had no explicit status. */
    public void bodyContent() {
        startDefault();
    }

    /** Ends the response, with status 200 if none was given. */
    public void endMessage() {
        startDefault();
        complete = true;
    }

    private void startDefault() {
        if (!started) {
            started = true;
            interim = false;
            status = 200;
        }
    }

    /** Returns whether a final status has been given or defaulted. */
    public boolean isStarted() {
        return started;
    }

    /** Returns whether endMessage was called. */
    public boolean isComplete() {
        return complete;
    }

    /** Returns the final status code, or 0 if none yet. */
    public int getStatus() {
        return status;
    }

    /**
     * Returns the first value of a field (case-insensitive), or null. The
     * pseudo name {@code :status} gives the final status code.
     */
    public String getValue(String name) {
        if (!started) {
            return null;
        }
        if (":status".equals(name)) {
            return Integer.toString(status);
        }
        for (String[] f : fields) {
            if (f[0].equalsIgnoreCase(name)) {
                return f[1];
            }
        }
        return null;
    }

    /** Returns all values of a field (case-insensitive). */
    public List<String> getValues(String name) {
        List<String> values = new ArrayList<String>();
        for (String[] f : fields) {
            if (f[0].equalsIgnoreCase(name)) {
                values.add(f[1]);
            }
        }
        return values;
    }

    /** Returns every recorded field as {name, value}, in order. */
    public List<String[]> getFields() {
        return fields;
    }
}
