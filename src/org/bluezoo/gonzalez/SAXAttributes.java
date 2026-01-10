/*
 * SAXAttributes.java
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.xml.sax.Attributes;
import org.xml.sax.ext.Attributes2;

/**
 * Extended attributes implementation that provides SAX2 extension information.
 *
 * <p>This class implements {@link Attributes2}, providing information about
 * whether attributes were specified in the document or came from DTD defaults,
 * and whether they were declared in the DTD.
 *
 * <p>The implementation is namespace-aware first and optimized for lookup
 * performance using multiple indexing strategies:
 * <ul>
 *   <li>Direct lookup by {@link QName} (namespace URI + local name)</li>
 *   <li>Lookup by qualified name string (for non-namespace-aware code)</li>
 *   <li>Sequential access by index</li>
 * </ul>
 *
 * <p>DTD information is retrieved lazily - {@link #isDeclared} queries the
 * DTD parser only when called, avoiding unnecessary overhead.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
class SAXAttributes implements Attributes2 {

    /**
     * Interface for normalizing attribute values on demand.
     */
    interface AttributeValueNormalizer {

        /**
         * Normalizes an attribute value.
         * 
         * @param rawValue the raw StringBuilder value
         * @param elementName the element name
         * @param attributeName the attribute name
         * @return the normalized String value
         */
        String normalize(StringBuilder rawValue, String elementName, String attributeName);

    }

    /**
     * Callback interface for recycling StringBuilder objects.
     */
    interface StringBuilderRecycler {

        void recycle(StringBuilder sb);

    }
    
    /**
     * Single attribute holder.
     * Stores either a materialized String value or a StringBuilder (to be normalized on demand).
     * 
     * Made mutable for object pooling - attributes are reused across startElement calls.
     */
    private static class Attribute {

        QName qname;
        String type;
        Object value;  // String or StringBuilder
        boolean specified;
        String elementName;  // For lazy normalization
        String attributeName;  // For lazy normalization

        Attribute() {
            // Default constructor for pooling
        }

        /**
         * Updates this attribute with new values (for pooling/reuse).
         */
        void update(QName qname, String type, Object value, boolean specified, 
                String elementName, String attributeName) {
            this.qname = qname;
            this.type = type;
            this.value = value;
            this.specified = specified;
            this.elementName = elementName;
            this.attributeName = attributeName;
        }

        /**
         * Gets the value as a String, normalizing from StringBuilder if needed.
         */
        String getValueAsString(AttributeValueNormalizer normalizer) {
            if (value instanceof String) {
                return (String) value;
            } else if (value instanceof StringBuilder) {
                // Normalize on demand
                if (normalizer != null) {
                    return normalizer.normalize((StringBuilder) value, elementName, attributeName);
                } else {
                    return ((StringBuilder) value).toString();
                }
            }
            return null;
        }
    }

    // Sequential access - also serves as the Attribute object pool
    private List<Attribute> attributes;
    
    // Number of active attributes (vs pool size)
    private int attributeCount;

    // Namespace-aware lookup (CRITICAL PATH - primary access method)
    private Map<QName, Attribute> qnameMap;

    // Non-namespace-aware lookup (for legacy SAX methods and DTD)
    private Map<String, Attribute> stringNameMap;
    
    // Track keys for efficient clearing without Entry reallocation
    // These lists store the keys added in the current element, allowing us to
    // remove only those keys instead of calling clear() which would require
    // reallocating all Entry objects on the next element
    private List<QName> qnameKeys;
    private List<String> stringKeys;

    // Element name for lazy DTD lookup
    private String elementName;
    private DTDParser dtdParser;
    
    // Normalizer for lazy attribute value normalization
    private AttributeValueNormalizer normalizer;
    
    // QName pool for reusing QName objects
    private QNamePool qnamePool;
    
    // Recycler for returning StringBuilders to a pool
    private StringBuilderRecycler stringBuilderRecycler;
    
    // Current element name (for lazy normalization)
    private String currentElementName;

    /**
     * Creates a new empty attribute list.
     */
    public SAXAttributes() {
        this.attributes = new ArrayList<>();
        this.attributeCount = 0;
        // Initial capacity of 24 handles typical elements with many attributes
        // Avoids resize operations during addAttribute() calls
        this.qnameMap = new HashMap<>(24);
        this.stringNameMap = new HashMap<>(24);
        // Track keys to avoid clear() overhead (which forces Entry reallocation)
        this.qnameKeys = new ArrayList<>(24);
        this.stringKeys = new ArrayList<>(24);
    }

    /**
     * Sets the normalizer for lazy attribute value normalization.
     * 
     * @param normalizer the normalizer function
     */
    public void setNormalizer(AttributeValueNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /**
     * Sets the StringBuilder recycler for returning StringBuilders to a pool.
     * Called during clear() to recycle StringBuilder attribute values.
     * 
     * @param recycler the recycler
     */
    public void setStringBuilderRecycler(StringBuilderRecycler recycler) {
        this.stringBuilderRecycler = recycler;
    }
    
    /**
     * Sets the QName pool for reusing QName objects.
     * 
     * @param pool the QName pool
     */
    public void setQNamePool(QNamePool pool) {
        this.qnamePool = pool;
    }

    /**
     * Sets the current element name for lazy attribute normalization.
     * 
     * @param elementName the element name
     */
    public void setElementName(String elementName) {
        this.currentElementName = elementName;
    }

    /**
     * Sets the context for DTD lookups.
     *
     * @param elementName the element name these attributes belong to
     * @param dtdParser the DTD parser to query, or null if no DTD
     */
    public void setDTDContext(String elementName, DTDParser dtdParser) {
        this.elementName = elementName;
        this.dtdParser = dtdParser;
    }

    /**
     * Adds an attribute to the list.
     * Throws an IllegalArgumentException if an attribute with the same qName already exists
     * (violates XML well-formedness constraint).
     *
     * @param uri the namespace URI (use "" for no namespace)
     * @param localName the local name
     * @param qName the qualified name
     * @param type the attribute type
     * @param value the attribute value (String or StringBuilder for lazy normalization)
     * @param specified whether the attribute was specified in the document
     * @throws NamespaceException if duplicate attribute detected
     */
    public void addAttribute(String uri, String localName, String qName,
            String type, Object value, boolean specified) throws NamespaceException {
        // Check for duplicate attribute by string name (well-formedness constraint)
        if (stringNameMap.containsKey(qName)) {
            throw new NamespaceException("Duplicate attribute: " + qName);
        }

        // Get QName from pool (checkout and update)
        QName qnameKey = qnamePool.checkout();
        qnameKey.update(uri, localName, qName);

        // Check for duplicate by expanded name (namespace-aware duplicate detection)
        // Two attributes are duplicates if they have the same namespace URI and local name
        if (qnameMap.containsKey(qnameKey)) {
            // Return QName to pool since we're rejecting this attribute
            qnamePool.returnToPool(qnameKey);
            throw new NamespaceException("Duplicate attribute by expanded name: {" + 
                    uri + "}" + localName + " (qName: " + qName + ")");
        }

        // Get Attribute object from pool or create new
        Attribute attr;
        if (attributeCount < attributes.size()) {
            // Reuse existing Attribute object from pool
            attr = attributes.get(attributeCount);
            attr.update(qnameKey, type, value, specified, currentElementName, qName);
        } else {
            // Create new Attribute and add to pool
            attr = new Attribute();
            attr.update(qnameKey, type, value, specified, currentElementName, qName);
            attributes.add(attr);
        }

        attributeCount++;

        // Add to lookup indices
        qnameMap.put(qnameKey, attr);
        stringNameMap.put(qName, attr);
        
        // Track keys for efficient removal in clear()
        qnameKeys.add(qnameKey);
        stringKeys.add(qName);
    }

    /**
     * Sets the type of an attribute by index.
     *
     * @param index the attribute index
     * @param type the new type
     */
    public void setType(int index, String type) {
        if (index >= 0 && index < attributeCount) {
            Attribute attr = attributes.get(index);
            // Just update the type field (Attribute is now mutable)
            attr.type = type;
            // Maps still point to same object, no need to update
        }
    }

    /**
     * Clears all attributes.
     * Does NOT remove Attribute objects from the pool - reuses them for next element.
     * Returns QName objects to the QName pool for reuse.
     */
    /**
     * Clears all attributes, preparing for the next element.
     * 
     * <p>Performance optimization: Instead of calling HashMap.clear() which
     * nulls all entries (forcing reallocation of Entry objects on next put()),
     * we selectively remove only the keys we added. This keeps the HashMap's
     * internal Entry objects alive for reuse, dramatically reducing allocations
     * in documents with many elements.
     * 
     * <p>With 15,000 elements × 5 attributes, this saves ~75,000 Entry allocations
     * (~2.4 MB) by reusing existing Entry objects.
     */
    public void clear() {
        // IMPORTANT: Remove keys from maps BEFORE returning QNames to pool
        // Once returned to pool, QName objects can be modified, breaking HashMap lookups
        
        // Remove keys individually instead of calling clear()
        // This preserves HashMap Entry objects for reuse, avoiding allocation overhead
        for (QName key : qnameKeys) {
            qnameMap.remove(key);
        }
        for (String key : stringKeys) {
            stringNameMap.remove(key);
        }
        
        // Clear key tracking lists (ArrayList.clear() is fast and doesn't deallocate array)
        qnameKeys.clear();
        stringKeys.clear();
        
        // NOW it's safe to return QName objects to pool and recycle StringBuilders
        for (int i = 0; i < attributeCount; i++) {
            Attribute attr = attributes.get(i);
            qnamePool.returnToPool(attr.qname);
            
            // Recycle StringBuilder values if recycler is set
            if (stringBuilderRecycler != null && attr.value instanceof StringBuilder) {
                stringBuilderRecycler.recycle((StringBuilder) attr.value);
            }
            attr.value = null;  // Clear reference to avoid holding onto recycled StringBuilder
        }
        
        attributeCount = 0;  // Reset active count, keep pooled Attribute objects
        
        elementName = null;
        dtdParser = null;
    }

    // Attributes interface

    @Override
    public int getLength() {
        return attributeCount;
    }

    @Override
    public String getURI(int index) {
        if (index < 0 || index >= attributeCount) {
            return null;
        }
        return attributes.get(index).qname.getURI();
    }

    @Override
    public String getLocalName(int index) {
        if (index < 0 || index >= attributeCount) {
            return null;
        }
        return attributes.get(index).qname.getLocalName();
    }

    @Override
    public String getQName(int index) {
        if (index < 0 || index >= attributeCount) {
            return null;
        }
        return attributes.get(index).qname.getQName();
    }

    @Override
    public String getType(int index) {
        if (index < 0 || index >= attributeCount) {
            return null;
        }

        Attribute attr = attributes.get(index);

        // Check DTD for more specific type information
        if (dtdParser != null && elementName != null) {
            AttributeDeclaration decl = dtdParser.getAttributeDeclaration(
                    elementName, attr.qname.getQName());
            if (decl != null) {
                return decl.type;
            }
        }

        return attr.type;
    }

    @Override
    public String getValue(int index) {
        if (index < 0 || index >= attributeCount) {
            return null;
        }
        return attributes.get(index).getValueAsString(normalizer);
    }

    @Override
    public int getIndex(String uri, String localName) {
        // Create temporary QName for lookup (checkout from pool)
        QName key = qnamePool.checkout();
        key.update(uri, localName, "");
        Attribute attr = qnameMap.get(key);

        // Return temporary QName to pool
        qnamePool.returnToPool(key);

        if (attr == null) {
            return -1;
        }

        // Find index in list
        return attributes.indexOf(attr);
    }

    @Override
    public int getIndex(String qName) {
        Attribute attr = stringNameMap.get(qName);

        if (attr == null) {
            return -1;
        }

        // Find index in list
        return attributes.indexOf(attr);
    }

    @Override
    public String getType(String uri, String localName) {
        QName key = qnamePool.checkout();
        key.update(uri, localName, "");
        Attribute attr = qnameMap.get(key);

        // Return temporary QName to pool
        qnamePool.returnToPool(key);

        if (attr == null) {
            return null;
        }

        // Check DTD for more specific type information
        if (dtdParser != null && elementName != null) {
            AttributeDeclaration decl = dtdParser.getAttributeDeclaration(
                    elementName, attr.qname.getQName());
            if (decl != null) {
                return decl.type;
            }
        }

        return attr.type;
    }

    @Override
    public String getType(String qName) {
        Attribute attr = stringNameMap.get(qName);

        if (attr == null) {
            return null;
        }

        // Check DTD for more specific type information
        if (dtdParser != null && elementName != null) {
            AttributeDeclaration decl = dtdParser.getAttributeDeclaration(
                    elementName, qName);
            if (decl != null) {
                return decl.type;
            }
        }

        return attr.type;
    }

    @Override
    public String getValue(String uri, String localName) {
        QName key = qnamePool.checkout();
        key.update(uri, localName, "");
        Attribute attr = qnameMap.get(key);

        // Return temporary QName to pool
        qnamePool.returnToPool(key);

        return (attr != null) ? attr.getValueAsString(normalizer) : null;
    }

    @Override
    public String getValue(String qName) {
        Attribute attr = stringNameMap.get(qName);
        return (attr != null) ? attr.getValueAsString(normalizer) : null;
    }

    // Attributes2 interface - lazy DTD lookup

    @Override
    public boolean isDeclared(int index) {
        if (index < 0 || index >= attributeCount) {
            throw new ArrayIndexOutOfBoundsException(index);
        }

        if (dtdParser == null || elementName == null) {
            return false;
        }

        Attribute attr = attributes.get(index);
        AttributeDeclaration decl = dtdParser.getAttributeDeclaration(
                elementName, attr.qname.getQName());
        return decl != null;
    }

    @Override
    public boolean isDeclared(String qName) {
        Attribute attr = stringNameMap.get(qName);
        if (attr == null) {
            throw new IllegalArgumentException("Unknown attribute: " + qName);
        }

        if (dtdParser == null || elementName == null) {
            return false;
        }

        AttributeDeclaration decl = dtdParser.getAttributeDeclaration(elementName, qName);
        return decl != null;
    }

    @Override
    public boolean isDeclared(String uri, String localName) {
        QName key = qnamePool.checkout();
        key.update(uri, localName, "");
        Attribute attr = qnameMap.get(key);

        // Return temporary QName to pool
        qnamePool.returnToPool(key);

        if (attr == null) {
            throw new IllegalArgumentException("Unknown attribute: {" + uri + "}" + localName);
        }

        if (dtdParser == null || elementName == null) {
            return false;
        }

        AttributeDeclaration decl = dtdParser.getAttributeDeclaration(
                elementName, attr.qname.getQName());
        return decl != null;
    }

    @Override
    public boolean isSpecified(int index) {
        if (index < 0 || index >= attributeCount) {
            throw new ArrayIndexOutOfBoundsException(index);
        }

        return attributes.get(index).specified;
    }

    @Override
    public boolean isSpecified(String qName) {
        Attribute attr = stringNameMap.get(qName);
        if (attr == null) {
            throw new IllegalArgumentException("Unknown attribute: " + qName);
        }
        return attr.specified;
    }

    @Override
    public boolean isSpecified(String uri, String localName) {
        QName key = qnamePool.checkout();
        key.update(uri, localName, "");
        Attribute attr = qnameMap.get(key);

        // Return temporary QName to pool
        qnamePool.returnToPool(key);

        if (attr == null) {
            throw new IllegalArgumentException("Unknown attribute: {" + uri + "}" + localName);
        }
        return attr.specified;
    }

}
