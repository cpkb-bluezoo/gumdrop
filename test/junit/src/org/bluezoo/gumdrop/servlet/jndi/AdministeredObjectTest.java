/*
 * AdministeredObjectTest.java
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

package org.bluezoo.gumdrop.servlet.jndi;

import org.junit.Test;
import org.xml.sax.helpers.AttributesImpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests {@link AdministeredObject}: attribute initialisation, injectable
 * accessors and reflective bean creation with typed property setters.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AdministeredObjectTest {

    /** Bean populated through setters of various types. */
    public static class Bean {
        private String text;
        private int count;
        private boolean flag;
        private long big;
        private double ratio;
        private float small;

        public Bean() {
        }

        public void setText(String text) {
            this.text = text;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public void setFlag(boolean flag) {
            this.flag = flag;
        }

        public void setBig(Long big) {
            this.big = big.longValue();
        }

        public void setRatio(double ratio) {
            this.ratio = ratio;
        }

        public void setSmall(Float small) {
            this.small = small.floatValue();
        }
    }

    /** Class without a public no-argument constructor. */
    public static class NoDefault {
        public NoDefault(String s) {
        }
    }

    @Test
    public void testInitFromAttributes() {
        AttributesImpl atts = new AttributesImpl();
        atts.addAttribute("", "description", "description", "CDATA", "d");
        atts.addAttribute("", "jndi-name", "jndi-name", "CDATA", "ao/x");
        atts.addAttribute("", "administered-object-interface", "administered-object-interface", "CDATA", "a.I");
        atts.addAttribute("", "administered-object-class", "administered-object-class", "CDATA", "a.C");
        AdministeredObject ao = new AdministeredObject();
        ao.init(atts);
        assertEquals("ao/x", ao.getName());
        assertEquals("a.I", ao.getInterfaceName());
        assertEquals("a.C", ao.getClassName());
        assertEquals("d", ao.description);
    }

    @Test
    public void testInjectableAccessors() {
        AdministeredObject ao = new AdministeredObject();
        InjectionTarget t = new InjectionTarget();
        ao.setLookupName("l");
        ao.setMappedName("m");
        ao.setInjectionTarget(t);
        ao.setDescription("d");
        ao.setJndiName("j");
        ao.setAdministeredObjectInterface("i");
        ao.setAdministeredObjectClass("c");
        assertEquals("l", ao.getLookupName());
        assertEquals("m", ao.getMappedName());
        assertSame(t, ao.getInjectionTarget());
        assertEquals("j", ao.getName());
    }

    @Test
    public void testNewInstanceWithoutClassReturnsNull() {
        AdministeredObject ao = new AdministeredObject();
        ao.setJndiName("ao/none");
        assertNull(ao.newInstance());
    }

    @Test
    public void testNewInstanceSetsTypedProperties() {
        AdministeredObject ao = new AdministeredObject();
        ao.setJndiName("ao/bean");
        ao.setAdministeredObjectClass(Bean.class.getName());
        ao.addProperty("text", "hello");
        ao.addProperty("count", "7");
        ao.addProperty("flag", "true");
        ao.addProperty("big", "123456789012");
        ao.addProperty("ratio", "0.5");
        ao.addProperty("small", "1.5");
        ao.addProperty("missing", "ignored");
        Object o = ao.newInstance();
        assertNotNull(o);
        Bean b = (Bean) o;
        assertEquals("hello", b.text);
        assertEquals(7, b.count);
        assertTrue(b.flag);
        assertEquals(123456789012L, b.big);
        assertEquals(0.5, b.ratio, 0.0);
        assertEquals(1.5f, b.small, 0.0f);
    }

    @Test
    public void testNewInstanceFailuresReturnNull() {
        AdministeredObject missing = new AdministeredObject();
        missing.setJndiName("ao/missing");
        missing.setAdministeredObjectClass("no.such.Class");
        assertNull(missing.newInstance());
        AdministeredObject noCtor = new AdministeredObject();
        noCtor.setJndiName("ao/noctor");
        noCtor.setAdministeredObjectClass(NoDefault.class.getName());
        assertNull(noCtor.newInstance());
    }
}
