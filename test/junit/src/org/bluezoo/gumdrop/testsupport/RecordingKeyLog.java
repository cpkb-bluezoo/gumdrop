/*
 * RecordingKeyLog.java
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

import org.bluezoo.gumdrop.tls.KeyLog;

/**
 * A {@link KeyLog} that keeps every entry in memory for assertions.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class RecordingKeyLog extends KeyLog {

    /** One logged secret. */
    public static final class Entry {

        public final String label;
        public final byte[] clientRandom;
        public final byte[] secret;

        Entry(String label, byte[] clientRandom, byte[] secret) {
            this.label = label;
            this.clientRandom = clientRandom.clone();
            this.secret = secret.clone();
        }

    }

    public final List<Entry> entries = new ArrayList<Entry>();

    @Override
    public synchronized void log(String label, byte[] clientRandom, byte[] secret) {
        entries.add(new Entry(label, clientRandom, secret));
    }

    /** The labels in the order they were logged. */
    public synchronized List<String> labels() {
        List<String> labels = new ArrayList<String>();
        for (int i = 0; i < entries.size(); i++) {
            labels.add(entries.get(i).label);
        }
        return labels;
    }

    /** The first secret logged under {@code label}, or null. */
    public synchronized byte[] secret(String label) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).label.equals(label)) {
                return entries.get(i).secret;
            }
        }
        return null;
    }

}
