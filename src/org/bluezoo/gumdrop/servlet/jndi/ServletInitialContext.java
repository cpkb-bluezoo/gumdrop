/*
 * ServletInitialContext.java
 * Copyright (C) 2005 Chris Burdess
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

import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import javax.naming.Binding;
import javax.naming.CompoundName;
import javax.naming.Context;
import javax.naming.InvalidNameException;
import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.OperationNotSupportedException;

/**
 * Namespace context for the web application.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletInitialContext implements Context, NameParser {

    static final Properties syntax = new Properties();

    static {
        syntax.put("jndi.syntax.direction", "flat");
    }

    final Hashtable env;
    final Map bindings;

    public ServletInitialContext(Hashtable env) {
        this.env = (Hashtable) env.clone();
        bindings = new LinkedHashMap();
    }

    public Object lookup(Name name) throws NamingException {
        if (name.size() != 1) {
            throw new NameNotFoundException();
        }
        return lookup(name.get(0));
    }

    public Object lookup(String name) throws NamingException {
        if ("".equals(name)) {
            return this;
        }
        if (name == null || !name.startsWith("java:comp/env/")) {
            throw new NameNotFoundException();
        }
        Binding binding = (Binding) bindings.get(name);
        if (binding == null) {
            throw new NameNotFoundException(name);
        }
        return binding.getObject();
    }

    public void bind(Name name, Object obj) throws NamingException {
        if (name.size() != 1) {
            throw new NameNotFoundException();
        }
        bind(name.get(0), obj);
    }

    public void bind(String name, Object obj) throws NamingException {
        if (name == null || !name.startsWith("java:comp/env/")) {
            throw new InvalidNameException(name);
        }
        Binding binding = new Binding(name, obj);
        bindings.put(name, binding);
    }

    public void bind(String name, String className, Object obj) {
        Binding binding = new Binding(name, className, obj);
        bindings.put(name, binding);
    }

    public void rebind(Name name, Object obj) throws NamingException {
        bind(name, obj);
    }

    public void rebind(String name, Object obj) throws NamingException {
        bind(name, obj);
    }

    public void unbind(Name name) throws NamingException {
        if (name.size() != 1) {
            throw new NameNotFoundException();
        }
        unbind(name.get(0));
    }

    public void unbind(String name) throws NamingException {
        if (name == null || !name.startsWith("java:comp/env/")) {
            throw new InvalidNameException(name);
        }
        if (!bindings.containsKey(name)) {
            throw new NameNotFoundException(name);
        }
        bindings.remove(name);
    }

    public void rename(Name oldName, Name newName) throws NamingException {
        if (oldName.size() != 1 || newName.size() != 1) {
            throw new InvalidNameException();
        }
        rename(oldName.get(0), newName.get(0));
    }

    public void rename(String oldName, String newName) throws NamingException {
        if (oldName == null
                || !oldName.startsWith("java:comp/env/")
                || newName == null
                || !newName.startsWith("java:comp/env/")) {
            throw new InvalidNameException();
        }
        Binding binding = (Binding) bindings.remove(oldName);
        if (binding == null) {
            throw new NameNotFoundException(oldName);
        }
        binding = new Binding(newName, binding.getClassName(), binding.getObject());
        bindings.put(newName, binding);
    }

    public NamingEnumeration list(Name name) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public NamingEnumeration list(String name) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public NamingEnumeration listBindings(Name name) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public NamingEnumeration listBindings(String name) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public void destroySubcontext(Name name) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public void destroySubcontext(String name) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public Context createSubcontext(Name name) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public Context createSubcontext(String name) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public Object lookupLink(Name name) throws NamingException {
        return lookup(name);
    }

    public Object lookupLink(String name) throws NamingException {
        return lookup(name);
    }

    public NameParser getNameParser(Name name) throws NamingException {
        return this;
    }

    public NameParser getNameParser(String name) throws NamingException {
        return this;
    }

    public Name composeName(Name name, Name prefix) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public String composeName(String name, String prefix) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public Object addToEnvironment(String name, Object val) throws NamingException {
        return env.put(name, val);
    }

    public Object removeFromEnvironment(String name) throws NamingException {
        return env.remove(name);
    }

    public Hashtable getEnvironment() throws NamingException {
        return (Hashtable) env.clone();
    }

    public void close() throws NamingException {}

    public String getNameInNamespace() throws NamingException {
        throw new OperationNotSupportedException();
    }

    // -- NameParser --

    public Name parse(String name) throws NamingException {
        return new CompoundName(name, syntax);
    }

}

