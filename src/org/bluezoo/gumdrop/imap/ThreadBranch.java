/*
 * ThreadBranch.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RFC 5256 THREAD response branch (parent/child chain plus nested splits).
 */
public final class ThreadBranch {

    private final List<Long> members = new ArrayList<>();
    private final List<ThreadBranch> nested = new ArrayList<>();

    public void addMember(long sequenceOrUid) {
        members.add(sequenceOrUid);
    }

    public void addNested(ThreadBranch branch) {
        nested.add(branch);
    }

    List<Long> membersMutable() {
        return members;
    }

    public List<Long> getMembers() {
        return Collections.unmodifiableList(members);
    }

    public List<ThreadBranch> getNested() {
        return Collections.unmodifiableList(nested);
    }
}
