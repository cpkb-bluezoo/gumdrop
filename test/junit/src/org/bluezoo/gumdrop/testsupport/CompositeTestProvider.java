/*
 * CompositeTestProvider.java
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

package org.bluezoo.gumdrop.testsupport;

import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A JCA provider that offers, under one name, every service of the JDK's
 * own providers, by delegating each to the provider that implements it.
 * No single JDK provider offers everything a TLS stack needs (digests and
 * certificates come from SUN, ciphers and HKDF from SunJCE, elliptic
 * curves from SunEC, trust managers from SunJSSE), so this stands in for
 * the single validated module an operator would name in FIPS mode. It
 * also counts the services handed out, so a test can show that a runtime
 * pinned to it really did draw its cryptography from here.
 *
 * <p>Registered with {@link Security} on first use, under
 * {@link #NAME}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class CompositeTestProvider extends Provider {

    /** The name a test names this provider by. */
    public static final String NAME = "GumdropTest";

    private static final String[] DELEGATES = { "SUN", "SunRsaSign", "SunEC", "SunJCE", "SunJSSE" };

    private static CompositeTestProvider installed;

    private final Map<String, Integer> handedOut = new HashMap<String, Integer>();

    private CompositeTestProvider() {
        super(NAME, "1.0", "Composite of the JDK providers, for tests");
        for (int i = 0; i < DELEGATES.length; i++) {
            Provider delegate = Security.getProvider(DELEGATES[i]);
            if (delegate == null) {
                continue;
            }
            Map<String, List<String>> aliases = aliasesOf(delegate);
            for (Service service : delegate.getServices()) {
                if (getService(service.getType(), service.getAlgorithm()) != null) {
                    continue;
                }
                putService(new DelegatingService(this, service,
                        aliases.get(service.getType() + "." + service.getAlgorithm().toUpperCase())));
            }
        }
    }

    /**
     * Collects a provider's algorithm aliases, keyed by
     * {@code TYPE.ALGORITHM} in upper case. {@link Service} does not expose
     * its aliases, but a provider still publishes them as
     * {@code Alg.Alias.<type>.<alias>} properties.
     */
    private static Map<String, List<String>> aliasesOf(Provider delegate) {
        Map<String, List<String>> aliases = new HashMap<String, List<String>>();
        for (Object keyObject : delegate.keySet()) {
            String key = String.valueOf(keyObject);
            if (!key.startsWith("Alg.Alias.")) {
                continue;
            }
            String rest = key.substring("Alg.Alias.".length());
            int dot = rest.indexOf('.');
            if (dot < 0) {
                continue;
            }
            String type = rest.substring(0, dot);
            String alias = rest.substring(dot + 1);
            String algorithm = String.valueOf(delegate.get(key));
            String lookup = type + "." + algorithm.toUpperCase();
            List<String> list = aliases.get(lookup);
            if (list == null) {
                list = new ArrayList<String>();
                aliases.put(lookup, list);
            }
            list.add(alias);
        }
        return aliases;
    }

    /**
     * Returns the installed instance, registering it with {@link Security}
     * the first time.
     *
     * @return the provider
     */
    public static synchronized CompositeTestProvider install() {
        if (installed == null) {
            installed = new CompositeTestProvider();
            Security.addProvider(installed);
        }
        return installed;
    }

    /**
     * Returns how many service objects of a type this provider has handed
     * out, such as {@code "Cipher"} or {@code "SecureRandom"}.
     *
     * @param type the JCA service type
     * @return the count
     */
    public synchronized int handedOut(String type) {
        Integer count = handedOut.get(type);
        return count == null ? 0 : count.intValue();
    }

    /** Returns the service types handed out so far, for diagnostics. */
    public synchronized List<String> handedOutTypes() {
        return new ArrayList<String>(handedOut.keySet());
    }

    private synchronized void count(String type) {
        Integer count = handedOut.get(type);
        handedOut.put(type, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
    }

    private static final class DelegatingService extends Service {

        private final Service delegate;
        private final CompositeTestProvider owner;

        DelegatingService(CompositeTestProvider owner, Service delegate, List<String> aliases) {
            super(owner, delegate.getType(), delegate.getAlgorithm(), delegate.getClassName(), aliases, null);
            this.owner = owner;
            this.delegate = delegate;
        }

        @Override
        public Object newInstance(Object constructorParameter) throws NoSuchAlgorithmException {
            owner.count(getType());
            return delegate.newInstance(constructorParameter);
        }

        @Override
        public boolean supportsParameter(Object parameter) {
            return delegate.supportsParameter(parameter);
        }
    }
}
