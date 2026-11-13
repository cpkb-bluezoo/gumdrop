/*
 * SentCommand.java
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

/**
 * A command that has been sent and is awaiting its reply, kept so that it
 * can be sent again elsewhere, for example after a cluster redirect.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class SentCommand {

    private final Object callback;
    private final String command;
    private final String[] textArgs;
    private final byte[][] binaryArgs;
    private final int redirects;
    private final boolean asking;
    private final boolean pinned;
    private final long timeoutMs;
    private final int routingSlot;

    SentCommand(Object callback, String command, String[] textArgs, byte[][] binaryArgs) {
        this(callback, command, textArgs, binaryArgs, 0, false, false, CommandTimeouts.DEFAULT, -1);
    }

    private SentCommand(Object callback, String command, String[] textArgs,
                        byte[][] binaryArgs, int redirects, boolean asking, boolean pinned,
                        long timeoutMs, int routingSlot) {
        this.timeoutMs = timeoutMs;
        this.routingSlot = routingSlot;
        this.callback = callback;
        this.command = command;
        this.textArgs = textArgs;
        this.binaryArgs = binaryArgs;
        this.redirects = redirects;
        this.asking = asking;
        this.pinned = pinned;
    }

    /** How many times this command has already been redirected to another node. */
    int getRedirects() {
        return redirects;
    }

    /** True if {@code ASKING} must be sent on the connection just before this command. */
    boolean isAsking() {
        return asking;
    }

    /** True if the command must stay on its connection, so redirects are not followed. */
    boolean isPinned() {
        return pinned;
    }

    /** This command after one more redirect, to be preceded by {@code ASKING} if {@code asking}. */
    SentCommand redirected(boolean asking) {
        return new SentCommand(callback, command, textArgs, binaryArgs,
                redirects + 1, asking, pinned, timeoutMs, routingSlot);
    }

    /** This command marked as one that must not be redirected. */
    SentCommand pinned() {
        return new SentCommand(callback, command, textArgs, binaryArgs,
                redirects, asking, true, timeoutMs, routingSlot);
    }

    /**
     * A copy with the given timeout: {@link CommandTimeouts#DEFAULT} for the
     * connection's default, {@link CommandTimeouts#NONE} for none, otherwise
     * milliseconds.
     */
    SentCommand withTimeout(long timeoutMs) {
        return new SentCommand(callback, command, textArgs, binaryArgs,
                redirects, asking, pinned, timeoutMs, routingSlot);
    }

    long getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * A copy sent to the node of the given slot whether or not the command
     * names a key of its own (those must agree with it).
     */
    SentCommand withRoutingSlot(int slot) {
        return new SentCommand(callback, command, textArgs, binaryArgs,
                redirects, asking, pinned, timeoutMs, slot);
    }

    /** The slot the command is to be sent to whatever its keys, or -1 for the slot of its keys. */
    int getRoutingSlot() {
        return routingSlot;
    }

    /** The arguments as bytes (text in UTF-8); empty if there are none. */
    byte[][] toBinaryArgs() {
        if (binaryArgs != null) {
            return binaryArgs;
        }
        if (textArgs == null) {
            return new byte[0][];
        }
        byte[][] out = new byte[textArgs.length][];
        for (int i = 0; i < textArgs.length; i++) {
            out[i] = textArgs[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return out;
    }

    /** The result handler the command was sent with, possibly null. */
    Object getCallback() {
        return callback;
    }

    /** The command name. */
    String getCommand() {
        return command;
    }

    /** The text arguments, or null if none or if sent as binary. */
    String[] getTextArgs() {
        return textArgs;
    }

    /** The binary arguments, or null if none or if sent as text. */
    byte[][] getBinaryArgs() {
        return binaryArgs;
    }

    /** Sends the same command, with the same result handler, on {@code target}. */
    void resend(AbstractRedisSession target) {
        if (textArgs != null) {
            target.sendCommand(callback, timeoutMs, command, textArgs);
        } else if (binaryArgs != null) {
            target.sendCommand(callback, timeoutMs, command, binaryArgs);
        } else {
            target.sendCommand(callback, timeoutMs, command, new String[0]);
        }
    }
}
