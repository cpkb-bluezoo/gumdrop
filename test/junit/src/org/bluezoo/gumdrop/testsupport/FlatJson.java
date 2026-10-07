/*
 * FlatJson.java
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.json.JSONDefaultHandler;
import org.bluezoo.json.JSONException;
import org.bluezoo.json.JSONParser;

/**
 * Reads a JSON text into a flat map of dotted path to scalar value, so a
 * test can assert on the fields it cares about.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class FlatJson {

    private FlatJson() {
    }

    /** Flattens one JSON text to path to value, with array elements as path.N. */
    public static Map<String, String> flatten(String json) throws JSONException {
        final Map<String, String> out = new LinkedHashMap<String, String>();
        JSONParser parser = new JSONParser();
        parser.setContentHandler(new JSONDefaultHandler() {
            private final List<String> segments = new ArrayList<String>();
            // one entry per open container: the next index of an array, or -1 for an object
            private final List<int[]> frames = new ArrayList<int[]>();

            private boolean inArray() {
                return frames.size() > 0 && frames.get(frames.size() - 1)[0] >= 0;
            }

            private String path() {
                StringBuilder sb = new StringBuilder();
                for (String segment : segments) {
                    if (sb.length() > 0) {
                        sb.append('.');
                    }
                    sb.append(segment);
                }
                return sb.toString();
            }

            private void enter(int firstIndex) {
                if (inArray()) {
                    segments.add(String.valueOf(frames.get(frames.size() - 1)[0]++));
                }
                frames.add(new int[] { firstIndex });
            }

            private void leave() {
                frames.remove(frames.size() - 1);
                if (segments.size() > 0) {
                    segments.remove(segments.size() - 1);
                }
            }

            private void scalar(String v) {
                if (inArray()) {
                    segments.add(String.valueOf(frames.get(frames.size() - 1)[0]++));
                }
                out.put(path(), v);
                segments.remove(segments.size() - 1);
            }

            @Override
            public void startObject() {
                enter(-1);
            }

            @Override
            public void endObject() {
                leave();
            }

            @Override
            public void startArray() {
                enter(0);
            }

            @Override
            public void endArray() {
                leave();
            }

            @Override
            public void key(String k) {
                segments.add(k);
            }

            @Override
            public void stringValue(String v) {
                scalar(v);
            }

            @Override
            public void numberValue(Number v) {
                scalar(v.toString());
            }

            @Override
            public void booleanValue(boolean v) {
                scalar(String.valueOf(v));
            }
        });
        parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        return out;
    }
}
