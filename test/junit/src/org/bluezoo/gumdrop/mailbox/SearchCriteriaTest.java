package org.bluezoo.gumdrop.mailbox;

import org.junit.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SearchCriteria}.
 */
public class SearchCriteriaTest {

    private static MessageContext stubContext(
            int msgNum, long uid, long size,
            Set<Flag> flags, Set<String> keywords,
            OffsetDateTime internalDate,
            String fromHeader, String toHeader, String subject,
            String bodyText) {
        return new MessageContext() {
            @Override
            public int getMessageNumber() { return msgNum; }
            @Override
            public long getUID() { return uid; }
            @Override
            public long getSize() { return size; }
            @Override
            public Set<Flag> getFlags() { return flags; }
            @Override
            public Set<String> getKeywords() { return keywords; }
            @Override
            public OffsetDateTime getInternalDate() { return internalDate; }
            @Override
            public String getHeader(String name) {
                if ("From".equalsIgnoreCase(name)) return fromHeader;
                if ("To".equalsIgnoreCase(name)) return toHeader;
                if ("Subject".equalsIgnoreCase(name)) return subject;
                return null;
            }
            @Override
            public List<String> getHeaders(String name) {
                String val = getHeader(name);
                return val != null ? Collections.singletonList(val) : Collections.emptyList();
            }
            @Override
            public OffsetDateTime getSentDate() { return internalDate; }
            @Override
            public CharSequence getHeadersText() {
                return "From: " + fromHeader + "\nTo: " + toHeader + "\nSubject: " + subject;
            }
            @Override
            public CharSequence getBodyText() { return bodyText; }
        };
    }

    private static final OffsetDateTime JAN_15 =
            OffsetDateTime.of(2025, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime FEB_20 =
            OffsetDateTime.of(2025, 2, 20, 14, 30, 0, 0, ZoneOffset.UTC);

    private MessageContext seenMessage() {
        return stubContext(1, 100, 5000,
                EnumSet.of(Flag.SEEN), Collections.emptySet(),
                JAN_15, "alice@example.com", "bob@example.com", "Hello", "body text");
    }

    private MessageContext unseenMessage() {
        return stubContext(2, 200, 1500,
                EnumSet.noneOf(Flag.class), new HashSet<>(Arrays.asList("$Junk")),
                FEB_20, "bob@example.com", "alice@example.com", "Re: Hello", "reply body");
    }

    @Test
    public void testAll() throws IOException {
        SearchCriteria c = SearchCriteria.all();
        assertTrue(c.matches(seenMessage()));
        assertTrue(c.matches(unseenMessage()));
    }

    @Test
    public void testSeen() throws IOException {
        assertTrue(SearchCriteria.seen().matches(seenMessage()));
        assertFalse(SearchCriteria.seen().matches(unseenMessage()));
    }

    @Test
    public void testUnseen() throws IOException {
        assertFalse(SearchCriteria.unseen().matches(seenMessage()));
        assertTrue(SearchCriteria.unseen().matches(unseenMessage()));
    }

    @Test
    public void testAnswered() throws IOException {
        MessageContext answered = stubContext(1, 100, 5000,
                EnumSet.of(Flag.ANSWERED), Collections.emptySet(),
                JAN_15, "alice@example.com", "bob@example.com", "Hello", "body");
        assertTrue(SearchCriteria.answered().matches(answered));
        assertFalse(SearchCriteria.answered().matches(unseenMessage()));
    }

    @Test
    public void testDeleted() throws IOException {
        MessageContext deleted = stubContext(1, 100, 5000,
                EnumSet.of(Flag.DELETED), Collections.emptySet(),
                JAN_15, "alice@example.com", "bob@example.com", "Hello", "body");
        assertTrue(SearchCriteria.deleted().matches(deleted));
        assertFalse(SearchCriteria.undeleted().matches(deleted));
    }

    @Test
    public void testFlagged() throws IOException {
        MessageContext flagged = stubContext(1, 100, 5000,
                EnumSet.of(Flag.FLAGGED), Collections.emptySet(),
                JAN_15, "alice@example.com", "bob@example.com", "Hello", "body");
        assertTrue(SearchCriteria.flagged().matches(flagged));
        assertFalse(SearchCriteria.unflagged().matches(flagged));
    }

    @Test
    public void testDraft() throws IOException {
        MessageContext draft = stubContext(1, 100, 5000,
                EnumSet.of(Flag.DRAFT), Collections.emptySet(),
                JAN_15, "alice@example.com", "bob@example.com", "Hello", "body");
        assertTrue(SearchCriteria.draft().matches(draft));
        assertFalse(SearchCriteria.undraft().matches(draft));
    }

    @Test
    public void testKeyword() throws IOException {
        assertTrue(SearchCriteria.keyword("$Junk").matches(unseenMessage()));
        assertFalse(SearchCriteria.keyword("$Junk").matches(seenMessage()));
    }

    @Test
    public void testUnkeyword() throws IOException {
        assertFalse(SearchCriteria.unkeyword("$Junk").matches(unseenMessage()));
        assertTrue(SearchCriteria.unkeyword("$Junk").matches(seenMessage()));
    }

    @Test
    public void testLarger() throws IOException {
        assertTrue(SearchCriteria.larger(4000).matches(seenMessage()));
        assertFalse(SearchCriteria.larger(4000).matches(unseenMessage()));
    }

    @Test
    public void testSmaller() throws IOException {
        assertFalse(SearchCriteria.smaller(2000).matches(seenMessage()));
        assertTrue(SearchCriteria.smaller(2000).matches(unseenMessage()));
    }

    @Test
    public void testBefore() throws IOException {
        LocalDate feb1 = LocalDate.of(2025, 2, 1);
        assertTrue(SearchCriteria.before(feb1).matches(seenMessage()));
        assertFalse(SearchCriteria.before(feb1).matches(unseenMessage()));
    }

    @Test
    public void testSince() throws IOException {
        LocalDate feb1 = LocalDate.of(2025, 2, 1);
        assertFalse(SearchCriteria.since(feb1).matches(seenMessage()));
        assertTrue(SearchCriteria.since(feb1).matches(unseenMessage()));
    }

    @Test
    public void testOn() throws IOException {
        LocalDate jan15 = LocalDate.of(2025, 1, 15);
        assertTrue(SearchCriteria.on(jan15).matches(seenMessage()));
        assertFalse(SearchCriteria.on(jan15).matches(unseenMessage()));
    }

    @Test
    public void testFrom() throws IOException {
        assertTrue(SearchCriteria.from("alice").matches(seenMessage()));
        assertFalse(SearchCriteria.from("alice").matches(unseenMessage()));
    }

    @Test
    public void testFromCaseInsensitive() throws IOException {
        assertTrue(SearchCriteria.from("ALICE").matches(seenMessage()));
    }

    @Test
    public void testTo() throws IOException {
        assertTrue(SearchCriteria.to("bob").matches(seenMessage()));
    }

    @Test
    public void testSubject() throws IOException {
        assertTrue(SearchCriteria.subject("Hello").matches(seenMessage()));
        assertTrue(SearchCriteria.subject("Re:").matches(unseenMessage()));
    }

    @Test
    public void testBody() throws IOException {
        assertTrue(SearchCriteria.body("body text").matches(seenMessage()));
        assertFalse(SearchCriteria.body("body text").matches(unseenMessage()));
    }

    @Test
    public void testText() throws IOException {
        assertTrue(SearchCriteria.text("alice").matches(seenMessage()));
        assertTrue(SearchCriteria.text("body text").matches(seenMessage()));
    }

    @Test
    public void testUid() throws IOException {
        assertTrue(SearchCriteria.uid(100).matches(seenMessage()));
        assertFalse(SearchCriteria.uid(200).matches(seenMessage()));
    }

    @Test
    public void testUidSet() throws IOException {
        assertTrue(SearchCriteria.uidSet(100, 200).matches(seenMessage()));
        assertTrue(SearchCriteria.uidSet(100, 200).matches(unseenMessage()));
        assertFalse(SearchCriteria.uidSet(300, 400).matches(seenMessage()));
    }

    @Test
    public void testUidRange() throws IOException {
        assertTrue(SearchCriteria.uidRange(50, 150).matches(seenMessage()));
        assertFalse(SearchCriteria.uidRange(150, 199).matches(seenMessage()));
    }

    @Test
    public void testSequenceNumber() throws IOException {
        assertTrue(SearchCriteria.sequenceNumber(1).matches(seenMessage()));
        assertFalse(SearchCriteria.sequenceNumber(2).matches(seenMessage()));
    }

    @Test
    public void testAnd() throws IOException {
        SearchCriteria c = SearchCriteria.and(
                SearchCriteria.seen(),
                SearchCriteria.from("alice")
        );
        assertTrue(c.matches(seenMessage()));
        assertFalse(c.matches(unseenMessage()));
    }

    @Test
    public void testAndEmpty() throws IOException {
        SearchCriteria c = SearchCriteria.and();
        assertTrue(c.matches(seenMessage()));
    }

    @Test
    public void testOr() throws IOException {
        SearchCriteria c = SearchCriteria.or(
                SearchCriteria.seen(),
                SearchCriteria.from("bob")
        );
        assertTrue(c.matches(seenMessage()));
        assertTrue(c.matches(unseenMessage()));
    }

    @Test
    public void testNot() throws IOException {
        SearchCriteria c = SearchCriteria.not(SearchCriteria.seen());
        assertFalse(c.matches(seenMessage()));
        assertTrue(c.matches(unseenMessage()));
    }

    @Test
    public void testComplexCombination() throws IOException {
        SearchCriteria c = SearchCriteria.and(
                SearchCriteria.unseen(),
                SearchCriteria.or(
                        SearchCriteria.from("bob"),
                        SearchCriteria.larger(10000)
                )
        );
        assertFalse(c.matches(seenMessage()));
        assertTrue(c.matches(unseenMessage()));
    }

    @Test
    public void testSentBefore() throws IOException {
        LocalDate feb1 = LocalDate.of(2025, 2, 1);
        assertTrue(SearchCriteria.sentBefore(feb1).matches(seenMessage()));
        assertFalse(SearchCriteria.sentBefore(feb1).matches(unseenMessage()));
    }

    @Test
    public void testSentOn() throws IOException {
        LocalDate jan15 = LocalDate.of(2025, 1, 15);
        assertTrue(SearchCriteria.sentOn(jan15).matches(seenMessage()));
    }

    @Test
    public void testSentSince() throws IOException {
        LocalDate feb1 = LocalDate.of(2025, 2, 1);
        assertTrue(SearchCriteria.sentSince(feb1).matches(unseenMessage()));
    }
}
