/*
 * JspPropertyGroup.java
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

package org.bluezoo.gumdrop.servlet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.servlet.descriptor.JspPropertyGroupDescriptor;

/**
 * A <code>jsp-property-group</code> deployment descriptor definition.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JspPropertyGroup implements JspPropertyGroupDescriptor {

    String description;
    List<String> urlPatterns = new ArrayList<>();
    Boolean elIgnored;
    String pageEncoding;
    Boolean scriptingInvalid;
    List<String> includePrelude = new ArrayList<>();
    List<String> includeCoda = new ArrayList<>();
    String defaultContentType;
    Long buffer;
    Boolean trimDirectiveWhitespaces;
    Boolean isXml;
    Boolean deferredSyntaxAllowedAsLiteral;
    Boolean errorOnUndeclaredNamespace;

    // -- JspPropertyGroupDescriptor --

    @Override public Collection<String> getUrlPatterns() {
        return Collections.unmodifiableList(urlPatterns);
    }

    @Override public String getElIgnored() {
        return (elIgnored == null) ? null : elIgnored.toString();
    }

    @Override public String getPageEncoding() {
        return pageEncoding;
    }

    @Override public String getScriptingInvalid() {
        return (scriptingInvalid == null) ? null : scriptingInvalid.toString();
    }

    @Override public String getIsXml() {
        return (isXml == null) ? null : isXml.toString();
    }

    @Override public Collection<String> getIncludePreludes() {
        return Collections.unmodifiableList(includePrelude);
    }

    @Override public Collection<String> getIncludeCodas() {
        return Collections.unmodifiableList(includeCoda);
    }

    @Override public String getDeferredSyntaxAllowedAsLiteral() {
        return (deferredSyntaxAllowedAsLiteral ==  null) ? null : deferredSyntaxAllowedAsLiteral.toString();
    }

    @Override public String getTrimDirectiveWhitespaces() {
        return (trimDirectiveWhitespaces == null) ? null : trimDirectiveWhitespaces.toString();
    }

    @Override public String getDefaultContentType() {
        return defaultContentType;
    }

    @Override public String getBuffer() {
        return (buffer == null) ? null : buffer.toString();
    }

    @Override public String getErrorOnUndeclaredNamespace() {
        return (errorOnUndeclaredNamespace == null) ? null : errorOnUndeclaredNamespace.toString();
    }

}
