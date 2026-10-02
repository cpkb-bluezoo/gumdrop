/*
 * JndiReferenceHoldersTest.java
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

import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import javax.naming.NamingException;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceContextType;
import javax.persistence.PersistenceUnit;
import javax.xml.ws.WebServiceRef;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Covers the descriptor reference holders (resource, service, persistence,
 * message destination and resource environment references) and the default
 * resolution order of {@link Injectable}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JndiReferenceHoldersTest {

    private static Map<String, Object> values(Object... kv) {
        Map<String, Object> m = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    public void resourceRefFromJavaxAnnotation() {
        javax.annotation.Resource a = AnnotationStubs.annotation(javax.annotation.Resource.class,
                values("description", "dd", "name", "jdbc/x", "lookup", "java:comp/env/l",
                        "mappedName", "mm", "type", String.class, "shareable", Boolean.TRUE,
                        "authenticationType", javax.annotation.Resource.AuthenticationType.APPLICATION));
        ResourceRef r = new ResourceRef();
        r.init(a);
        assertEquals("jdbc/x", r.getName());
        assertEquals("java:comp/env/l", r.getLookupName());
        assertEquals("mm", r.getMappedName());
        assertEquals("java.lang.String", r.className);
        assertEquals("Shareable", r.resSharingScope);
        assertEquals(javax.annotation.Resource.AuthenticationType.APPLICATION, r.resAuth);
        assertEquals("dd", r.description);
    }

    @Test
    public void resourceRefFromJakartaAnnotation() {
        jakarta.annotation.Resource a = AnnotationStubs.annotation(jakarta.annotation.Resource.class,
                values("name", "jdbc/j", "type", Integer.class, "shareable", Boolean.FALSE,
                        "authenticationType", jakarta.annotation.Resource.AuthenticationType.APPLICATION));
        ResourceRef r = new ResourceRef();
        r.init(a);
        assertEquals("jdbc/j", r.getName());
        assertEquals("java.lang.Integer", r.className);
        assertEquals("Unshareable", r.resSharingScope);
        assertEquals(javax.annotation.Resource.AuthenticationType.APPLICATION, r.resAuth);
    }

    @Test
    public void resourceRefSettersAndInjectable() {
        ResourceRef r = new ResourceRef();
        r.setDescription("d");
        r.setName("n");
        r.setClassName("c");
        r.setResAuth(javax.annotation.Resource.AuthenticationType.CONTAINER);
        r.setResSharingScope("Shareable");
        r.setMappedName("m");
        r.setLookupName("l");
        InjectionTarget t = new InjectionTarget();
        r.setInjectionTarget(t);
        assertEquals("m", r.getMappedName());
        assertEquals("l", r.getLookupName());
        assertSame(t, r.getInjectionTarget());
        assertEquals("c", r.className);
        assertEquals(javax.annotation.Resource.AuthenticationType.CONTAINER, r.resAuth);
    }

    @Test
    public void serviceRefSettersAndAnnotation() {
        ServiceRef s = new ServiceRef();
        s.setName("svc");
        s.setServiceInterface("si");
        s.setClassName("cn");
        s.setWsdlFile("w.wsdl");
        s.setJaxrpcMappingFile("map.xml");
        s.setServiceQname("{ns}q");
        s.setPortComponentRef("pc");
        Object handler = new Object();
        s.setHandler(handler);
        Object chain = new Object();
        s.addHandlerChain(chain);
        s.setDescription("d");
        s.setDisplayName("dn");
        s.setSmallIcon("s.png");
        s.setLargeIcon("l.png");
        s.setMappedName("mapped");
        s.setLookupName("look");
        InjectionTarget t = new InjectionTarget();
        s.setInjectionTarget(t);
        assertEquals("svc", s.getName());
        assertEquals("d", s.getDescription());
        assertEquals("dn", s.getDisplayName());
        assertEquals("s.png", s.getSmallIcon());
        assertEquals("l.png", s.getLargeIcon());
        assertEquals("mapped", s.getMappedName());
        assertEquals("look", s.getLookupName());
        assertSame(t, s.getInjectionTarget());
        assertSame(handler, s.handler);
        assertSame(chain, s.handlerChains.get(0));
        WebServiceRef a = AnnotationStubs.annotation(WebServiceRef.class,
                values("name", "svc/a", "type", String.class, "value", Integer.class,
                        "wsdlLocation", "x.wsdl", "lookup", "lk", "mappedName", "mp"));
        ServiceRef fromAnnotation = new ServiceRef();
        fromAnnotation.init(a);
        assertEquals("svc/a", fromAnnotation.getName());
        assertEquals("java.lang.String", fromAnnotation.className);
        assertEquals("java.lang.Integer", fromAnnotation.serviceInterface);
        assertEquals("x.wsdl", fromAnnotation.wsdlFile);
        assertEquals("lk", fromAnnotation.getLookupName());
        assertEquals("mp", fromAnnotation.getMappedName());
    }

    @Test
    public void persistenceRefsFromAnnotations() {
        PersistenceUnitRef u = new PersistenceUnitRef();
        u.init(AnnotationStubs.annotation(PersistenceUnit.class, values("name", "pu/a", "unitName", "ua")));
        assertEquals("pu/a", u.getName());
        assertEquals("java:comp/env/ua", u.getDefaultName());
        PersistenceUnitRef ju = new PersistenceUnitRef();
        ju.init(AnnotationStubs.annotation(jakarta.persistence.PersistenceUnit.class,
                values("name", "pu/j", "unitName", "uj")));
        assertEquals("pu/j", ju.getName());
        assertEquals("java:comp/env/uj", ju.getDefaultName());
        PersistenceContextRef c = new PersistenceContextRef();
        c.init(AnnotationStubs.annotation(PersistenceContext.class,
                values("name", "pc/a", "unitName", "ca", "type", PersistenceContextType.EXTENDED)));
        assertEquals("pc/a", c.getName());
        assertEquals(PersistenceContextType.EXTENDED, c.type);
        assertEquals("java:comp/env/ca", c.getDefaultName());
        PersistenceContextRef jc = new PersistenceContextRef();
        jc.init(AnnotationStubs.annotation(jakarta.persistence.PersistenceContext.class,
                values("name", "pc/j", "unitName", "cj",
                        "type", jakarta.persistence.PersistenceContextType.EXTENDED)));
        assertEquals(PersistenceContextType.EXTENDED, jc.type);
        assertEquals("cj", jc.unitName);
    }

    @Test
    public void persistenceAndMessageRefSettersAndInjectable() {
        PersistenceContextRef c = new PersistenceContextRef();
        c.setDescription("d");
        c.setName("n");
        c.setType(PersistenceContextType.TRANSACTION);
        c.setUnitName("u");
        c.setMappedName("m");
        c.setLookupName("l");
        InjectionTarget t = new InjectionTarget();
        c.setInjectionTarget(t);
        assertEquals("m", c.getMappedName());
        assertEquals("l", c.getLookupName());
        assertSame(t, c.getInjectionTarget());
        PersistenceUnitRef u = new PersistenceUnitRef();
        u.setMappedName("m");
        u.setLookupName("l");
        u.setInjectionTarget(t);
        u.setDescription("d");
        assertEquals("m", u.getMappedName());
        assertEquals("l", u.getLookupName());
        assertSame(t, u.getInjectionTarget());
        MessageDestinationRef md = new MessageDestinationRef();
        md.setDescription("d");
        md.setName("n");
        md.setClassName("c");
        md.setMessageDestinationUsage("Consumes");
        md.setMessageDestinationLink("link");
        md.setMappedName("m");
        md.setLookupName("l");
        md.setInjectionTarget(t);
        assertEquals("m", md.getMappedName());
        assertEquals("l", md.getLookupName());
        assertSame(t, md.getInjectionTarget());
        assertEquals("link", md.messageDestinationLink);
        ResourceEnvRef env = new ResourceEnvRef();
        env.setName("n");
        env.setClassName("c");
        env.setMappedName("m");
        env.setLookupName("l");
        env.setInjectionTarget(t);
        assertEquals("m", env.getMappedName());
        assertEquals("l", env.getLookupName());
        assertSame(t, env.getInjectionTarget());
    }

    private ServletInitialContext context() {
        return new ServletInitialContext(new Hashtable<String, String>());
    }

    @Test
    public void injectableResolvesLookupThenMappedThenDefault() throws NamingException {
        ServletInitialContext ctx = context();
        PersistenceUnitRef u = new PersistenceUnitRef();
        u.setName("pu/r");
        u.setUnitName("unit");
        assertNull(u.resolve(ctx));
        ctx.bind("java:comp/env/unit", "by-default");
        // without an injection target the default name is not consulted
        assertNull(u.resolve(ctx));
        u.setInjectionTarget(new InjectionTarget());
        assertEquals("by-default", u.resolve(ctx));
        ctx.bind("java:comp/env/mapped", "by-mapped");
        u.setMappedName("mapped");
        assertEquals("by-mapped", u.resolve(ctx));
        ctx.bind("java:comp/env/lookup", "by-lookup");
        u.setLookupName("java:comp/env/lookup");
        assertEquals("by-lookup", u.resolve(ctx));
        u.setLookupName("java:comp/env/absent");
        assertEquals("by-mapped", u.resolve(ctx));
        u.setMappedName("absent");
        assertEquals("by-default", u.resolve(ctx));
        u.setLookupName("");
        u.setMappedName("");
        assertEquals("by-default", u.resolve(ctx));
    }
}
