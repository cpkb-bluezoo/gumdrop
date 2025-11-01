/*
 * ContinuationFrame.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.http;

import java.nio.ByteBuffer;

/**
 * A HTTP/2 CONTINUATION frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
class ContinuationFrame extends Frame {

    int stream;
    boolean endHeaders;

    byte[] headerBlockFragment;

    /**
     * Constructor for a continuation frame received from the client.
     */
    protected ContinuationFrame(int flags, int stream, byte[] payload) {
        this.stream = stream;
        endHeaders = (flags & FLAG_END_HEADERS) != 0;
        headerBlockFragment = payload;
    }

    /**
     * Constructor for a continuation frame to send to the client.
     */
    protected ContinuationFrame(int stream, boolean endHeaders, byte[] headerBlockFragment) {
        this.stream = stream;
        this.endHeaders = endHeaders;
        this.headerBlockFragment = headerBlockFragment;
    }

    protected int getLength() {
        return headerBlockFragment.length;
    }

    protected int getType() {
        return TYPE_CONTINUATION;
    }

    protected int getFlags() {
        return endHeaders ? FLAG_END_HEADERS : 0;
    }

    protected int getStream() {
        return stream;
    }

    protected void write(ByteBuffer buf) {
        super.write(buf);
        buf.put(headerBlockFragment);
    }

}
