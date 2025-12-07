/*
 * TldParserTest.java
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
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Unit tests for TldParser.
 */
public class TldParserTest {

    @Test
    public void testParseEmptyTaglib() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n" +
            "<taglib>\n" +
            "</taglib>";

        TagLibraryDescriptor result = parseTld(tld);
        assertNotNull(result);
    }

    @Test
    public void testParseLibraryMetadata() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n" +
            "<taglib>\n" +
            "  <tlib-version>1.0</tlib-version>\n" +
            "  <short-name>test</short-name>\n" +
            "  <uri>http://example.com/tags</uri>\n" +
            "  <description>Test tag library</description>\n" +
            "  <display-name>Test Tags</display-name>\n" +
            "</taglib>";

        TagLibraryDescriptor result = parseTld(tld);

        assertEquals("1.0", result.getTlibVersion());
        assertEquals("test", result.getShortName());
        assertEquals("http://example.com/tags", result.getUri());
        assertEquals("Test tag library", result.getDescription());
        assertEquals("Test Tags", result.getDisplayName());
    }

    @Test
    public void testParseSimpleTag() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n" +
            "<taglib>\n" +
            "  <short-name>test</short-name>\n" +
            "  <tag>\n" +
            "    <name>hello</name>\n" +
            "    <tag-class>com.example.HelloTag</tag-class>\n" +
            "    <body-content>empty</body-content>\n" +
            "  </tag>\n" +
            "</taglib>";

        TagLibraryDescriptor result = parseTld(tld);

        TagLibraryDescriptor.TagDescriptor tag = result.getTag("hello");
        assertNotNull(tag);
        assertEquals("hello", tag.getName());
        assertEquals("com.example.HelloTag", tag.getTagClass());
        assertEquals("empty", tag.getBodyContent());
    }

    @Test
    public void testParseTagWithAttributes() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n" +
            "<taglib>\n" +
            "  <tag>\n" +
            "    <name>format</name>\n" +
            "    <tag-class>com.example.FormatTag</tag-class>\n" +
            "    <body-content>JSP</body-content>\n" +
            "    <attribute>\n" +
            "      <name>pattern</name>\n" +
            "      <required>true</required>\n" +
            "      <rtexprvalue>true</rtexprvalue>\n" +
            "    </attribute>\n" +
            "    <attribute>\n" +
            "      <name>locale</name>\n" +
            "      <required>false</required>\n" +
            "      <type>java.util.Locale</type>\n" +
            "    </attribute>\n" +
            "  </tag>\n" +
            "</taglib>";

        TagLibraryDescriptor result = parseTld(tld);

        TagLibraryDescriptor.TagDescriptor tag = result.getTag("format");
        assertNotNull(tag);
        assertEquals(2, tag.getAttributes().size());

        TagLibraryDescriptor.AttributeDescriptor pattern = tag.getAttribute("pattern");
        assertNotNull(pattern);
        assertTrue(pattern.isRequired());
        assertTrue(pattern.isRtexprvalue());

        TagLibraryDescriptor.AttributeDescriptor locale = tag.getAttribute("locale");
        assertNotNull(locale);
        assertFalse(locale.isRequired());
        assertEquals("java.util.Locale", locale.getType());
    }

    @Test
    public void testParseTagWithVariable() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n" +
            "<taglib>\n" +
            "  <tag>\n" +
            "    <name>iterate</name>\n" +
            "    <tag-class>com.example.IterateTag</tag-class>\n" +
            "    <variable>\n" +
            "      <name-given>item</name-given>\n" +
            "      <variable-class>java.lang.Object</variable-class>\n" +
            "      <scope>NESTED</scope>\n" +
            "    </variable>\n" +
            "  </tag>\n" +
            "</taglib>";

        TagLibraryDescriptor result = parseTld(tld);

        TagLibraryDescriptor.TagDescriptor tag = result.getTag("iterate");
        assertNotNull(tag);
        assertEquals(1, tag.getVariables().size());

        TagLibraryDescriptor.VariableDescriptor variable = tag.getVariables().get(0);
        assertEquals("item", variable.getNameGiven());
        assertEquals("java.lang.Object", variable.getVariableClass());
        assertEquals("NESTED", variable.getScope());
    }

    @Test
    public void testParseFunction() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n" +
            "<taglib>\n" +
            "  <function>\n" +
            "    <name>length</name>\n" +
            "    <function-class>org.example.Functions</function-class>\n" +
            "    <function-signature>int length(java.lang.Object)</function-signature>\n" +
            "    <description>Returns collection length</description>\n" +
            "  </function>\n" +
            "</taglib>";

        TagLibraryDescriptor result = parseTld(tld);

        TagLibraryDescriptor.FunctionDescriptor function = result.getFunction("length");
        assertNotNull(function);
        assertEquals("length", function.getName());
        assertEquals("org.example.Functions", function.getFunctionClass());
        assertEquals("int length(java.lang.Object)", function.getFunctionSignature());
        assertEquals("Returns collection length", function.getDescription());
    }

    @Test
    public void testParseMultipleTags() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n" +
            "<taglib>\n" +
            "  <tag>\n" +
            "    <name>if</name>\n" +
            "    <tag-class>com.example.IfTag</tag-class>\n" +
            "  </tag>\n" +
            "  <tag>\n" +
            "    <name>forEach</name>\n" +
            "    <tag-class>com.example.ForEachTag</tag-class>\n" +
            "  </tag>\n" +
            "  <tag>\n" +
            "    <name>out</name>\n" +
            "    <tag-class>com.example.OutTag</tag-class>\n" +
            "  </tag>\n" +
            "</taglib>";

        TagLibraryDescriptor result = parseTld(tld);

        assertEquals(3, result.getTags().size());
        assertNotNull(result.getTag("if"));
        assertNotNull(result.getTag("forEach"));
        assertNotNull(result.getTag("out"));
    }

    @Test
    public void testParseTagWithTeiClass() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n" +
            "<taglib>\n" +
            "  <tag>\n" +
            "    <name>custom</name>\n" +
            "    <tag-class>com.example.CustomTag</tag-class>\n" +
            "    <tei-class>com.example.CustomTEI</tei-class>\n" +
            "  </tag>\n" +
            "</taglib>";

        TagLibraryDescriptor result = parseTld(tld);

        TagLibraryDescriptor.TagDescriptor tag = result.getTag("custom");
        assertNotNull(tag);
        assertEquals("com.example.CustomTEI", tag.getTeiClass());
    }

    @Test
    public void testParseDynamicAttributes() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n" +
            "<taglib>\n" +
            "  <tag>\n" +
            "    <name>dynamic</name>\n" +
            "    <tag-class>com.example.DynamicTag</tag-class>\n" +
            "    <dynamic-attributes>true</dynamic-attributes>\n" +
            "  </tag>\n" +
            "</taglib>";

        TagLibraryDescriptor result = parseTld(tld);

        TagLibraryDescriptor.TagDescriptor tag = result.getTag("dynamic");
        assertNotNull(tag);
        assertTrue(tag.isDynamicAttributes());
    }

    @Test
    public void testParseValidator() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n" +
            "<taglib>\n" +
            "  <validator-class>com.example.TaglibValidator</validator-class>\n" +
            "</taglib>";

        TagLibraryDescriptor result = parseTld(tld);

        assertEquals("com.example.TaglibValidator", result.getValidatorClass());
    }

    @Test
    public void testParseJspVersion() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n" +
            "<taglib>\n" +
            "  <jsp-version>2.3</jsp-version>\n" +
            "</taglib>";

        TagLibraryDescriptor result = parseTld(tld);

        assertEquals("2.3", result.getJspVersion());
    }

    @Test
    public void testParseFragmentAttribute() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n" +
            "<taglib>\n" +
            "  <tag>\n" +
            "    <name>body</name>\n" +
            "    <tag-class>com.example.BodyTag</tag-class>\n" +
            "    <attribute>\n" +
            "      <name>header</name>\n" +
            "      <fragment>true</fragment>\n" +
            "    </attribute>\n" +
            "  </tag>\n" +
            "</taglib>";

        TagLibraryDescriptor result = parseTld(tld);

        TagLibraryDescriptor.TagDescriptor tag = result.getTag("body");
        assertNotNull(tag);
        TagLibraryDescriptor.AttributeDescriptor attr = tag.getAttribute("header");
        assertNotNull(attr);
        assertTrue(attr.isFragment());
    }

    @Test
    public void testParseVariableFromAttribute() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n" +
            "<taglib>\n" +
            "  <tag>\n" +
            "    <name>setVar</name>\n" +
            "    <tag-class>com.example.SetVarTag</tag-class>\n" +
            "    <variable>\n" +
            "      <name-from-attribute>var</name-from-attribute>\n" +
            "      <variable-class>java.lang.String</variable-class>\n" +
            "      <declare>true</declare>\n" +
            "      <scope>AT_END</scope>\n" +
            "    </variable>\n" +
            "  </tag>\n" +
            "</taglib>";

        TagLibraryDescriptor result = parseTld(tld);

        TagLibraryDescriptor.TagDescriptor tag = result.getTag("setVar");
        TagLibraryDescriptor.VariableDescriptor variable = tag.getVariables().get(0);
        assertEquals("var", variable.getNameFromAttribute());
        assertTrue(variable.isDeclare());
        assertEquals("AT_END", variable.getScope());
    }

    @Test
    public void testParseSourceLocation() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n<taglib></taglib>";

        TagLibraryDescriptor result = TldParser.parseTld(
            new ByteArrayInputStream(tld.getBytes(StandardCharsets.UTF_8)),
            "/WEB-INF/tlds/test.tld"
        );

        assertEquals("/WEB-INF/tlds/test.tld", result.getSourceLocation());
    }

    @Test
    public void testParseJstlCoreStyleTaglib() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n" +
            "<taglib xmlns=\"http://java.sun.com/xml/ns/j2ee\" version=\"2.0\">\n" +
            "  <tlib-version>1.2</tlib-version>\n" +
            "  <short-name>c</short-name>\n" +
            "  <uri>http://java.sun.com/jsp/jstl/core</uri>\n" +
            "  <tag>\n" +
            "    <name>if</name>\n" +
            "    <tag-class>org.apache.taglibs.standard.tag.rt.core.IfTag</tag-class>\n" +
            "    <body-content>JSP</body-content>\n" +
            "    <attribute>\n" +
            "      <name>test</name>\n" +
            "      <required>true</required>\n" +
            "      <rtexprvalue>true</rtexprvalue>\n" +
            "      <type>boolean</type>\n" +
            "    </attribute>\n" +
            "    <attribute>\n" +
            "      <name>var</name>\n" +
            "      <required>false</required>\n" +
            "    </attribute>\n" +
            "  </tag>\n" +
            "</taglib>";

        TagLibraryDescriptor result = parseTld(tld);

        assertEquals("1.2", result.getTlibVersion());
        assertEquals("c", result.getShortName());
        assertEquals("http://java.sun.com/jsp/jstl/core", result.getUri());

        TagLibraryDescriptor.TagDescriptor ifTag = result.getTag("if");
        assertNotNull(ifTag);
        assertEquals("org.apache.taglibs.standard.tag.rt.core.IfTag", ifTag.getTagClass());
        assertEquals("JSP", ifTag.getBodyContent());

        TagLibraryDescriptor.AttributeDescriptor testAttr = ifTag.getAttribute("test");
        assertNotNull(testAttr);
        assertTrue(testAttr.isRequired());
        assertTrue(testAttr.isRtexprvalue());
        assertEquals("boolean", testAttr.getType());
    }

    @Test
    public void testParseTagIcons() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n" +
            "<taglib>\n" +
            "  <small-icon>/icons/small.gif</small-icon>\n" +
            "  <large-icon>/icons/large.gif</large-icon>\n" +
            "  <tag>\n" +
            "    <name>myTag</name>\n" +
            "    <tag-class>com.example.MyTag</tag-class>\n" +
            "    <small-icon>/icons/tag-small.gif</small-icon>\n" +
            "    <large-icon>/icons/tag-large.gif</large-icon>\n" +
            "  </tag>\n" +
            "</taglib>";

        TagLibraryDescriptor result = parseTld(tld);

        assertEquals("/icons/small.gif", result.getSmallIcon());
        assertEquals("/icons/large.gif", result.getLargeIcon());

        TagLibraryDescriptor.TagDescriptor tag = result.getTag("myTag");
        assertEquals("/icons/tag-small.gif", tag.getSmallIcon());
        assertEquals("/icons/tag-large.gif", tag.getLargeIcon());
    }

    @Test
    public void testParseMultipleFunctions() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n" +
            "<taglib>\n" +
            "  <function>\n" +
            "    <name>contains</name>\n" +
            "    <function-class>org.example.Functions</function-class>\n" +
            "    <function-signature>boolean contains(java.lang.String, java.lang.String)</function-signature>\n" +
            "  </function>\n" +
            "  <function>\n" +
            "    <name>trim</name>\n" +
            "    <function-class>org.example.Functions</function-class>\n" +
            "    <function-signature>java.lang.String trim(java.lang.String)</function-signature>\n" +
            "  </function>\n" +
            "</taglib>";

        TagLibraryDescriptor result = parseTld(tld);

        assertEquals(2, result.getFunctions().size());
        assertNotNull(result.getFunction("contains"));
        assertNotNull(result.getFunction("trim"));
    }

    @Test(expected = IOException.class)
    public void testParseMalformedXml() throws IOException {
        String tld = "<?xml version=\"1.0\"?>\n" +
            "<taglib>\n" +
            "  <tag><name>broken</unclosed>\n";

        parseTld(tld);
    }

    private TagLibraryDescriptor parseTld(String tldContent) throws IOException {
        return TldParser.parseTld(
            new ByteArrayInputStream(tldContent.getBytes(StandardCharsets.UTF_8)),
            "test.tld"
        );
    }
}

