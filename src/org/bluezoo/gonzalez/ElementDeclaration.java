/*
 * ElementDeclaration.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents an element declaration from the DTD.
 *
 * <p>Element declarations define the content model for an element, specifying
 * what child elements and character data are allowed.
 *
 * <p>Content models can be:
 * <ul>
 *   <li>EMPTY - no content allowed</li>
 *   <li>ANY - any content allowed</li>
 *   <li>Mixed content - (#PCDATA | child1 | child2)*</li>
 *   <li>Element content - structured content model with sequences and choices</li>
 * </ul>
 *
 * <p>This class stores the content model as a parsed structure for efficient
 * validation. For simple cases (EMPTY, ANY), only the type is stored.
 * For complex content models, a tree structure represents the model.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
class ElementDeclaration {

    /**
     * Content model types.
     */
    enum ContentType {
        EMPTY,      // <!ELEMENT name EMPTY>
        ANY,        // <!ELEMENT name ANY>
        MIXED,      // <!ELEMENT name (#PCDATA|child)*>
        ELEMENT     // <!ELEMENT name (child1, child2)>
    }

    /**
     * The element name.
     */
    String name;

    /**
     * The content type.
     */
    ContentType contentType;

    /**
     * The content model root node (null for EMPTY and ANY).
     * For MIXED and ELEMENT content, this is the root of the content model tree.
     */
    ContentModel contentModel;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!ELEMENT ").append(name).append(" ");
        switch (contentType) {
            case EMPTY:
                sb.append("EMPTY");
                break;
            case ANY:
                sb.append("ANY");
                break;
            case MIXED:
            case ELEMENT:
                sb.append(contentModel);
                break;
        }
        sb.append(">");
        return sb.toString();
    }

    /**
     * Represents a node in the content model tree.
     *
     * <p>Content models are represented as trees where:
     * <ul>
     *   <li>Leaf nodes represent element names or #PCDATA</li>
     *   <li>Internal nodes represent sequences (,), choices (|), or groups</li>
     *   <li>Occurrence indicators (?, *, +) are properties of nodes</li>
     * </ul>
     */
    static class ContentModel {

        /**
         * Node types in the content model.
         */
        public enum NodeType {
            PCDATA,     // #PCDATA
            ELEMENT,    // Element name
            SEQUENCE,   // (a, b, c)
            CHOICE      // (a | b | c)
        }

        /**
         * Occurrence indicators.
         */
        public enum Occurrence {
            ONCE,       // No indicator
            OPTIONAL,   // ?
            ZERO_OR_MORE, // *
            ONE_OR_MORE  // +
        }

        public NodeType type;
        public String elementName;  // For ELEMENT type
        public Occurrence occurrence;
        public List<ContentModel> children;

        /**
         * Default constructor for building incrementally.
         */
        public ContentModel() {
            this.occurrence = Occurrence.ONCE;
            this.children = new ArrayList<>();
        }

        /**
         * Creates a leaf node (#PCDATA or element name).
         * @param type the node type (PCDATA or ELEMENT)
         * @param elementName the element name for ELEMENT nodes, null for PCDATA
         * @param occurrence the occurrence indicator
         */
        public ContentModel(NodeType type, String elementName, Occurrence occurrence) {
            this.type = type;
            this.elementName = elementName;
            this.children = null;
            this.occurrence = occurrence;
        }

        /**
         * Creates an internal node (sequence or choice).
         * @param type the node type (SEQUENCE or CHOICE)
         * @param children the child content models
         * @param occurrence the occurrence indicator
         */
        public ContentModel(NodeType type, List<ContentModel> children, Occurrence occurrence) {
            this.type = type;
            this.elementName = null;
            this.children = children;
            this.occurrence = occurrence;
        }
        
        /**
         * Adds a child node during building (for SEQUENCE and CHOICE nodes).
         * @param child the child content model to add
         */
        public void addChild(ContentModel child) {
            if (children == null) {
                children = new ArrayList<>();
            }
            children.add(child);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            switch (type) {
                case PCDATA:
                    sb.append("#PCDATA");
                    break;
                case ELEMENT:
                    sb.append(elementName);
                    break;
                case SEQUENCE:
                case CHOICE:
                    sb.append("(");
                    if (children != null) {
                        for (int i = 0; i < children.size(); i++) {
                            if (i > 0) {
                                sb.append(type == NodeType.SEQUENCE ? ", " : " | ");
                            }
                            sb.append(children.get(i));
                        }
                    }
                    sb.append(")");
                    break;
            }
            // Add occurrence indicator
            switch (occurrence) {
                case OPTIONAL: sb.append("?"); break;
                case ZERO_OR_MORE: sb.append("*"); break;
                case ONE_OR_MORE: sb.append("+"); break;
                default: break;
            }
            return sb.toString();
        }
    }
}

