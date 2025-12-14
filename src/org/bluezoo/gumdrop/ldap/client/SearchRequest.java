/*
 * SearchRequest.java
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

package org.bluezoo.gumdrop.ldap.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Represents an LDAP search request.
 *
 * <h4>Usage Example</h4>
 * <pre>{@code
 * SearchRequest search = new SearchRequest();
 * search.setBaseDN("dc=example,dc=com");
 * search.setScope(SearchScope.SUBTREE);
 * search.setFilter("(&(objectClass=person)(uid=jdoe))");
 * search.setAttributes("cn", "mail", "memberOf");
 * search.setSizeLimit(100);
 * search.setTimeLimit(30);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SearchRequest {

    private String baseDN = "";
    private SearchScope scope = SearchScope.SUBTREE;
    private DerefAliases derefAliases = DerefAliases.NEVER;
    private int sizeLimit = 0;  // 0 = no limit
    private int timeLimit = 0;  // 0 = no limit
    private boolean typesOnly = false;
    private String filter = "(objectClass=*)";
    private List<String> attributes = Collections.emptyList();

    /**
     * Creates a new search request with default values.
     */
    public SearchRequest() {
    }

    /**
     * Returns the base DN for the search.
     *
     * @return the base DN
     */
    public String getBaseDN() {
        return baseDN;
    }

    /**
     * Sets the base DN for the search.
     *
     * @param baseDN the base DN
     */
    public void setBaseDN(String baseDN) {
        this.baseDN = baseDN != null ? baseDN : "";
    }

    /**
     * Returns the search scope.
     *
     * @return the scope
     */
    public SearchScope getScope() {
        return scope;
    }

    /**
     * Sets the search scope.
     *
     * @param scope the scope
     */
    public void setScope(SearchScope scope) {
        this.scope = scope != null ? scope : SearchScope.SUBTREE;
    }

    /**
     * Returns the alias dereferencing policy.
     *
     * @return the deref policy
     */
    public DerefAliases getDerefAliases() {
        return derefAliases;
    }

    /**
     * Sets the alias dereferencing policy.
     *
     * @param derefAliases the deref policy
     */
    public void setDerefAliases(DerefAliases derefAliases) {
        this.derefAliases = derefAliases != null ? derefAliases : DerefAliases.NEVER;
    }

    /**
     * Returns the maximum number of entries to return.
     *
     * @return the size limit (0 = no limit)
     */
    public int getSizeLimit() {
        return sizeLimit;
    }

    /**
     * Sets the maximum number of entries to return.
     *
     * @param sizeLimit the size limit (0 = no limit)
     */
    public void setSizeLimit(int sizeLimit) {
        this.sizeLimit = Math.max(0, sizeLimit);
    }

    /**
     * Returns the time limit in seconds.
     *
     * @return the time limit (0 = no limit)
     */
    public int getTimeLimit() {
        return timeLimit;
    }

    /**
     * Sets the time limit in seconds.
     *
     * @param timeLimit the time limit (0 = no limit)
     */
    public void setTimeLimit(int timeLimit) {
        this.timeLimit = Math.max(0, timeLimit);
    }

    /**
     * Returns whether to return only attribute types (not values).
     *
     * @return true if types only
     */
    public boolean isTypesOnly() {
        return typesOnly;
    }

    /**
     * Sets whether to return only attribute types (not values).
     *
     * @param typesOnly true for types only
     */
    public void setTypesOnly(boolean typesOnly) {
        this.typesOnly = typesOnly;
    }

    /**
     * Returns the search filter.
     *
     * @return the filter string
     */
    public String getFilter() {
        return filter;
    }

    /**
     * Sets the search filter.
     *
     * <p>The filter should use standard LDAP filter syntax (RFC 4515).
     * Examples:</p>
     * <ul>
     *   <li>{@code (uid=jdoe)} - equality</li>
     *   <li>{@code (cn=John*)} - substring</li>
     *   <li>{@code (&(objectClass=person)(uid=jdoe))} - AND</li>
     *   <li>{@code (|(uid=jdoe)(uid=jsmith))} - OR</li>
     *   <li>{@code (!(objectClass=computer))} - NOT</li>
     * </ul>
     *
     * @param filter the filter string
     */
    public void setFilter(String filter) {
        this.filter = filter != null ? filter : "(objectClass=*)";
    }

    /**
     * Returns the list of attributes to return.
     *
     * @return unmodifiable list of attribute names
     */
    public List<String> getAttributes() {
        return attributes;
    }

    /**
     * Sets the attributes to return.
     *
     * <p>An empty list returns all user attributes. The special value
     * "*" returns all user attributes, "+" returns all operational
     * attributes.</p>
     *
     * @param attributes the attribute names
     */
    public void setAttributes(String... attributes) {
        if (attributes == null || attributes.length == 0) {
            this.attributes = Collections.emptyList();
        } else {
            this.attributes = Collections.unmodifiableList(
                    new ArrayList<String>(Arrays.asList(attributes)));
        }
    }

    /**
     * Sets the attributes to return.
     *
     * @param attributes the attribute names
     */
    public void setAttributes(List<String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            this.attributes = Collections.emptyList();
        } else {
            this.attributes = Collections.unmodifiableList(
                    new ArrayList<String>(attributes));
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SearchRequest[base=").append(baseDN);
        sb.append(", scope=").append(scope);
        sb.append(", filter=").append(filter);
        if (!attributes.isEmpty()) {
            sb.append(", attrs=").append(attributes);
        }
        if (sizeLimit > 0) {
            sb.append(", sizeLimit=").append(sizeLimit);
        }
        if (timeLimit > 0) {
            sb.append(", timeLimit=").append(timeLimit);
        }
        sb.append("]");
        return sb.toString();
    }
}

