/*
 * ThreadFormatter.java
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

package org.bluezoo.gumdrop.imap;

import java.util.List;

/**
 * Serializes {@link ThreadBranch} trees into RFC 5256 THREAD data.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ThreadFormatter {

    private ThreadFormatter() {
    }

    public static String format(List<ThreadBranch> threads) {
        if (threads == null || threads.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ThreadBranch thread : threads) {
            formatBranch(sb, thread);
        }
        return sb.toString();
    }

    private static void formatBranch(StringBuilder sb, ThreadBranch branch) {
        sb.append('(');
        boolean first = true;
        for (Long num : branch.getMembers()) {
            if (!first) {
                sb.append(' ');
            }
            sb.append(num);
            first = false;
        }
        boolean hadMembers = !branch.getMembers().isEmpty();
        List<ThreadBranch> nested = branch.getNested();
        for (int i = 0; i < nested.size(); i++) {
            if (i == 0 && hadMembers) {
                sb.append(' ');
            }
            formatBranch(sb, nested.get(i));
        }
        sb.append(')');
    }
}
