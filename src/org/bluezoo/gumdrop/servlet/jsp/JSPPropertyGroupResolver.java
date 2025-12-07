/*
 * JSPPropertyGroupResolver.java
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

import javax.servlet.descriptor.JspConfigDescriptor;
import javax.servlet.descriptor.JspPropertyGroupDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Resolves JSP property group configurations according to the JSP specification.
 * This class handles URL pattern matching and property merging when multiple
 * property groups apply to the same JSP page.
 * <p>
 * According to the JSP specification, property groups are processed in the order
 * they appear in the deployment descriptor, with later matches potentially
 * overriding earlier ones for certain properties.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSPPropertyGroupResolver {

    private static final Logger LOGGER = Logger.getLogger(JSPPropertyGroupResolver.class.getName());

    /**
     * Represents the resolved JSP configuration properties for a specific JSP page.
     * This class merges properties from all applicable JSP property groups.
     */
    public static class ResolvedJSPProperties {
        // Properties that can be overridden by later matches
        private String pageEncoding = "UTF-8"; // Default encoding
        private Boolean elIgnored = null;
        private Boolean scriptingInvalid = null;
        private Boolean isXml = null;
        private String defaultContentType = null;
        private String buffer = null;
        private Boolean trimDirectiveWhitespaces = null;
        private Boolean deferredSyntaxAllowedAsLiteral = null;
        private Boolean errorOnUndeclaredNamespace = null;

        // Cumulative properties (all matches are included)
        private final List<String> includePreludes = new ArrayList<>();
        private final List<String> includeCodas = new ArrayList<>();

        // Getters
        public String getPageEncoding() { return pageEncoding; }
        public Boolean getElIgnored() { return elIgnored; }
        public Boolean getScriptingInvalid() { return scriptingInvalid; }
        public Boolean getIsXml() { return isXml; }
        public String getDefaultContentType() { return defaultContentType; }
        public String getBuffer() { return buffer; }
        public Boolean getTrimDirectiveWhitespaces() { return trimDirectiveWhitespaces; }
        public Boolean getDeferredSyntaxAllowedAsLiteral() { return deferredSyntaxAllowedAsLiteral; }
        public Boolean getErrorOnUndeclaredNamespace() { return errorOnUndeclaredNamespace; }
        public List<String> getIncludePreludes() { return Collections.unmodifiableList(includePreludes); }
        public List<String> getIncludeCodas() { return Collections.unmodifiableList(includeCodas); }

        // Package-private setters for the resolver
        void setPageEncoding(String pageEncoding) { this.pageEncoding = pageEncoding; }
        void setElIgnored(Boolean elIgnored) { this.elIgnored = elIgnored; }
        void setScriptingInvalid(Boolean scriptingInvalid) { this.scriptingInvalid = scriptingInvalid; }
        void setIsXml(Boolean isXml) { this.isXml = isXml; }
        void setDefaultContentType(String defaultContentType) { this.defaultContentType = defaultContentType; }
        void setBuffer(String buffer) { this.buffer = buffer; }
        void setTrimDirectiveWhitespaces(Boolean trimDirectiveWhitespaces) { this.trimDirectiveWhitespaces = trimDirectiveWhitespaces; }
        void setDeferredSyntaxAllowedAsLiteral(Boolean deferredSyntaxAllowedAsLiteral) { this.deferredSyntaxAllowedAsLiteral = deferredSyntaxAllowedAsLiteral; }
        void setErrorOnUndeclaredNamespace(Boolean errorOnUndeclaredNamespace) { this.errorOnUndeclaredNamespace = errorOnUndeclaredNamespace; }
        void addIncludePrelude(String prelude) { if (prelude != null) includePreludes.add(prelude); }
        void addIncludeCoda(String coda) { if (coda != null) includeCodas.add(coda); }

        @Override
        public String toString() {
            return "ResolvedJSPProperties{" +
                    "pageEncoding='" + pageEncoding + '\'' +
                    ", elIgnored=" + elIgnored +
                    ", scriptingInvalid=" + scriptingInvalid +
                    ", isXml=" + isXml +
                    ", defaultContentType='" + defaultContentType + '\'' +
                    ", buffer='" + buffer + '\'' +
                    ", trimDirectiveWhitespaces=" + trimDirectiveWhitespaces +
                    ", deferredSyntaxAllowedAsLiteral=" + deferredSyntaxAllowedAsLiteral +
                    ", errorOnUndeclaredNamespace=" + errorOnUndeclaredNamespace +
                    ", includePreludes=" + includePreludes.size() +
                    ", includeCodas=" + includeCodas.size() +
                    '}';
        }
    }

    /**
     * Resolves JSP properties for the given JSP file path using the provided JSP configuration.
     *
     * @param jspPath the path of the JSP file (relative to the web application root)
     * @param jspConfig the JSP configuration from the deployment descriptor
     * @return the resolved JSP properties, or a default configuration if no matches
     */
    public static ResolvedJSPProperties resolve(String jspPath, JspConfigDescriptor jspConfig) {
        ResolvedJSPProperties resolved = new ResolvedJSPProperties();

        if (jspConfig == null || jspPath == null) {
            LOGGER.fine("No JSP configuration or path provided, using defaults");
            return resolved;
        }

        Collection<JspPropertyGroupDescriptor> propertyGroups = jspConfig.getJspPropertyGroups();
        if (propertyGroups == null || propertyGroups.isEmpty()) {
            LOGGER.fine("No JSP property groups configured, using defaults");
            return resolved;
        }

        // Process property groups in order (as they appear in web.xml)
        int matchCount = 0;
        for (JspPropertyGroupDescriptor propertyGroup : propertyGroups) {
            if (matchesPropertyGroup(jspPath, propertyGroup)) {
                applyPropertyGroup(resolved, propertyGroup);
                matchCount++;
                LOGGER.fine("Applied JSP property group to '" + jspPath + "' (match " + matchCount + ")");
            }
        }

        if (matchCount == 0) {
            LOGGER.fine("No JSP property groups matched '" + jspPath + "', using defaults");
        } else {
            LOGGER.fine("Resolved JSP properties for '" + jspPath + "': " + resolved);
        }

        return resolved;
    }

    /**
     * Checks if the JSP path matches any URL patterns in the property group.
     *
     * @param jspPath the JSP file path
     * @param propertyGroup the property group to check
     * @return true if the path matches any pattern in the property group
     */
    private static boolean matchesPropertyGroup(String jspPath, JspPropertyGroupDescriptor propertyGroup) {
        Collection<String> urlPatterns = propertyGroup.getUrlPatterns();
        if (urlPatterns == null || urlPatterns.isEmpty()) {
            return false;
        }

        for (String pattern : urlPatterns) {
            if (matchesUrlPattern(jspPath, pattern)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if a JSP file path matches a specific URL pattern.
     * Supports exact matches, prefix matches (ending with /*), 
     * and extension matches (starting with *.).
     *
     * @param jspPath the JSP file path
     * @param pattern the URL pattern to match against
     * @return true if the path matches the pattern, false otherwise
     */
    private static boolean matchesUrlPattern(String jspPath, String pattern) {
        if (pattern == null || jspPath == null) {
            return false;
        }

        // Exact match
        if (pattern.equals(jspPath)) {
            return true;
        }

        // Extension match (e.g., "*.jsp")
        if (pattern.startsWith("*.")) {
            String extension = pattern.substring(1); // Remove the "*"
            return jspPath.endsWith(extension);
        }

        // Prefix match (e.g., "/admin/*")
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2); // Remove the "/*"
            return jspPath.startsWith(prefix + "/") || jspPath.equals(prefix);
        }

        // Default case - no match
        return false;
    }

    /**
     * Applies the properties from a property group to the resolved configuration.
     * Later matches can override certain properties from earlier matches.
     *
     * @param resolved the resolved properties to update
     * @param propertyGroup the property group to apply
     */
    private static void applyPropertyGroup(ResolvedJSPProperties resolved, JspPropertyGroupDescriptor propertyGroup) {
        // Override properties (later matches win)
        if (propertyGroup.getPageEncoding() != null) {
            resolved.setPageEncoding(propertyGroup.getPageEncoding());
        }

        if (propertyGroup.getElIgnored() != null) {
            resolved.setElIgnored(Boolean.valueOf(propertyGroup.getElIgnored()));
        }

        if (propertyGroup.getScriptingInvalid() != null) {
            resolved.setScriptingInvalid(Boolean.valueOf(propertyGroup.getScriptingInvalid()));
        }

        if (propertyGroup.getIsXml() != null) {
            resolved.setIsXml(Boolean.valueOf(propertyGroup.getIsXml()));
        }

        if (propertyGroup.getDefaultContentType() != null) {
            resolved.setDefaultContentType(propertyGroup.getDefaultContentType());
        }

        if (propertyGroup.getBuffer() != null) {
            resolved.setBuffer(propertyGroup.getBuffer());
        }

        if (propertyGroup.getTrimDirectiveWhitespaces() != null) {
            resolved.setTrimDirectiveWhitespaces(Boolean.valueOf(propertyGroup.getTrimDirectiveWhitespaces()));
        }

        if (propertyGroup.getDeferredSyntaxAllowedAsLiteral() != null) {
            resolved.setDeferredSyntaxAllowedAsLiteral(Boolean.valueOf(propertyGroup.getDeferredSyntaxAllowedAsLiteral()));
        }

        if (propertyGroup.getErrorOnUndeclaredNamespace() != null) {
            resolved.setErrorOnUndeclaredNamespace(Boolean.valueOf(propertyGroup.getErrorOnUndeclaredNamespace()));
        }

        // Cumulative properties (all matches are included)
        Collection<String> includePreludes = propertyGroup.getIncludePreludes();
        if (includePreludes != null) {
            for (String prelude : includePreludes) {
                resolved.addIncludePrelude(prelude);
            }
        }

        Collection<String> includeCodas = propertyGroup.getIncludeCodas();
        if (includeCodas != null) {
            for (String coda : includeCodas) {
                resolved.addIncludeCoda(coda);
            }
        }
    }
}
