/*
 * ResourceDef.java
 * Copyright (C) 2005 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.ConfigurationParser;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

/**
 * A resource definition.
 * This contains all the configuration information required to access the
 * resource.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ResourceDef {

    /**
     * The name specified in the deployment descriptor.
     */
    final String name;

    /**
     * The type (class name) of the resource.
     */
    final String type;

    /**
     * The properties set for the resource.
     */
    private final Properties props = new Properties();

    public ResourceDef(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public String getProperty(String name) {
        return props.getProperty(name);
    }

    public void setProperty(String name, String value) {
        props.put(name, value);
    }

    public Object newInstance() {
        try {
            if ("javax.sql.DataSource".equals(type)) {
                // JDBC driver
                String driverClassName = props.getProperty("driver");
                String url = props.getProperty("url");
                Class t = Class.forName(driverClassName);
                Driver driver = (Driver) t.newInstance();
                DriverManager.registerDriver(driver);
                return new ServletDataSource(url, props);
            } else if ("javax.mail.Session".equals(type)) {
                // JavaMail session
                Class t = Class.forName(type);
                Class[] pt = new Class[] {Properties.class};
                Method m = t.getMethod("getDefaultInstance", pt);
                return m.invoke(null, new Object[] {props});
            } else {
                // Generic object
                Class t = Class.forName(type);
                String defaultValue = (String) props.get("");
                Object resource;
                if (defaultValue == null) {
                    resource = t.newInstance();
                } else {
                    Class[] ct = new Class[] {String.class};
                    Constructor c = t.getConstructor(ct);
                    Object[] args = new Object[] {defaultValue};
                    resource = c.newInstance(args);
                }
                Method[] methods = t.getMethods();
                // Configure
                for (Iterator i = props.entrySet().iterator(); i.hasNext(); ) {
                    Map.Entry entry = (Map.Entry) i.next();
                    String propName = (String) entry.getKey();
                    if (propName == null || "".equals(propName)) {
                        continue; // default value
                    }
                    String propValue = (String) entry.getValue();

                    // Find a matching method and invoke it
                    ConfigurationParser.setValue(resource, propName, propValue, methods);
                }
                return resource;
            }
        } catch (Exception e) {
            RuntimeException e2 = new RuntimeException();
            e2.initCause(e);
            throw e2;
        }
    }

}
