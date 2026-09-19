/*
 * ReferencesThreader.java
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

import org.bluezoo.gumdrop.mailbox.Mailbox;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RFC 5256 REFERENCES threading.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ReferencesThreader {

    private ReferencesThreader() {
    }

    public static List<ThreadBranch> thread(Mailbox mailbox,
            List<Integer> matches) throws IOException {
        if (matches.isEmpty()) {
            return new ArrayList<>();
        }
        List<Integer> seqOrder = new ArrayList<>(matches);
        seqOrder.sort(new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return a.compareTo(b);
            }
        });

        List<ThreadMessageRecord> records =
                ThreadMessageRecord.load(mailbox, seqOrder);
        Map<String, RefNode> byId = new HashMap<>();
        RefNode root = new RefNode(null);
        Set<String> canonicalIds = new HashSet<>();

        for (ThreadMessageRecord rec : records) {
            String id = rec.messageId;
            if (canonicalIds.contains(id)) {
                id = ThreadHeaders.syntheticId(rec.sequenceNumber);
            } else {
                canonicalIds.add(id);
            }
            RefNode node = byId.get(id);
            if (node == null) {
                node = new RefNode(rec);
                node.messageId = id;
                byId.put(id, node);
            } else {
                node.attach(rec);
            }

            RefNode prev = null;
            for (String refId : rec.references) {
                RefNode refNode = byId.get(refId);
                if (refNode == null) {
                    refNode = new RefNode(null);
                    refNode.messageId = refId;
                    refNode.dummy = true;
                    byId.put(refId, refNode);
                }
                if (prev != null) {
                    linkIfAllowed(prev, refNode);
                }
                prev = refNode;
            }
            if (prev != null) {
                if (node.parent != null && node.parent != root) {
                    unlink(node);
                }
                linkIfAllowed(prev, node);
            }
        }

        for (RefNode node : byId.values()) {
            if (node.parent == null) {
                linkIfAllowed(root, node);
            }
        }

        pruneDummies(root);
        root.children.sort(sentDateComparator());
        mergeBySubject(root);
        sortSiblingsYoungestFirst(root);

        List<ThreadBranch> out = new ArrayList<>();
        for (RefNode child : root.children) {
            ThreadBranch branch = toBranch(child);
            if (branch != null) {
                out.add(branch);
            }
        }
        return out;
    }

    private static void linkIfAllowed(RefNode parent, RefNode child) {
        if (parent == child || wouldCreateLoop(parent, child)) {
            return;
        }
        if (child.parent != null) {
            if (child.parent == parent) {
                return;
            }
            unlink(child);
        }
        if (!parent.children.contains(child)) {
            parent.children.add(child);
        }
        child.parent = parent;
    }

    private static boolean wouldCreateLoop(RefNode parent, RefNode child) {
        RefNode walk = parent;
        while (walk != null) {
            if (walk == child) {
                return true;
            }
            walk = walk.parent;
        }
        return false;
    }

    private static void unlink(RefNode child) {
        if (child.parent != null) {
            child.parent.children.remove(child);
            child.parent = null;
        }
    }

    private static void pruneDummies(RefNode node) {
        for (RefNode child : new ArrayList<>(node.children)) {
            pruneDummies(child);
        }
        for (RefNode child : new ArrayList<>(node.children)) {
            if (!child.dummy) {
                continue;
            }
            if (child.children.isEmpty()) {
                unlink(child);
                continue;
            }
            RefNode parent = child.parent;
            int insertAt = parent.children.indexOf(child);
            parent.children.remove(child);
            child.parent = null;
            List<RefNode> promote = new ArrayList<>(child.children);
            if (parent.parent == null && promote.size() > 1) {
                for (RefNode grand : promote) {
                    unlink(grand);
                    parent.children.add(grand);
                    grand.parent = parent;
                }
            } else {
                for (int i = 0; i < promote.size(); i++) {
                    RefNode grand = promote.get(i);
                    unlink(grand);
                    parent.children.add(insertAt + i, grand);
                    grand.parent = parent;
                }
            }
        }
    }

    private static Comparator<RefNode> sentDateComparator() {
        return new Comparator<RefNode>() {
            @Override
            public int compare(RefNode a, RefNode b) {
                int c = sentDateForSort(a).compareTo(sentDateForSort(b));
                if (c != 0) {
                    return c;
                }
                return Integer.compare(seqOrMax(a), seqOrMax(b));
            }
        };
    }

    private static Instant sentDateForSort(RefNode node) {
        if (node.record != null) {
            return node.record.sentDate;
        }
        if (!node.children.isEmpty()) {
            return sentDateForSort(node.children.get(0));
        }
        return Instant.EPOCH;
    }

    private static int seqOrMax(RefNode node) {
        return node.record != null ? node.record.sequenceNumber
                : Integer.MAX_VALUE;
    }

    private static void mergeBySubject(RefNode root) {
        Map<String, RefNode> subjectTable = new LinkedHashMap<>();
        for (RefNode child : new ArrayList<>(root.children)) {
            String subj = threadSubject(child);
            if (subj.isEmpty()) {
                continue;
            }
            String existingKey = findSubjectKey(subjectTable, subj);
            if (existingKey == null) {
                subjectTable.put(subj, child);
                continue;
            }
            RefNode existing = subjectTable.get(existingKey);
            if (!existing.dummy
                    && (child.dummy || (existing.record != null
                            && existing.record.replyOrForward
                            && child.record != null
                            && !child.record.replyOrForward))) {
                subjectTable.remove(existingKey);
                subjectTable.put(subj, child);
            }
        }
        for (RefNode child : new ArrayList<>(root.children)) {
            String subj = threadSubject(child);
            if (subj.isEmpty()) {
                continue;
            }
            String key = findSubjectKey(subjectTable, subj);
            if (key == null) {
                continue;
            }
            RefNode tableNode = subjectTable.get(key);
            if (tableNode == child) {
                continue;
            }
            mergePair(tableNode, child, subjectTable, subj);
        }
    }

    private static String findSubjectKey(Map<String, RefNode> table,
            String subject) {
        for (Map.Entry<String, RefNode> e : table.entrySet()) {
            if (ImapUnicodeCasemap.compare(e.getKey(), subject) == 0) {
                return e.getKey();
            }
        }
        return null;
    }

    private static void mergePair(RefNode tableNode, RefNode current,
            Map<String, RefNode> subjectTable, String subj) {
        if (tableNode.dummy && current.dummy) {
            for (RefNode c : current.children) {
                unlink(c);
                linkIfAllowed(tableNode, c);
            }
            unlink(current);
            return;
        }
        if (tableNode.dummy && !current.dummy) {
            linkIfAllowed(tableNode, current);
            return;
        }
        if (current.record != null && current.record.replyOrForward
                && tableNode.record != null
                && !tableNode.record.replyOrForward) {
            linkIfAllowed(tableNode, current);
            return;
        }
        RefNode dummy = new RefNode(null);
        dummy.messageId = ThreadHeaders.syntheticId(-1);
        dummy.dummy = true;
        RefNode parent = tableNode.parent;
        int idx = parent.children.indexOf(tableNode);
        unlink(tableNode);
        unlink(current);
        linkIfAllowed(dummy, tableNode);
        linkIfAllowed(dummy, current);
        parent.children.add(idx, dummy);
        dummy.parent = parent;
        String key = findSubjectKey(subjectTable, subj);
        if (key != null) {
            subjectTable.remove(key);
        }
        subjectTable.put(subj, dummy);
    }

    private static String threadSubject(RefNode node) {
        if (node.record != null && !node.record.baseSubject.isEmpty()) {
            return node.record.baseSubject;
        }
        if (!node.children.isEmpty()) {
            return threadSubject(node.children.get(0));
        }
        return "";
    }

    private static void sortSiblingsYoungestFirst(RefNode node) {
        for (RefNode child : node.children) {
            sortSiblingsYoungestFirst(child);
        }
        node.children.sort(sentDateComparator());
    }

    private static ThreadBranch toBranch(RefNode node) {
        if (node.record == null && node.children.isEmpty()) {
            return null;
        }
        ThreadBranch branch = new ThreadBranch();
        appendChain(branch, node);
        return branch;
    }

    private static void appendChain(ThreadBranch branch, RefNode node) {
        if (node.record != null) {
            branch.addMember((long) node.record.sequenceNumber);
        } else if (!node.children.isEmpty()) {
            appendChain(branch, node.children.get(0));
            return;
        }
        if (node.children.isEmpty()) {
            return;
        }
        if (node.children.size() == 1) {
            appendChain(branch, node.children.get(0));
            return;
        }
        appendChain(branch, node.children.get(0));
        for (int i = 1; i < node.children.size(); i++) {
            ThreadBranch nested = toBranch(node.children.get(i));
            if (nested != null) {
                branch.addNested(nested);
            }
        }
    }

    private static final class RefNode {
        String messageId;
        ThreadMessageRecord record;
        boolean dummy;
        RefNode parent;
        final List<RefNode> children = new ArrayList<>();

        RefNode(ThreadMessageRecord record) {
            attach(record);
        }

        void attach(ThreadMessageRecord rec) {
            if (rec == null) {
                dummy = true;
                return;
            }
            record = rec;
            dummy = false;
        }
    }
}
