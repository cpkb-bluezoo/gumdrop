/*
 * StoryHandler.java
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

package org.bluezoo.gumdrop.http.hpack;

import java.util.*;
import org.bluezoo.json.*;
import org.bluezoo.gumdrop.http.Header;

/**
 * Story JSON handler
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class StoryHandler extends JSONDefaultHandler {

    int seqno;
    String headerName;
    List<Header> headers;
    String wire;
    private Deque<String> stack;
    StoryTestInterface test;

    StoryHandler(StoryTestInterface test) {
        this.test = test;
        stack = new ArrayDeque<>();
        stack.addLast("");
    }

    public void startObject() throws JSONException {
    }

    public void startArray() throws JSONException {
        String state = stack.getLast();
        switch (state) {
            case "headers":
                headers = new ArrayList<>();
                break;
        }
    }

    public void endObject() throws JSONException {
        String state = stack.getLast();
        switch (state) {
            case "cases":
                runTest(seqno, wire, headers);
                headers = null;
                break;
        }
    }

    public void endArray() throws JSONException {
        String state = stack.getLast();
        switch (state) {
            case "cases":
            case "headers":
                stack.removeLast();
                break;
        }
    }

    public void key(String key) throws JSONException {
        String state = stack.getLast();
        switch (state) {
            case "headers":
                headerName = key;
                break;
        }
        stack.addLast(key);
    }

    public void stringValue(String value) throws JSONException {
        String state = stack.getLast();
        switch (state) {
            case "context":
            case "description":
                break;
            case "wire":
                wire = value;
                break;
            default:
                headers.add(new Header(state, value));
                break;
        }
        stack.removeLast();
    }

    public void numberValue(Number value) throws JSONException {
        seqno = value.intValue();
        stack.removeLast();
    }

    public void nullValue() throws JSONException {
        stack.removeLast();
    }

    public void runTest(int seqno, String wire, List<Header> headers) {
        test.testCase(seqno, wire, headers);
    }

}

