/*
 * JndiRefAndAdministeredTest.java
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

package org.bluezoo.gumdrop.servlet.jndi;

import org.junit.Test;

import javax.annotation.Resource;
import javax.naming.NamingException;
import java.util.Hashtable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Coverage for JNDI reference holders and basic JCA helpers.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JndiRefAndAdministeredTest {

    @Test
    public void testResourceRefLookupName() {
        ResourceRef ref = new ResourceRef();
        ref.setName("jdbc/DS");
        ref.setLookupName("java:comp/env/jdbc/DS");
        assertEquals("jdbc/DS", ref.getName());
        assertEquals("java:comp/env/jdbc/DS", ref.getLookupName());
    }

    @Test
    public void testEjbRefAndServiceRef() {
        EjbRef ejb = new EjbRef(true);
        ejb.setName("ejb/Order");
        ejb.setClassName("Session");
        ejb.setHome("com.example.OrderHome");
        ejb.setRemoteOrLocal("com.example.OrderRemote");
        assertEquals("ejb/Order", ejb.getName());

        ServiceRef svc = new ServiceRef();
        svc.setName("service/Billing");
        svc.setServiceInterface("com.example.BillingService");
        svc.setWsdlFile("WEB-INF/wsdl/billing.wsdl");
        assertEquals("service/Billing", svc.getName());
        svc.setLookupName("java:comp/env/service/Billing");
        assertEquals("java:comp/env/service/Billing", svc.getLookupName());
    }

    @Test
    public void testPersistenceAndEnvRefs() {
        PersistenceContextRef pc = new PersistenceContextRef();
        pc.setName("em/orders");
        pc.setUnitName("ordersPU");
        assertEquals("em/orders", pc.getName());

        PersistenceUnitRef pu = new PersistenceUnitRef();
        pu.setName("pu/orders");
        pu.setUnitName("ordersPU");
        assertEquals("pu/orders", pu.getName());
        assertEquals("java:comp/env/ordersPU", pu.getDefaultName());

        ResourceEnvRef envRef = new ResourceEnvRef();
        envRef.setName("env/maxThreads");
        envRef.setLookupName("java:comp/env/env/maxThreads");
        assertEquals("java:comp/env/env/maxThreads", envRef.getLookupName());

        MessageDestinationRef dest = new MessageDestinationRef();
        dest.setName("jms/OrderQueue");
        dest.setMessageDestinationUsage("Produces");
        dest.setLookupName("java:comp/env/jms/OrderQueue");
        assertEquals("java:comp/env/jms/OrderQueue", dest.getLookupName());
    }

    @Test
    public void testBasicJcaConnectionFactoryMetadata() {
        ConnectionFactory config = new ConnectionFactory();
        config.jndiName = "jca/ERP";
        config.properties.put("maxPoolSize", "5");
        config.properties.put("initialPoolSize", "2");
        config.properties.put("eisProductName", "Example ERP");

        BasicJCAConnectionFactory factory = new BasicJCAConnectionFactory(config);
        assertEquals("Gumdrop Basic JCA Adapter for jca/ERP", factory.getAdapterName());
        assertEquals("Example ERP", factory.getEISProductName());
        assertSame(config, factory.getConfig());

        BasicJCAConnection connection = factory.getConnection();
        assertEquals("Example ERP", connection.getEISProductName());
        connection.close();
    }

    @Test
    public void testResourceRefResolveViaLookupName() throws NamingException {
        ServletInitialContext ctx = new ServletInitialContext(new Hashtable<String, String>());
        ctx.bind("java:comp/env/jdbc/Primary", "ds-handle");

        ResourceRef ref = new ResourceRef();
        ref.setLookupName("java:comp/env/jdbc/Primary");
        assertEquals("ds-handle", ref.resolve(ctx));
    }
}
