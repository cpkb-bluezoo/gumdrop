package org.bluezoo.gumdrop.http.hpack;

import java.util.*;
import org.bluezoo.json.*;
import org.bluezoo.gumdrop.http.Header;

/**
 * Story JSON handler
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

