/*
 * TldParserBranchTest.java
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

package org.bluezoo.gumdrop.servlet.jsp;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Branch-level unit tests for {@link TldParser}: the context rules that decide
 * which descriptor a shared element name ({@code description}, {@code name},
 * the icon elements) belongs to, boolean flag parsing and ignored elements.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TldParserBranchTest {

    private static TagLibraryDescriptor parse(String body) throws IOException {
        String xml = "<?xml version=\"1.0\"?>\n<taglib>" + body + "</taglib>";
        return TldParser.parseTld(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "branch.tld");
    }

    @Test
    public void testLibraryLevelElements() throws IOException {
        TagLibraryDescriptor tld = parse("<tlib-version>2.1</tlib-version>"
                + "<short-name>sn</short-name><uri>urn:x</uri>"
                + "<jsp-version>2.3</jsp-version>"
                + "<validator-class>a.V</validator-class>"
                + "<description>lib</description><display-name>Lib</display-name>"
                + "<small-icon>s.png</small-icon><large-icon>l.png</large-icon>");
        assertEquals("2.1", tld.getTlibVersion());
        assertEquals("sn", tld.getShortName());
        assertEquals("urn:x", tld.getUri());
        assertEquals("2.3", tld.getJspVersion());
        assertEquals("a.V", tld.getValidatorClass());
        assertEquals("lib", tld.getDescription());
        assertEquals("Lib", tld.getDisplayName());
        assertEquals("s.png", tld.getSmallIcon());
        assertEquals("l.png", tld.getLargeIcon());
        assertEquals("branch.tld", tld.getSourceLocation());
    }

    @Test
    public void testTagLevelMetadataDoesNotLeakToLibrary() throws IOException {
        TagLibraryDescriptor tld = parse("<description>lib</description>"
                + "<tag><name>t</name><tag-class>a.T</tag-class><tei-class>a.TEI</tei-class>"
                + "<body-content>empty</body-content>"
                + "<description>tag desc</description><display-name>Tag</display-name>"
                + "<small-icon>ts.png</small-icon><large-icon>tl.png</large-icon>"
                + "<dynamic-attributes>TRUE</dynamic-attributes></tag>");
        assertEquals("lib", tld.getDescription());
        assertNull(tld.getDisplayName());
        assertNull(tld.getSmallIcon());
        assertNull(tld.getLargeIcon());
        TagLibraryDescriptor.TagDescriptor tag = tld.getTag("t");
        assertNotNull(tag);
        assertEquals("a.T", tag.getTagClass());
        assertEquals("a.TEI", tag.getTeiClass());
        assertEquals("empty", tag.getBodyContent());
        assertEquals("tag desc", tag.getDescription());
        assertEquals("Tag", tag.getDisplayName());
        assertEquals("ts.png", tag.getSmallIcon());
        assertEquals("tl.png", tag.getLargeIcon());
        assertTrue(tag.isDynamicAttributes());
    }

    @Test
    public void testDynamicAttributesFalseAndAnyOtherValue() throws IOException {
        TagLibraryDescriptor tld = parse("<tag><name>a</name><dynamic-attributes>false</dynamic-attributes></tag>"
                + "<tag><name>b</name><dynamic-attributes>yes</dynamic-attributes></tag>");
        assertFalse(tld.getTag("a").isDynamicAttributes());
        assertFalse(tld.getTag("b").isDynamicAttributes());
    }

    @Test
    public void testAttributeLevelDescriptionStaysOnAttribute() throws IOException {
        TagLibraryDescriptor tld = parse("<tag><name>t</name><description>tag desc</description>"
                + "<attribute><name>a1</name><required>true</required><rtexprvalue>true</rtexprvalue>"
                + "<type>java.lang.String</type><description>attr desc</description>"
                + "<fragment>true</fragment></attribute>"
                + "<attribute><name>a2</name><required>false</required><rtexprvalue>no</rtexprvalue>"
                + "<fragment>false</fragment></attribute></tag>");
        TagLibraryDescriptor.TagDescriptor tag = tld.getTag("t");
        assertEquals("tag desc", tag.getDescription());
        TagLibraryDescriptor.AttributeDescriptor a1 = tag.getAttribute("a1");
        assertTrue(a1.isRequired());
        assertTrue(a1.isRtexprvalue());
        assertTrue(a1.isFragment());
        assertEquals("java.lang.String", a1.getType());
        assertEquals("attr desc", a1.getDescription());
        TagLibraryDescriptor.AttributeDescriptor a2 = tag.getAttribute("a2");
        assertFalse(a2.isRequired());
        assertFalse(a2.isRtexprvalue());
        assertFalse(a2.isFragment());
        assertNull(a2.getDescription());
        assertNull(tld.getDescription());
    }

    @Test
    public void testVariableLevelElements() throws IOException {
        TagLibraryDescriptor tld = parse("<tag><name>t</name><description>tag desc</description>"
                + "<variable><name-given>v1</name-given><variable-class>java.lang.Integer</variable-class>"
                + "<declare>true</declare><scope>AT_END</scope><description>var desc</description>"
                + "<display-name>ignored</display-name><small-icon>ignored.png</small-icon></variable>"
                + "<variable><name-from-attribute>v2</name-from-attribute><declare>false</declare></variable>"
                + "</tag>");
        TagLibraryDescriptor.TagDescriptor tag = tld.getTag("t");
        assertEquals("tag desc", tag.getDescription());
        assertNull(tag.getDisplayName());
        assertNull(tag.getSmallIcon());
        List<TagLibraryDescriptor.VariableDescriptor> vars = tag.getVariables();
        assertEquals(2, vars.size());
        assertEquals("v1", vars.get(0).getNameGiven());
        assertEquals("java.lang.Integer", vars.get(0).getVariableClass());
        assertTrue(vars.get(0).isDeclare());
        assertEquals("AT_END", vars.get(0).getScope());
        assertEquals("var desc", vars.get(0).getDescription());
        assertEquals("v2", vars.get(1).getNameFromAttribute());
        assertFalse(vars.get(1).isDeclare());
        assertNull(tld.getDescription());
        assertNull(tld.getDisplayName());
    }

    @Test
    public void testFunctionLevelElements() throws IOException {
        TagLibraryDescriptor tld = parse("<description>lib</description>"
                + "<function><name>f</name><function-class>a.F</function-class>"
                + "<function-signature>int f(int)</function-signature>"
                + "<description>fn desc</description><display-name>nope</display-name>"
                + "<small-icon>nope.png</small-icon><large-icon>nope2.png</large-icon></function>");
        TagLibraryDescriptor.FunctionDescriptor f = tld.getFunction("f");
        assertNotNull(f);
        assertEquals("a.F", f.getFunctionClass());
        assertEquals("int f(int)", f.getFunctionSignature());
        assertEquals("fn desc", f.getDescription());
        assertEquals("lib", tld.getDescription());
        assertNull(tld.getDisplayName());
        assertNull(tld.getSmallIcon());
        assertNull(tld.getLargeIcon());
    }

    @Test
    public void testUnknownElementsAndTagWithoutNameAreHarmless() throws IOException {
        TagLibraryDescriptor tld = parse("<unknown>x</unknown><tag><tag-class>a.T</tag-class><extra/></tag>");
        assertNotNull(tld);
        assertTrue(tld.getTags().size() <= 1);
        assertEquals(0, tld.getFunctions().size());
    }

    @Test
    public void testElementsOutsideTaglibContextAreIgnored() throws IOException {
        String xml = "<?xml version=\"1.0\"?>\n<other><uri>urn:ignored</uri></other>";
        TagLibraryDescriptor tld = TldParser.parseTld(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "other.tld");
        assertNotNull(tld);
        assertNull(tld.getUri());
    }
}
