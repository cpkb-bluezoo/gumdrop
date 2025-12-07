/*
 * SearchParserTest.java
 * Copyright (C) 2025 Chris Burdess
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

import org.bluezoo.gumdrop.mailbox.SearchCriteria;
import org.junit.Test;

import java.text.ParseException;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SearchParser}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SearchParserTest {

    @Test
    public void testEmptySearchReturnsAll() throws ParseException {
        SearchCriteria criteria = new SearchParser("").parse();
        assertNotNull(criteria);
        // Empty search means ALL
    }
    
    @Test
    public void testWhitespaceOnlyReturnsAll() throws ParseException {
        SearchCriteria criteria = new SearchParser("   ").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testAll() throws ParseException {
        SearchCriteria criteria = new SearchParser("ALL").parse();
        assertNotNull(criteria);
    }
    
    // Flag searches
    
    @Test
    public void testAnswered() throws ParseException {
        SearchCriteria criteria = new SearchParser("ANSWERED").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testDeleted() throws ParseException {
        SearchCriteria criteria = new SearchParser("DELETED").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testDraft() throws ParseException {
        SearchCriteria criteria = new SearchParser("DRAFT").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testFlagged() throws ParseException {
        SearchCriteria criteria = new SearchParser("FLAGGED").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testNew() throws ParseException {
        SearchCriteria criteria = new SearchParser("NEW").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testOld() throws ParseException {
        SearchCriteria criteria = new SearchParser("OLD").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testRecent() throws ParseException {
        SearchCriteria criteria = new SearchParser("RECENT").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testSeen() throws ParseException {
        SearchCriteria criteria = new SearchParser("SEEN").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testUnanswered() throws ParseException {
        SearchCriteria criteria = new SearchParser("UNANSWERED").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testUndeleted() throws ParseException {
        SearchCriteria criteria = new SearchParser("UNDELETED").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testUndraft() throws ParseException {
        SearchCriteria criteria = new SearchParser("UNDRAFT").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testUnflagged() throws ParseException {
        SearchCriteria criteria = new SearchParser("UNFLAGGED").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testUnseen() throws ParseException {
        SearchCriteria criteria = new SearchParser("UNSEEN").parse();
        assertNotNull(criteria);
    }
    
    // Header searches
    
    @Test
    public void testFrom() throws ParseException {
        SearchCriteria criteria = new SearchParser("FROM \"john@example.com\"").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testTo() throws ParseException {
        SearchCriteria criteria = new SearchParser("TO recipient@example.com").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testCc() throws ParseException {
        SearchCriteria criteria = new SearchParser("CC \"copy@example.com\"").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testBcc() throws ParseException {
        SearchCriteria criteria = new SearchParser("BCC hidden@example.com").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testSubject() throws ParseException {
        SearchCriteria criteria = new SearchParser("SUBJECT \"Important Meeting\"").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testHeader() throws ParseException {
        SearchCriteria criteria = new SearchParser("HEADER X-Priority high").parse();
        assertNotNull(criteria);
    }
    
    // Text searches
    
    @Test
    public void testBody() throws ParseException {
        SearchCriteria criteria = new SearchParser("BODY \"meeting agenda\"").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testText() throws ParseException {
        SearchCriteria criteria = new SearchParser("TEXT project").parse();
        assertNotNull(criteria);
    }
    
    // Date searches
    
    @Test
    public void testBefore() throws ParseException {
        SearchCriteria criteria = new SearchParser("BEFORE 1-Jan-2024").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testOn() throws ParseException {
        SearchCriteria criteria = new SearchParser("ON 15-Nov-2024").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testSince() throws ParseException {
        SearchCriteria criteria = new SearchParser("SINCE \"1-Dec-2024\"").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testSentBefore() throws ParseException {
        SearchCriteria criteria = new SearchParser("SENTBEFORE 1-Jan-2024").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testSentOn() throws ParseException {
        SearchCriteria criteria = new SearchParser("SENTON 15-Nov-2024").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testSentSince() throws ParseException {
        SearchCriteria criteria = new SearchParser("SENTSINCE 1-Dec-2024").parse();
        assertNotNull(criteria);
    }
    
    // Size searches
    
    @Test
    public void testLarger() throws ParseException {
        SearchCriteria criteria = new SearchParser("LARGER 1000000").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testSmaller() throws ParseException {
        SearchCriteria criteria = new SearchParser("SMALLER 50000").parse();
        assertNotNull(criteria);
    }
    
    // Keyword searches
    
    @Test
    public void testKeyword() throws ParseException {
        SearchCriteria criteria = new SearchParser("KEYWORD important").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testUnkeyword() throws ParseException {
        SearchCriteria criteria = new SearchParser("UNKEYWORD spam").parse();
        assertNotNull(criteria);
    }
    
    // Boolean operators
    
    @Test
    public void testNot() throws ParseException {
        SearchCriteria criteria = new SearchParser("NOT SEEN").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testOr() throws ParseException {
        SearchCriteria criteria = new SearchParser("OR SEEN FLAGGED").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testImplicitAnd() throws ParseException {
        // Multiple criteria without explicit AND are implicitly ANDed
        SearchCriteria criteria = new SearchParser("SEEN FLAGGED").parse();
        assertNotNull(criteria);
    }
    
    // Parenthesized groups
    
    @Test
    public void testParentheses() throws ParseException {
        SearchCriteria criteria = new SearchParser("(SEEN FLAGGED)").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testNestedParentheses() throws ParseException {
        SearchCriteria criteria = new SearchParser("OR (SEEN FLAGGED) (UNSEEN UNFLAGGED)").parse();
        assertNotNull(criteria);
    }
    
    // Sequence sets
    
    @Test
    public void testSequenceNumber() throws ParseException {
        SearchCriteria criteria = new SearchParser("1").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testSequenceRange() throws ParseException {
        SearchCriteria criteria = new SearchParser("1:100").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testSequenceSet() throws ParseException {
        SearchCriteria criteria = new SearchParser("1,2,3").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testSequenceWithStar() throws ParseException {
        SearchCriteria criteria = new SearchParser("1:*").parse();
        assertNotNull(criteria);
    }
    
    // UID searches
    
    @Test
    public void testUid() throws ParseException {
        SearchCriteria criteria = new SearchParser("UID 12345").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testUidRange() throws ParseException {
        SearchCriteria criteria = new SearchParser("UID 1:1000").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testUidSet() throws ParseException {
        SearchCriteria criteria = new SearchParser("UID 1,5,10").parse();
        assertNotNull(criteria);
    }
    
    // Complex queries
    
    @Test
    public void testComplexQuery() throws ParseException {
        SearchCriteria criteria = new SearchParser(
            "UNSEEN SINCE 1-Jan-2024 FROM \"boss@example.com\" SUBJECT \"urgent\""
        ).parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testVeryComplexQuery() throws ParseException {
        SearchCriteria criteria = new SearchParser(
            "OR (UNSEEN FROM boss@example.com) (FLAGGED SINCE 1-Dec-2024)"
        ).parse();
        assertNotNull(criteria);
    }
    
    // Case insensitivity
    
    @Test
    public void testCaseInsensitive() throws ParseException {
        SearchCriteria c1 = new SearchParser("SEEN").parse();
        SearchCriteria c2 = new SearchParser("seen").parse();
        SearchCriteria c3 = new SearchParser("Seen").parse();
        
        assertNotNull(c1);
        assertNotNull(c2);
        assertNotNull(c3);
    }
    
    // Quoted strings
    
    @Test
    public void testQuotedString() throws ParseException {
        SearchCriteria criteria = new SearchParser("SUBJECT \"Hello World\"").parse();
        assertNotNull(criteria);
    }
    
    @Test
    public void testQuotedStringWithEscapes() throws ParseException {
        SearchCriteria criteria = new SearchParser("SUBJECT \"He said \\\"hello\\\"\"").parse();
        assertNotNull(criteria);
    }
    
    // Error cases
    
    @Test(expected = ParseException.class)
    public void testUnknownKeyword() throws ParseException {
        new SearchParser("UNKNOWN").parse();
    }
    
    @Test(expected = ParseException.class)
    public void testMissingClosingParen() throws ParseException {
        new SearchParser("(SEEN FLAGGED").parse();
    }
    
    @Test(expected = ParseException.class)
    public void testUnterminatedQuotedString() throws ParseException {
        new SearchParser("SUBJECT \"unterminated").parse();
    }
    
    @Test(expected = ParseException.class)
    public void testInvalidDate() throws ParseException {
        new SearchParser("BEFORE invalid-date").parse();
    }
    
    @Test(expected = ParseException.class)
    public void testTrailingGarbage() throws ParseException {
        new SearchParser("SEEN invalid").parse();
    }
}

