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

    SentCommand(Object callback, String command, String[] textArgs, byte[][] binaryArgs) {
        this.callback = callback;
        this.command = command;
        this.textArgs = textArgs;
        this.binaryArgs = binaryArgs;
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
            target.sendCommand(callback, command, textArgs);
        } else if (binaryArgs != null) {
            target.sendCommand(callback, command, binaryArgs);
        } else {
            target.sendCommand(callback, command);
        }
    }
}
