/*
 * TagLibraryDescriptorTest.java
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

import java.util.Map;

/**
 * Unit tests for TagLibraryDescriptor and its inner classes.
 */
public class TagLibraryDescriptorTest {

    @Test
    public void testEmptyDescriptor() {
        TagLibraryDescriptor tld = new TagLibraryDescriptor();

        assertNull(tld.getTlibVersion());
        assertNull(tld.getShortName());
        assertNull(tld.getUri());
        assertTrue(tld.getTags().isEmpty());
        assertTrue(tld.getFunctions().isEmpty());
    }

    @Test
    public void testLibraryMetadata() {
        TagLibraryDescriptor tld = new TagLibraryDescriptor();
        tld.setTlibVersion("1.0");
        tld.setShortName("c");
        tld.setUri("http://example.com/tags");
        tld.setDescription("Core tag library");
        tld.setDisplayName("Core Tags");
        tld.setSmallIcon("/icons/small.gif");
        tld.setLargeIcon("/icons/large.gif");
        tld.setJspVersion("2.3");

        assertEquals("1.0", tld.getTlibVersion());
        assertEquals("c", tld.getShortName());
        assertEquals("http://example.com/tags", tld.getUri());
        assertEquals("Core tag library", tld.getDescription());
        assertEquals("Core Tags", tld.getDisplayName());
        assertEquals("/icons/small.gif", tld.getSmallIcon());
        assertEquals("/icons/large.gif", tld.getLargeIcon());
        assertEquals("2.3", tld.getJspVersion());
    }

    @Test
    public void testAddTag() {
        TagLibraryDescriptor tld = new TagLibraryDescriptor();

        TagLibraryDescriptor.TagDescriptor tag = new TagLibraryDescriptor.TagDescriptor();
        tag.setName("myTag");
        tag.setTagClass("com.example.MyTag");
        tld.addTag(tag);

        assertEquals(1, tld.getTags().size());
        assertNotNull(tld.getTag("myTag"));
        assertEquals("com.example.MyTag", tld.getTag("myTag").getTagClass());
    }

    @Test
    public void testAddMultipleTags() {
        TagLibraryDescriptor tld = new TagLibraryDescriptor();

        TagLibraryDescriptor.TagDescriptor tag1 = new TagLibraryDescriptor.TagDescriptor();
        tag1.setName("if");
        tld.addTag(tag1);

        TagLibraryDescriptor.TagDescriptor tag2 = new TagLibraryDescriptor.TagDescriptor();
        tag2.setName("forEach");
        tld.addTag(tag2);

        assertEquals(2, tld.getTags().size());
        assertNotNull(tld.getTag("if"));
        assertNotNull(tld.getTag("forEach"));
    }

    @Test
    public void testAddNullTag() {
        TagLibraryDescriptor tld = new TagLibraryDescriptor();
        tld.addTag(null);

        assertTrue(tld.getTags().isEmpty());
    }

    @Test
    public void testAddTagWithNullName() {
        TagLibraryDescriptor tld = new TagLibraryDescriptor();

        TagLibraryDescriptor.TagDescriptor tag = new TagLibraryDescriptor.TagDescriptor();
        // name not set
        tld.addTag(tag);

        assertTrue(tld.getTags().isEmpty());
    }

    @Test
    public void testGetNonExistentTag() {
        TagLibraryDescriptor tld = new TagLibraryDescriptor();
        assertNull(tld.getTag("nonexistent"));
    }

    @Test
    public void testTagsMapIsUnmodifiable() {
        TagLibraryDescriptor tld = new TagLibraryDescriptor();

        TagLibraryDescriptor.TagDescriptor tag = new TagLibraryDescriptor.TagDescriptor();
        tag.setName("test");
        tld.addTag(tag);

        Map<String, TagLibraryDescriptor.TagDescriptor> tags = tld.getTags();

        try {
            tags.put("illegal", new TagLibraryDescriptor.TagDescriptor());
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    @Test
    public void testAddFunction() {
        TagLibraryDescriptor tld = new TagLibraryDescriptor();

        TagLibraryDescriptor.FunctionDescriptor function = new TagLibraryDescriptor.FunctionDescriptor();
        function.setName("length");
        function.setFunctionClass("com.example.Functions");
        function.setFunctionSignature("int length(java.lang.Object)");
        tld.addFunction(function);

        assertEquals(1, tld.getFunctions().size());
        assertNotNull(tld.getFunction("length"));
    }

    @Test
    public void testValidator() {
        TagLibraryDescriptor tld = new TagLibraryDescriptor();
        tld.setValidatorClass("com.example.Validator");

        assertEquals("com.example.Validator", tld.getValidatorClass());
    }

    @Test
    public void testInitParams() {
        TagLibraryDescriptor tld = new TagLibraryDescriptor();
        tld.addInitParam("param1", "value1");
        tld.addInitParam("param2", "value2");

        assertEquals("value1", tld.getInitParam("param1"));
        assertEquals("value2", tld.getInitParam("param2"));
        assertEquals(2, tld.getInitParams().size());
    }

    @Test
    public void testSourceLocation() {
        TagLibraryDescriptor tld = new TagLibraryDescriptor();
        tld.setSourceLocation("/WEB-INF/tlds/test.tld");

        assertEquals("/WEB-INF/tlds/test.tld", tld.getSourceLocation());
    }

    @Test
    public void testToString() {
        TagLibraryDescriptor tld = new TagLibraryDescriptor();
        tld.setUri("http://example.com/tags");
        tld.setShortName("test");
        tld.setTlibVersion("1.0");

        String str = tld.toString();
        assertTrue(str.contains("http://example.com/tags"));
        assertTrue(str.contains("test"));
        assertTrue(str.contains("1.0"));
    }

    // TagDescriptor tests

    @Test
    public void testTagDescriptor() {
        TagLibraryDescriptor.TagDescriptor tag = new TagLibraryDescriptor.TagDescriptor();
        tag.setName("myTag");
        tag.setTagClass("com.example.MyTag");
        tag.setTeiClass("com.example.MyTagTEI");
        tag.setBodyContent("JSP");
        tag.setDescription("My custom tag");
        tag.setDisplayName("My Tag");
        tag.setDynamicAttributes(true);

        assertEquals("myTag", tag.getName());
        assertEquals("com.example.MyTag", tag.getTagClass());
        assertEquals("com.example.MyTagTEI", tag.getTeiClass());
        assertEquals("JSP", tag.getBodyContent());
        assertEquals("My custom tag", tag.getDescription());
        assertEquals("My Tag", tag.getDisplayName());
        assertTrue(tag.isDynamicAttributes());
    }

    @Test
    public void testTagDescriptorDefaultBodyContent() {
        TagLibraryDescriptor.TagDescriptor tag = new TagLibraryDescriptor.TagDescriptor();
        assertEquals("JSP", tag.getBodyContent());
    }

    @Test
    public void testTagDescriptorAddAttribute() {
        TagLibraryDescriptor.TagDescriptor tag = new TagLibraryDescriptor.TagDescriptor();

        TagLibraryDescriptor.AttributeDescriptor attr = new TagLibraryDescriptor.AttributeDescriptor();
        attr.setName("value");
        tag.addAttribute(attr);

        assertEquals(1, tag.getAttributes().size());
        assertNotNull(tag.getAttribute("value"));
    }

    @Test
    public void testTagDescriptorAddVariable() {
        TagLibraryDescriptor.TagDescriptor tag = new TagLibraryDescriptor.TagDescriptor();

        TagLibraryDescriptor.VariableDescriptor var = new TagLibraryDescriptor.VariableDescriptor();
        var.setNameGiven("item");
        tag.addVariable(var);

        assertEquals(1, tag.getVariables().size());
        assertEquals("item", tag.getVariables().get(0).getNameGiven());
    }

    @Test
    public void testTagDescriptorToString() {
        TagLibraryDescriptor.TagDescriptor tag = new TagLibraryDescriptor.TagDescriptor();
        tag.setName("test");
        tag.setTagClass("com.example.TestTag");

        String str = tag.toString();
        assertTrue(str.contains("test"));
        assertTrue(str.contains("com.example.TestTag"));
    }

    // AttributeDescriptor tests

    @Test
    public void testAttributeDescriptor() {
        TagLibraryDescriptor.AttributeDescriptor attr = new TagLibraryDescriptor.AttributeDescriptor();
        attr.setName("value");
        attr.setRequired(true);
        attr.setRtexprvalue(true);
        attr.setType("java.lang.Integer");
        attr.setDescription("The value attribute");
        attr.setFragment(true);

        assertEquals("value", attr.getName());
        assertTrue(attr.isRequired());
        assertTrue(attr.isRtexprvalue());
        assertEquals("java.lang.Integer", attr.getType());
        assertEquals("The value attribute", attr.getDescription());
        assertTrue(attr.isFragment());
    }

    @Test
    public void testAttributeDescriptorDefaults() {
        TagLibraryDescriptor.AttributeDescriptor attr = new TagLibraryDescriptor.AttributeDescriptor();

        assertFalse(attr.isRequired());
        assertFalse(attr.isRtexprvalue());
        assertEquals("java.lang.String", attr.getType());
        assertFalse(attr.isFragment());
    }

    @Test
    public void testAttributeDescriptorToString() {
        TagLibraryDescriptor.AttributeDescriptor attr = new TagLibraryDescriptor.AttributeDescriptor();
        attr.setName("test");
        attr.setRequired(true);
        attr.setType("int");

        String str = attr.toString();
        assertTrue(str.contains("test"));
        assertTrue(str.contains("required=true"));
        assertTrue(str.contains("int"));
    }

    // VariableDescriptor tests

    @Test
    public void testVariableDescriptor() {
        TagLibraryDescriptor.VariableDescriptor var = new TagLibraryDescriptor.VariableDescriptor();
        var.setNameGiven("item");
        var.setNameFromAttribute("varName");
        var.setVariableClass("java.lang.Object");
        var.setDeclare(true);
        var.setScope("AT_END");
        var.setDescription("Loop variable");

        assertEquals("item", var.getNameGiven());
        assertEquals("varName", var.getNameFromAttribute());
        assertEquals("java.lang.Object", var.getVariableClass());
        assertTrue(var.isDeclare());
        assertEquals("AT_END", var.getScope());
        assertEquals("Loop variable", var.getDescription());
    }

    @Test
    public void testVariableDescriptorDefaults() {
        TagLibraryDescriptor.VariableDescriptor var = new TagLibraryDescriptor.VariableDescriptor();

        assertEquals("java.lang.String", var.getVariableClass());
        assertTrue(var.isDeclare());
        assertEquals("NESTED", var.getScope());
    }

    @Test
    public void testVariableDescriptorToString() {
        TagLibraryDescriptor.VariableDescriptor var = new TagLibraryDescriptor.VariableDescriptor();
        var.setNameGiven("myVar");
        var.setVariableClass("java.util.List");

        String str = var.toString();
        assertTrue(str.contains("myVar"));
        assertTrue(str.contains("java.util.List"));
    }

    // FunctionDescriptor tests

    @Test
    public void testFunctionDescriptor() {
        TagLibraryDescriptor.FunctionDescriptor func = new TagLibraryDescriptor.FunctionDescriptor();
        func.setName("length");
        func.setFunctionClass("org.example.Functions");
        func.setFunctionSignature("int length(java.lang.Object)");
        func.setDescription("Returns length");

        assertEquals("length", func.getName());
        assertEquals("org.example.Functions", func.getFunctionClass());
        assertEquals("int length(java.lang.Object)", func.getFunctionSignature());
        assertEquals("Returns length", func.getDescription());
    }

    @Test
    public void testFunctionDescriptorToString() {
        TagLibraryDescriptor.FunctionDescriptor func = new TagLibraryDescriptor.FunctionDescriptor();
        func.setName("test");
        func.setFunctionClass("com.example.Test");

        String str = func.toString();
        assertTrue(str.contains("test"));
        assertTrue(str.contains("com.example.Test"));
    }
}

