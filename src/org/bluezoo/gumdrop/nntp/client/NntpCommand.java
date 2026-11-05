/*
 * NntpCommand.java
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

package org.bluezoo.gumdrop.nntp.client;

import java.nio.ByteBuffer;

/**
 * One command queued on a session.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class NntpCommand {

    /** How the block after the status line is read, if there is one. */
    enum Block { NONE, CONTENT, NEWSGROUPS, OVERVIEW }

    final String line;
    final Block block;
    final NntpConsumer<ByteBuffer> contentSink;
    final NntpConsumer<NewsgroupEntry> groupSink;
    final NntpConsumer<OverviewEntry> overviewSink;
    final NntpCallback<NntpStatus> done;
    /** When the first reply has this code, {@link #payload} is sent (0: never). */
    final int continuationCode;
    final byte[] payload;
    final boolean quit;

    NntpCommand(String line, Block block, NntpConsumer<ByteBuffer> contentSink,
                NntpConsumer<NewsgroupEntry> groupSink, NntpConsumer<OverviewEntry> overviewSink,
                NntpCallback<NntpStatus> done, int continuationCode, byte[] payload,
                boolean quit) {
        this.line = line;
        this.block = block;
        this.contentSink = contentSink;
        this.groupSink = groupSink;
        this.overviewSink = overviewSink;
        this.done = done;
        this.continuationCode = continuationCode;
        this.payload = payload;
        this.quit = quit;
    }

}
