/*
 * AttachmentPoster.java
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.mime.UuEncoder;

/**
 * Writes an article and its attachments to a post stream in constant
 * memory: the text, then each attachment read a chunk at a time, encoded a
 * chunk at a time and written out before the next is read.
 *
 * <p>It is a state machine driven by completions. Each step that waits (for
 * a read, or for the connection to take a write) returns, and its completion
 * drives the machine on, so the stack does not grow with the size of the
 * attachments even when the completions are immediate.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class AttachmentPoster implements NntpPoster {

    private static final int SLICE = 8192;
    private static final int READ = 16384;
    private static final int ENCODED = 8192;

    private enum Phase { TEXT, ENSURE_LINE_END, NEXT_ATTACHMENT, READING, ENCODING, DONE }

    private final byte[] text;
    private final List<NntpAttachment> attachments;

    private NntpPostStream stream;
    private Phase phase = Phase.TEXT;
    private int textPosition;
    private int index;
    private NntpAttachment.Source source;
    private UuEncoder encoder;
    private ByteBuffer input;
    private ByteBuffer encoded;
    private boolean endOfInput;

    private boolean waiting;
    private boolean driving;
    private boolean again;

    AttachmentPoster(byte[] text, List<NntpAttachment> attachments) {
        this.text = text;
        this.attachments = new ArrayList<NntpAttachment>(attachments);
    }

    @Override
    public void ready(NntpPostStream stream) {
        this.stream = stream;
        drive();
    }

    /** Resumes the machine after something it waited for; runs on the event loop. */
    private final Runnable resume = new Runnable() {
        @Override
        public void run() {
            waiting = false;
            drive();
        }
    };

    private void drive() {
        if (driving) {
            again = true;
            return;
        }
        driving = true;
        try {
            do {
                again = false;
                if (!waiting) {
                    step();
                }
            } while (again);
        } finally {
            driving = false;
        }
    }

    private void step() {
        if (phase != Phase.DONE && !stream.isOpen()) {
            closeSource(); // the post is over: stop reading
            phase = Phase.DONE;
            return;
        }
        switch (phase) {
            case TEXT:
                writeText();
                break;
            case ENSURE_LINE_END:
                phase = Phase.NEXT_ATTACHMENT;
                if (text.length > 0 && text[text.length - 1] != '\n' && text[text.length - 1] != '\r') {
                    waiting = true;
                    stream.write(ByteBuffer.wrap(new byte[] {'\n'}), resume);
                } else {
                    again = true;
                }
                break;
            case NEXT_ATTACHMENT:
                nextAttachment();
                break;
            case READING:
                read();
                break;
            case ENCODING:
                encode();
                break;
            default:
                break;
        }
    }

    private void writeText() {
        if (textPosition >= text.length) {
            phase = Phase.ENSURE_LINE_END;
            again = true;
            return;
        }
        int n = Math.min(SLICE, text.length - textPosition);
        waiting = true;
        ByteBuffer slice = ByteBuffer.wrap(text, textPosition, n);
        textPosition += n;
        stream.write(slice, resume);
    }

    private void nextAttachment() {
        if (index >= attachments.size()) {
            phase = Phase.DONE;
            stream.finish();
            return;
        }
        NntpAttachment a = attachments.get(index);
        try {
            source = a.open(stream.storage());
        } catch (IOException e) {
            phase = Phase.DONE;
            stream.abort("cannot read attachment " + a.getName() + ": " + e.getMessage());
            return;
        }
        encoder = new UuEncoder(a.getName(), a.getMode());
        if (input == null) {
            input = ByteBuffer.allocate(READ);
            encoded = ByteBuffer.allocate(ENCODED);
        }
        input.clear();
        input.limit(0);
        endOfInput = false;
        phase = Phase.READING;
        again = true;
    }

    private void read() {
        if (endOfInput) {
            phase = Phase.ENCODING;
            again = true;
            return;
        }
        input.clear();
        waiting = true;
        source.read(input, new CompletionHandler<Integer, ByteBuffer>() {
            @Override
            public void completed(final Integer n, ByteBuffer attachment) {
                stream.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (n.intValue() < 0) {
                            endOfInput = true;
                            input.clear();
                            input.limit(0);
                        } else {
                            input.flip();
                        }
                        phase = Phase.ENCODING;
                        resume.run();
                    }
                });
            }

            @Override
            public void failed(final Throwable error, ByteBuffer attachment) {
                stream.execute(new Runnable() {
                    @Override
                    public void run() {
                        closeSource();
                        phase = Phase.DONE;
                        waiting = false;
                        stream.abort("cannot read attachment " + attachments.get(index).getName()
                                + ": " + error.getMessage());
                    }
                });
            }
        });
    }

    private void encode() {
        encoded.clear();
        boolean finished = encoder.encode(input, encoded, endOfInput);
        encoded.flip();
        if (finished) {
            closeSource();
            index++;
            phase = Phase.NEXT_ATTACHMENT;
        } else if (!input.hasRemaining() && !endOfInput) {
            phase = Phase.READING;
        }
        // else the output buffer was full, or the end of the file wants more: encode again
        again = true;
        if (encoded.hasRemaining()) {
            waiting = true;
            stream.write(encoded, resume);
        }
    }

    private void closeSource() {
        if (source != null) {
            source.close();
            source = null;
        }
    }

}
