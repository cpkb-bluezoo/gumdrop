/*
 * RecoverableConnectionImpl.java
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

package org.bluezoo.gumdrop.amqp.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.amqp.client.handler.ClientConnection;
import org.bluezoo.gumdrop.amqp.client.handler.ServerChannelOpenHandler;
import org.bluezoo.gumdrop.amqp.client.handler.ServerCloseHandler;

/**
 * A {@link ClientConnection} whose channels survive reconnects — see
 * {@link AMQPClientRecovery}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class RecoverableConnectionImpl implements ClientConnection {

    private static final Logger LOGGER = Logger.getLogger(RecoverableConnectionImpl.class.getName());

    private volatile ClientConnection live;
    private final Map<Integer, RecoverableChannelImpl> channels =
            new ConcurrentHashMap<Integer, RecoverableChannelImpl>();

    void bind(ClientConnection newLive) {
        this.live = newLive;
    }

    /**
     * Reopens every previously-opened channel on the new connection and
     * replays its recorded topology, then makes each one live again.
     * Called once the new connection is open, before the application is
     * told recovery finished.
     *
     * @param onComplete run after every channel has finished reopening
     *      (whether individually successful or not — a channel that
     *      fails to reopen logs a warning and is left disconnected,
     *      rather than one bad channel blocking the rest)
     */
    void reopenAndReplayAll(Runnable onComplete) {
        if (channels.isEmpty()) {
            onComplete.run();
            return;
        }
        final int[] remaining = { channels.size() };
        for (final RecoverableChannelImpl channel : channels.values()) {
            live.channelOpen(channel.getChannelId(), new ServerChannelOpenHandler() {
                @Override
                public void handleChannelOpenOk(org.bluezoo.gumdrop.amqp.client.handler.ClientChannel newRealChannel) {
                    channel.rebind(newRealChannel);
                    countDown();
                }

                private void countDown() {
                    synchronized (remaining) {
                        if (--remaining[0] == 0) {
                            onComplete.run();
                        }
                    }
                }
            });
        }
    }

    /** Called when the whole connection drops; channels await {@link #reopenAndReplayAll}. */
    void markDisconnected() {
        this.live = null;
        for (RecoverableChannelImpl channel : channels.values()) {
            channel.markDisconnected();
        }
    }

    @Override
    public void channelOpen(final int channelId, final ServerChannelOpenHandler handler) {
        ClientConnection l = live;
        if (l == null) {
            throw new IllegalStateException("Connection is disconnected and awaiting reconnect");
        }
        l.channelOpen(channelId, new ServerChannelOpenHandler() {
            @Override
            public void handleChannelOpenOk(org.bluezoo.gumdrop.amqp.client.handler.ClientChannel realChannel) {
                // Stop tracking (and therefore stop replaying on future
                // reconnects) this channel once it's closed for good,
                // whether by the application or unsolicited by the
                // broker — see RecoverableChannelImpl.internalCloseListener.
                RecoverableChannelImpl recoverable = new RecoverableChannelImpl(channelId, realChannel,
                        () -> channels.remove(channelId));
                channels.put(channelId, recoverable);
                handler.handleChannelOpenOk(recoverable);
            }
        });
    }

    @Override
    public void close(int replyCode, String replyText, ServerCloseHandler handler) {
        ClientConnection l = live;
        if (l == null) {
            throw new IllegalStateException("Connection is disconnected and awaiting reconnect");
        }
        channels.clear();
        l.close(replyCode, replyText, handler);
    }
}
