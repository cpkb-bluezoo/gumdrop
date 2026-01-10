/*
 * ContentModelValidator.java
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

import org.bluezoo.gonzalez.ElementDeclaration.ContentModel;
import org.bluezoo.gonzalez.ElementDeclaration.ContentModel.NodeType;
import org.bluezoo.gonzalez.ElementDeclaration.ContentModel.Occurrence;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates element content against DTD-declared content models.
 * 
 * <p>This validator implements a streaming content model validator that tracks
 * element children as they are encountered and validates them against the
 * content model declared in the DTD.
 * 
 * <p>Content models can be:
 * <ul>
 * <li>EMPTY - no content allowed (no children, no text except whitespace)</li>
 * <li>ANY - any content allowed (no validation)</li>
 * <li>Mixed - (#PCDATA|elem1|elem2)* - text and specific elements</li>
 * <li>Element - structured content with sequences and choices</li>
 * </ul>
 * 
 * <p>For element content models, the validator uses a state machine approach
 * to track progress through the content model as children are encountered.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ContentModelValidator {
    
    private final ElementDeclaration elementDecl;
    private final List<String> children;
    private boolean hasTextContent;
    
    /**
     * Creates a new content model validator for an element.
     * 
     * @param elementDecl the element declaration from the DTD
     */
    public ContentModelValidator(ElementDeclaration elementDecl) {
        this.elementDecl = elementDecl;
        this.children = new ArrayList<>();
        this.hasTextContent = false;
    }
    
    /**
     * Records that text content was encountered.
     * 
     * @param text the text content
     * @param isWhitespaceOnly true if the text contains only whitespace
     * @return error message if validation fails, null if valid
     */
    public String addTextContent(String text, boolean isWhitespaceOnly) {
        if (isWhitespaceOnly) {
            // Whitespace is always allowed (will be ignored in element content)
            return null;
        }
        
        hasTextContent = true;
        
        switch (elementDecl.contentType) {
            case EMPTY:
                return "Element '" + elementDecl.name + "' declared EMPTY but has text content";
                
            case ELEMENT:
                return "Element '" + elementDecl.name + "' has element-only content but contains text";
                
            case ANY:
            case MIXED:
                // Text is allowed
                return null;
                
            default:
                return "Unknown content type";
        }
    }
    
    /**
     * Records that a child element was encountered.
     * 
     * @param childName the name of the child element
     * @return error message if validation fails, null if valid
     */
    public String addChildElement(String childName) {
        children.add(childName);
        
        switch (elementDecl.contentType) {
            case EMPTY:
                return "Element '" + elementDecl.name + "' declared EMPTY but has child element '" + childName + "'";
                
            case ANY:
                // Any children allowed
                return null;
                
            case MIXED:
                // Check if child is allowed in mixed content
                return validateMixedContent(childName);
                
            case ELEMENT:
                // Will validate structure when element closes
                return null;
                
            default:
                return "Unknown content type";
        }
    }
    
    /**
     * Validates that the content is complete (called when element closes).
     * 
     * @return error message if validation fails, null if valid
     */
    public String validate() {
        switch (elementDecl.contentType) {
            case EMPTY:
                // Already validated - no children or text allowed
                return null;
                
            case ANY:
                // Any content allowed
                return null;
                
            case MIXED:
                // Already validated during addChildElement
                return null;
                
            case ELEMENT:
                // Validate element content structure
                return validateElementContent();
                
            default:
                return "Unknown content type";
        }
    }
    
    /**
     * Validates mixed content - checks if child element is allowed.
     */
    private String validateMixedContent(String childName) {
        ContentModel model = elementDecl.contentModel;
        if (model == null || model.children == null) {
            return null; // (#PCDATA)* allows any or no children
        }
        
        // Mixed content: (#PCDATA | elem1 | elem2 | ...)*
        // Check if childName is in the list of allowed elements
        for (ContentModel child : model.children) {
            if (child.type == NodeType.ELEMENT && childName.equals(child.elementName)) {
                return null; // Found it, valid
            }
        }
        
        // Build list of allowed elements for error message
        StringBuilder allowed = new StringBuilder();
        for (ContentModel child : model.children) {
            if (child.type == NodeType.ELEMENT) {
                if (allowed.length() > 0) {
                    allowed.append(", ");
                }
                allowed.append(child.elementName);
            }
        }
        
        return "Element '" + childName + "' not allowed in content of '" + elementDecl.name + 
               "'. Allowed: " + allowed;
    }
    
    /**
     * Validates element content structure.
     * Uses a recursive descent matcher to check if the sequence of children
     * matches the content model.
     */
    private String validateElementContent() {
        if (elementDecl.contentModel == null) {
            // No content model means no children allowed
            if (!children.isEmpty()) {
                return "Element '" + elementDecl.name + "' should have no children";
            }
            return null;
        }
        
        // Try to match the children against the content model
        MatchResult result = matchContentModel(elementDecl.contentModel, children, 0);
        
        if (!result.matched) {
            return "Content of element '" + elementDecl.name + "' does not match content model. " +
                   result.errorMessage;
        }
        
        // Check if we consumed all children
        if (result.position < children.size()) {
            StringBuilder unexpected = new StringBuilder();
            for (int i = result.position; i < children.size(); i++) {
                if (unexpected.length() > 0) {
                    unexpected.append(", ");
                }
                unexpected.append(children.get(i));
            }
            return "Unexpected elements in '" + elementDecl.name + "': " + unexpected;
        }
        
        return null;
    }
    
    /**
     * Result of attempting to match content model.
     */
    private static class MatchResult {
        boolean matched;
        int position;  // How many children were consumed
        String errorMessage;
        
        MatchResult(boolean matched, int position, String errorMessage) {
            this.matched = matched;
            this.position = position;
            this.errorMessage = errorMessage;
        }
        
        static MatchResult success(int position) {
            return new MatchResult(true, position, null);
        }
        
        static MatchResult failure(int position, String error) {
            return new MatchResult(false, position, error);
        }
    }
    
    /**
     * Recursively matches children against a content model node.
     * 
     * @param model the content model node to match
     * @param children the list of child element names
     * @param start the starting position in children list
     * @return match result indicating success/failure and position
     */
    private MatchResult matchContentModel(ContentModel model, List<String> children, int start) {
        if (model == null || model.type == null) {
            return MatchResult.failure(start, "Null or invalid content model");
        }
        
        switch (model.type) {
            case ELEMENT:
                return matchElement(model, children, start);
                
            case SEQUENCE:
                return matchSequence(model, children, start);
                
            case CHOICE:
                return matchChoice(model, children, start);
                
            case PCDATA:
                // #PCDATA in element content is not allowed (only in mixed)
                return MatchResult.failure(start, "#PCDATA not allowed in element content");
                
            default:
                return MatchResult.failure(start, "Unknown content model type");
        }
    }
    
    /**
     * Matches an element name against expected name.
     */
    private MatchResult matchElement(ContentModel model, List<String> children, int start) {
        if (start >= children.size()) {
            // No more children, check if element is required
            if (model.occurrence == Occurrence.ONCE || model.occurrence == Occurrence.ONE_OR_MORE) {
                return MatchResult.failure(start, "Expected '" + model.elementName + "'");
            }
            // Optional or zero-or-more, so absence is OK
            return MatchResult.success(start);
        }
        
        String childName = children.get(start);
        if (model.elementName.equals(childName)) {
            // Matched one occurrence
            int position = start + 1;
            
            // Handle occurrence indicators
            switch (model.occurrence) {
                case ONCE:
                case OPTIONAL:
                    // Matched exactly once, done
                    return MatchResult.success(position);
                    
                case ONE_OR_MORE:
                case ZERO_OR_MORE:
                    // Try to match more occurrences
                    while (position < children.size() && model.elementName.equals(children.get(position))) {
                        position++;
                    }
                    return MatchResult.success(position);
                    
                default:
                    return MatchResult.success(position);
            }
        } else {
            // Doesn't match
            if (model.occurrence == Occurrence.OPTIONAL || model.occurrence == Occurrence.ZERO_OR_MORE) {
                // That's OK, element is optional
                return MatchResult.success(start);
            } else {
                return MatchResult.failure(start, "Expected '" + model.elementName + "', found '" + childName + "'");
            }
        }
    }
    
    /**
     * Matches a sequence (a, b, c).
     */
    private MatchResult matchSequence(ContentModel model, List<String> children, int start) {
        if (model.children == null || model.children.isEmpty()) {
            // Empty sequence matches zero children
            return MatchResult.success(start);
        }
        
        int position = start;
        int sequenceMatches = 0;
        
        do {
            int sequenceStart = position;
            boolean allChildrenMatched = true;
            
            // Try to match all children in sequence
            for (ContentModel child : model.children) {
                MatchResult result = matchContentModel(child, children, position);
                if (!result.matched) {
                    // Failed to match this child in sequence
                    allChildrenMatched = false;
                    if (sequenceMatches == 0) {
                        // Never matched the sequence at all
                        if (model.occurrence == Occurrence.OPTIONAL || 
                            model.occurrence == Occurrence.ZERO_OR_MORE) {
                            // Sequence is optional, so failure is OK
                            return MatchResult.success(start);
                        }
                        return result; // Propagate error
                    }
                    // Already matched sequence at least once, stop trying
                    break;
                }
                position = result.position;
            }
            
            // If all children matched (even if no input consumed), count as successful sequence match
            if (allChildrenMatched) {
                sequenceMatches++;
            }
            
            // Check if we made progress through the sequence
            if (position == sequenceStart) {
                // Didn't consume any children in this iteration
                // If all children matched (all optional), we succeeded, otherwise stop
                break;
            }
            
            // Check if we should try to match sequence again
            if (model.occurrence != Occurrence.ZERO_OR_MORE && 
                model.occurrence != Occurrence.ONE_OR_MORE) {
                // Only match once
                break;
            }
            
        } while (position < children.size());
        
        // Check if we matched the required number of times
        if (sequenceMatches == 0 && 
            (model.occurrence == Occurrence.ONCE || model.occurrence == Occurrence.ONE_OR_MORE)) {
            return MatchResult.failure(start, "Required sequence not found");
        }
        
        return MatchResult.success(position);
    }
    
    /**
     * Matches a choice (a | b | c).
     */
    private MatchResult matchChoice(ContentModel model, List<String> children, int start) {
        if (model.children == null || model.children.isEmpty()) {
            // Empty choice matches zero children
            return MatchResult.success(start);
        }
        
        int position = start;
        int choiceMatches = 0;
        
        do {
            boolean matchedAny = false;
            int bestPosition = position;
            
            // Try each alternative in the choice
            for (ContentModel child : model.children) {
                MatchResult result = matchContentModel(child, children, position);
                if (result.matched && result.position > position) {
                    // This alternative matched and consumed children
                    matchedAny = true;
                    bestPosition = Math.max(bestPosition, result.position);
                }
            }
            
            if (!matchedAny) {
                // None of the alternatives matched
                break;
            }
            
            position = bestPosition;
            choiceMatches++;
            
            // Check if we should try to match choice again
            if (model.occurrence != Occurrence.ZERO_OR_MORE && 
                model.occurrence != Occurrence.ONE_OR_MORE) {
                // Only match once
                break;
            }
            
        } while (position < children.size());
        
        // Check if we matched the required number of times
        if (choiceMatches == 0 && 
            (model.occurrence == Occurrence.ONCE || model.occurrence == Occurrence.ONE_OR_MORE)) {
            return MatchResult.failure(start, "No valid choice found in content model");
        }
        
        return MatchResult.success(position);
    }
}

