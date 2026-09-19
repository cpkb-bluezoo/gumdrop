/*
 * ThreadFormatter.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import java.util.List;

/**
 * Serializes {@link ThreadBranch} trees into RFC 5256 THREAD data.
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
        for (ThreadBranch nested : branch.getNested()) {
            formatBranch(sb, nested);
        }
        sb.append(')');
    }
}
