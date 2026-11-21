/*
 * RequestWriter.java
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

package org.bluezoo.gumdrop.redis.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

import org.bluezoo.gumdrop.Endpoint;

/**
 * Writes a connection's requests in order. A command held in memory is handed
 * straight to the endpoint. A command with a streamed argument is written in
 * blocks, each pulled from its {@link ArgumentStream} only when the endpoint
 * can take it: while the unsent data the endpoint holds is over a limit the
 * writer waits for {@link Endpoint#onWriteReady}, and while a stream has
 * nothing to give it waits for the stream to say it has. Commands submitted
 * meanwhile queue behind it, as the bytes of one command cannot be broken
 * by another's.
 *
 * <p>What the writer holds is a block at a time: the memory a streamed
 * argument costs is set by the block size and the write limit, never by its
 * length.
 *
 * <p>All methods run on the connection's event loop.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class RequestWriter {

    /** The bytes asked of a stream at a time. */
    static final int BLOCK = 16 * 1024;

    /** The unsent bytes the endpoint may hold before the writer waits for it. */
    static final int HIGH_WATER = 64 * 1024;

    /** What the writer tells the connection. */
    interface Owner {

        /**
         * The argument's stream failed or ended short, and the stream of
         * requests cannot be recovered: the connection must be closed.
         */
        void streamBroken(Job job, String error);

        /** The last byte of the command has been handed to the endpoint. */
        void streamWritten(Job job);
    }

    /** A command with a streamed argument. */
    static final class Job {
        final ByteBuffer head;
        final ByteBuffer tail;
        final ArgumentStream stream;
        final long length;
        /** The connection's record of the command. */
        final Object owner;
        /** Commands queued behind this one, whose clocks start when it is written. */
        final java.util.List<Object> after = new java.util.ArrayList<Object>();
        long remaining;

        Job(ByteBuffer head, ByteBuffer tail, ArgumentStream stream, long length, Object owner) {
            this.head = head;
            this.tail = tail;
            this.stream = stream;
            this.length = length;
            this.owner = owner;
            this.remaining = length;
        }
    }

    private final Endpoint endpoint;
    private final Owner owner;
    private final Deque<Object> queue = new ArrayDeque<Object>();
    private final Runnable wake = new Runnable() {
        @Override
        public void run() {
            pump();
        }
    };
    private Job current;
    private boolean headSent;
    private boolean pumping;
    private boolean again;
    private boolean stopped;

    RequestWriter(Endpoint endpoint, Owner owner) {
        this.endpoint = endpoint;
        this.owner = owner;
    }

    /** Returns whether a streamed command is being written, or waits to be. */
    boolean isBusy() {
        return current != null || !queue.isEmpty();
    }

    /** Sends a command held in memory, after any streamed one before it. */
    void send(ByteBuffer command) {
        if (!isBusy()) {
            endpoint.send(command);
        } else {
            queue.add(command);
        }
    }

    /** Sends a command with a streamed argument. */
    void send(Job job) {
        queue.add(job);
        pump();
    }

    /** The sink for a job's stream: tells the writer when more can be read. */
    ArgumentSink sinkFor(final Job[] holder) {
        return new ArgumentSink() {
            @Override
            public void ready() {
                endpoint.execute(wake);
            }

            @Override
            public void fail(final IOException cause) {
                endpoint.execute(new Runnable() {
                    @Override
                    public void run() {
                        Job job = holder[0];
                        if (job != null && !stopped && job == current) {
                            broken(job, String.valueOf(cause.getMessage()));
                        }
                    }
                });
            }
        };
    }

    /** Stops writing: the connection is closing. Streams still open are closed. */
    void abort() {
        stopped = true;
        if (current != null) {
            current.stream.close();
            current = null;
        }
        for (Object o : queue) {
            if (o instanceof Job) {
                ((Job) o).stream.close();
            }
        }
        queue.clear();
    }

    private void pump() {
        if (pumping) {
            again = true;
            return;
        }
        pumping = true;
        try {
            do {
                again = false;
                step();
            } while (again && !stopped);
        } finally {
            pumping = false;
        }
    }

    private void step() {
        while (!stopped) {
            if (current == null) {
                Object next = queue.poll();
                if (next == null) {
                    return;
                }
                if (next instanceof ByteBuffer) {
                    endpoint.send((ByteBuffer) next);
                    continue;
                }
                current = (Job) next;
                headSent = false;
            }
            Job job = current;
            if (!headSent) {
                endpoint.send(job.head);
                headSent = true;
            }
            while (job.remaining > 0) {
                if (endpoint.pendingWriteBytes() >= HIGH_WATER) {
                    // the peer is not taking it as fast as it is offered
                    endpoint.onWriteReady(wake);
                    return;
                }
                ByteBuffer block = ByteBuffer.allocate((int) Math.min(BLOCK, job.remaining));
                int n;
                try {
                    n = job.stream.read(block);
                } catch (IOException e) {
                    broken(job, String.valueOf(e.getMessage()));
                    return;
                } catch (RuntimeException e) {
                    broken(job, String.valueOf(e.getMessage()));
                    return;
                }
                if (stopped || current != job) {
                    return;
                }
                if (n < 0) {
                    broken(job, RedisClientProtocolHandler.L10N.getString("err.argument_short"));
                    return;
                }
                if (n == 0) {
                    // the stream calls ready() when it has more
                    return;
                }
                block.flip();
                endpoint.send(block);
                job.remaining -= n;
            }
            endpoint.send(job.tail);
            job.stream.close();
            current = null;
            owner.streamWritten(job);
        }
    }

    private void broken(Job job, String error) {
        stopped = true;
        job.stream.close();
        current = null;
        owner.streamBroken(job, error);
    }

}
