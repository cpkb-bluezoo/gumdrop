/*
 * JndiResourceDefinitionsTest.java
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
import java.util.Map;
import java.util.Properties;

import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.MailSessionDefinition;
import jakarta.mail.NoSuchProviderException;

import org.junit.Test;
import org.xml.sax.helpers.AttributesImpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Covers the mail session, JMS and JCA resource definitions and the base
 * {@link Resource#newInstance()} behaviour.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JndiResourceDefinitionsTest {

    /** Provider registered through a mail session definition. */
    public static class StubProvider extends Provider {
        public StubProvider() {
            super(Provider.Type.STORE, "stubproto", "x.Y", "vendor", "1.0");
        }
    }

    private static AttributesImpl attrs(String[][] pairs) {
        AttributesImpl a = new AttributesImpl();
        for (int i = 0; i < pairs.length; i++) {
            a.addAttribute("", pairs[i][0], pairs[i][0], "CDATA", pairs[i][1]);
        }
        return a;
    }

    // MailSession

    private MailSession fullMailSession() {
        MailSession m = new MailSession();
        m.setDescription("d");
        m.setName("mail/s");
        m.setStoreProtocol("pop3");
        m.setTransportProtocol("smtps");
        m.setHost("mail.example.org");
        m.setUser("u");
        m.setPassword("p");
        m.setFrom("me@example.org");
        m.addProperty("mail.debug", "true");
        return m;
    }

    @Test
    public void mailSessionBuildsConfiguredSession() {
        MailSession m = fullMailSession();
        assertEquals("mail/s", m.getName());
        assertNull(m.getClassName());
        assertEquals("jakarta.mail.Session", m.getInterfaceName());
        Object o = m.newInstance();
        assertTrue(o instanceof Session);
        Session s = (Session) o;
        Properties p = s.getProperties();
        assertEquals("mail.example.org", p.getProperty("mail.host"));
        assertEquals("pop3", p.getProperty("mail.store.protocol"));
        assertEquals("smtps", p.getProperty("mail.transport.protocol"));
        assertEquals("true", p.getProperty("mail.debug"));
        assertEquals("me@example.org", p.getProperty("mail.from"));
    }

    @Test
    public void mailSessionAuthenticatorSuppliesCredentials() {
        MailSession m = fullMailSession();
        PasswordAuthentication pa = new PasswordAuthentication("u", "p");
        MailSession.MailSessionAuthenticator a = m.new MailSessionAuthenticator(pa);
        PasswordAuthentication got = a.getPasswordAuthentication();
        assertSame(pa, got);
    }

    @Test
    public void mailSessionRegistersProviders() {
        MailSession m = fullMailSession();
        m.setStoreProtocolClass(StubProvider.class.getName());
        m.setTransportProtocolClass("no.such.Provider");
        Session s = (Session) m.newInstance();
        Provider p = null;
        try {
            p = s.getProvider("stubproto");
        } catch (NoSuchProviderException e) {
            fail(e.toString());
        }
        assertNotNull(p);
        // not a provider at all: logged and ignored
        m.setStoreProtocolClass("java.lang.String");
        assertNotNull(m.newInstance());
    }

    @Test
    public void mailSessionInitFromAttributes() {
        MailSession m = new MailSession();
        m.init(attrs(new String[][] {
            { "description", "desc" }, { "name", "mail/a" }, { "store-protocol", "imaps" },
            { "store-protocol-class", "a.B" }, { "transport-protocol", "smtp" },
            { "transport-protocol-class", "c.D" }, { "host", "h" }, { "user", "u" },
            { "password", "p" }, { "from", "f@example.org" } }));
        assertEquals("mail/a", m.getName());
        assertEquals("imaps", m.storeProtocol);
        assertEquals("a.B", m.storeProtocolClass);
        assertEquals("c.D", m.transportProtocolClass);
        assertEquals("f@example.org", m.from);
        assertEquals("desc", m.description);
    }

    @Test
    public void mailSessionInitFromAnnotation() {
        Map<String, Object> v = new HashMap<String, Object>();
        v.put("description", "ad");
        v.put("name", "mail/ann");
        v.put("storeProtocol", "imap");
        v.put("transportProtocol", "smtp");
        v.put("host", "ah");
        v.put("user", "au");
        v.put("password", "ap");
        v.put("from", "af@example.org");
        MailSessionDefinition def = AnnotationStubs.annotation(MailSessionDefinition.class, v);
        MailSession m = new MailSession();
        m.init(def);
        assertEquals("mail/ann", m.getName());
        assertEquals("ah", m.host);
        assertEquals("af@example.org", m.from);
        assertEquals("ap", m.password);
    }

    // JmsDestination

    @Test
    public void jmsDestinationInterfaceDerivedFromName() {
        JmsDestination q = new JmsDestination();
        q.setName("java:comp/env/queue/orders");
        assertEquals("javax.jms.Queue", q.getInterfaceName());
        JmsDestination t = new JmsDestination();
        t.setName("topic/news");
        assertEquals("javax.jms.Topic", t.getInterfaceName());
        JmsDestination other = new JmsDestination();
        other.setName("jms/other");
        try {
            other.getInterfaceName();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage() != null);
        }
        other.setInterfaceName("javax.jms.Queue");
        assertEquals("javax.jms.Queue", other.getInterfaceName());
    }

    @Test
    public void jmsDestinationInitAndNewInstance() throws Exception {
        JmsDestination d = new JmsDestination();
        d.setDescription("x");
        d.addProperty("a", "b");
        d.init(attrs(new String[][] {
            { "description", "dd" }, { "name", "jms/d" }, { "interface-name", "java.util.List" },
            { "class-name", "java.util.ArrayList" } }));
        assertEquals("jms/d", d.getName());
        assertEquals("java.util.ArrayList", d.getClassName());
        Object o = d.newInstance();
        assertTrue(o instanceof java.util.ArrayList);
        d.setClassName("java.lang.String");
        assertNull(d.newInstance());
        d.setClassName("no.such.Type");
        assertNull(d.newInstance());
        d.setClassName("java.util.ArrayList");
        d.setInterfaceName("no.such.Interface");
        assertNull(d.newInstance());
        d.init();
        d.close();
    }

    // JmsConnectionFactory

    @Test
    public void jmsConnectionFactoryAttributesAndDefaults() {
        JmsConnectionFactory f = new JmsConnectionFactory();
        f.init(attrs(new String[][] {
            { "description", "d" }, { "name", "jms/cf" }, { "class-name", "a.B" },
            { "user", "u" }, { "password", "p" }, { "client-id", "cid" } }));
        assertEquals("javax.jms.ConnectionFactory", f.getInterfaceName());
        assertEquals("jms/cf", f.getName());
        assertEquals("a.B", f.getClassName());
        assertEquals("cid", f.clientId);
        f.init(attrs(new String[][] { { "name", "jms/cf2" },
            { "interface-name", "javax.jms.QueueConnectionFactory" } }));
        assertEquals("javax.jms.QueueConnectionFactory", f.getInterfaceName());
    }

    @Test
    public void jmsConnectionFactorySetters() {
        JmsConnectionFactory f = new JmsConnectionFactory();
        f.setDescription("d");
        f.setName("n");
        f.setInterfaceName("i");
        f.setClassName("c");
        f.setUser("u");
        f.setPassword("p");
        f.setClientId("cid");
        f.setTransactional(false);
        f.setResourceAdapter("ra");
        f.addProperty("k", "v");
        JmsConnectionFactory.Pool pool = new JmsConnectionFactory.Pool();
        pool.setMaxPoolSize(5);
        pool.setMinPoolSize(1);
        pool.setConnectionTimeoutInSeconds(30);
        f.setPool(pool);
        assertEquals("n", f.getName());
        assertEquals("i", f.getInterfaceName());
        assertEquals("c", f.getClassName());
        assertEquals("ra", f.resourceAdapter);
        assertEquals("v", f.properties.get("k"));
        assertSame(pool, f.pool);
        assertEquals(5, pool.maxPoolSize);
        assertEquals(1, pool.minPoolSize);
        assertEquals(30, pool.connectionTimeoutInSeconds);
        assertEquals(false, f.transactional);
    }

    // ConnectionFactory / basic JCA

    @Test
    public void connectionFactoryProducesBasicJcaFactory() {
        ConnectionFactory cf = new ConnectionFactory();
        cf.init(attrs(new String[][] { { "jndi-name", "jca/x" }, { "connection-definition-id", "id" } }));
        cf.setJndiName("jca/x");
        cf.setConnectionDefinitionId("id");
        cf.addProperty("maxPoolSize", "7");
        cf.addProperty("initialPoolSize", "bad");
        assertEquals("jca/x", cf.getName());
        assertNull(cf.getClassName());
        assertNull(cf.getInterfaceName());
        Object o = cf.newInstance();
        assertTrue(o instanceof BasicJCAConnectionFactory);
        BasicJCAConnectionFactory f = (BasicJCAConnectionFactory) o;
        assertTrue(f.getAdapterName().endsWith("jca/x"));
        assertEquals("External System via jca/x", f.getEISProductName());
    }

    @Test
    public void basicJcaConnectionLifecycle() throws Exception {
        ConnectionFactory cf = new ConnectionFactory();
        cf.setJndiName("jca/y");
        cf.addProperty("maxPoolSize", "bad");
        cf.addProperty("initialPoolSize", "3");
        cf.addProperty("eisProductName", "Mainframe");
        BasicJCAConnectionFactory f = new BasicJCAConnectionFactory(cf);
        assertEquals("Mainframe", f.getEISProductName());
        BasicJCAConnection plain = f.getConnection();
        assertEquals(System.getProperty("user.name"), plain.getUserName());
        assertNull(plain.getProperty("anything"));
        Map<String, String> props = new HashMap<String, String>();
        props.put("username", "bob");
        props.put("opt", "val");
        BasicJCAConnection c = f.getConnection(props);
        assertEquals("bob", c.getUserName());
        assertEquals("val", c.getProperty("opt"));
        assertEquals("Mainframe", c.getEISProductName());
        Object r = c.execute("op", null);
        assertTrue(String.valueOf(r).contains("op"));
        c.close();
        c.close();
        try {
            c.execute("again", null);
            fail("expected closed connection failure");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("closed"));
        }
        Map<String, String> noUser = new HashMap<String, String>();
        BasicJCAConnection d = f.getConnection(noUser);
        assertEquals(System.getProperty("user.name"), d.getUserName());
        d.close();
        plain.close();
    }
}
