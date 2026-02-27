/*
 * MailboxInfo.java
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

package org.bluezoo.gumdrop.imap.client;

/**
 * Information about a selected IMAP mailbox, populated from
 * SELECT/EXAMINE response data.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MailboxInfo {

    private int exists;
    private int recent;
    private String[] flags;
    private String[] permanentFlags;
    private long uidValidity;
    private long uidNext;
    private int unseen;
    private boolean readWrite;

    public int getExists() {
        return exists;
    }

    public void setExists(int exists) {
        this.exists = exists;
    }

    public int getRecent() {
        return recent;
    }

    public void setRecent(int recent) {
        this.recent = recent;
    }

    public String[] getFlags() {
        return flags;
    }

    public void setFlags(String[] flags) {
        this.flags = flags;
    }

    public String[] getPermanentFlags() {
        return permanentFlags;
    }

    public void setPermanentFlags(String[] permanentFlags) {
        this.permanentFlags = permanentFlags;
    }

    public long getUidValidity() {
        return uidValidity;
    }

    public void setUidValidity(long uidValidity) {
        this.uidValidity = uidValidity;
    }

    public long getUidNext() {
        return uidNext;
    }

    public void setUidNext(long uidNext) {
        this.uidNext = uidNext;
    }

    public int getUnseen() {
        return unseen;
    }

    public void setUnseen(int unseen) {
        this.unseen = unseen;
    }

    public boolean isReadWrite() {
        return readWrite;
    }

    public void setReadWrite(boolean readWrite) {
        this.readWrite = readWrite;
    }
}
