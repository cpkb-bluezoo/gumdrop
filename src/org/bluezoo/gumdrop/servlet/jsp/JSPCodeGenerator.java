/*
 * JSPCodeGenerator.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet.jsp;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Generates Java servlet source code from a JSP Abstract Syntax Tree.
 * 
 * <p>This class takes a {@link JSPPage} object (the parsed JSP AST) and generates
 * the corresponding Java servlet source code that implements {@code javax.servlet.http.HttpServlet}.
 * The generated servlet can then be compiled and loaded to handle JSP requests.</p>
 * 
 * <p>The code generator handles various JSP elements:</p>
 * <ul>
 *   <li>{@link TextElement} - Static HTML/text content → {@code out.write()} calls</li>
 *   <li>{@link ScriptletElement} - Java code blocks → Direct insertion into service method</li>
 *   <li>{@link ExpressionElement} - Java expressions → {@code out.write(String.valueOf())} calls</li>
 *   <li>{@link DeclarationElement} - Java declarations → Class-level fields and methods</li>
 *   <li>{@link DirectiveElement} - Page directives → Imports, content type, session handling</li>
 *   <li>{@link CommentElement} - JSP comments → Java comments or ignored</li>
 *   <li>{@link StandardActionElement} - JSP standard actions → Method calls</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSPCodeGenerator implements JSPElementVisitor {

    private final JSPPage jspPage;
    private final PrintWriter writer;
    private final TaglibRegistry taglibRegistry;
    private final JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties;
    private final Set<String> imports = new HashSet<>();
    private final StringBuilder declarations = new StringBuilder();
    private final StringBuilder serviceMethodBody = new StringBuilder();
    
    // Configuration extracted from page directives
    private String contentType = "text/html; charset=UTF-8";
    private boolean session = true;
    private boolean autoFlush = true;
    private int buffer = 8192;
    private String errorPage = null;
    private boolean isErrorPage = false;
    private String pageEncoding = "UTF-8";
    private String language = "java";
    private String className = null;

    /**
     * Constructs a new JSPCodeGenerator.
     * 
     * @param jspPage The JSP page AST to generate code from.
     * @param output  The output stream to write the generated Java code to.
     * @param taglibRegistry The taglib registry for resolving custom tags.
     * @throws IOException If an I/O error occurs while setting up the writer.
     */
    public JSPCodeGenerator(JSPPage jspPage, OutputStream output, TaglibRegistry taglibRegistry) throws IOException {
        this.jspPage = jspPage;
        this.writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        this.taglibRegistry = taglibRegistry;
        this.jspProperties = null;
        
        // Add default imports
        imports.add("java.io.*");
        imports.add("javax.servlet.*");
        imports.add("javax.servlet.http.*");
        imports.add("javax.servlet.jsp.*");
    }

    /**
     * Constructs a new JSPCodeGenerator with JSP configuration properties.
     * 
     * @param jspPage The JSP page AST to generate code from.
     * @param output  The output stream to write the generated Java code to.
     * @param taglibRegistry The taglib registry for resolving custom tags.
     * @param jspProperties The resolved JSP configuration properties.
     * @throws IOException If an I/O error occurs while setting up the writer.
     */
    public JSPCodeGenerator(JSPPage jspPage, OutputStream output, TaglibRegistry taglibRegistry,
                           JSPPropertyGroupResolver.ResolvedJSPProperties jspProperties) throws IOException {
        this.jspPage = jspPage;
        this.writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        this.taglibRegistry = taglibRegistry;
        this.jspProperties = jspProperties;
        
        // Add default imports
        imports.add("java.io.*");
        imports.add("javax.servlet.*");
        imports.add("javax.servlet.http.*");
        imports.add("javax.servlet.jsp.*");
        
        // Apply JSP properties to configuration
        applyJSPProperties();
    }

    /**
     * Sets the class name for the generated servlet.
     * 
     * @param className the class name to use for the generated servlet
     */
    public void setClassName(String className) {
        this.className = className;
    }

    /**
     * Applies JSP configuration properties to the code generator settings.
     * This method overrides default values with those specified in JSP configuration.
     */
    private void applyJSPProperties() {
        if (jspProperties == null) {
            return;
        }
        
        // Apply buffer size
        if (jspProperties.getBuffer() != null) {
            try {
                String bufferValue = jspProperties.getBuffer();
                if (!"none".equals(bufferValue)) {
                    // Remove 'kb' suffix if present
                    if (bufferValue.endsWith("kb")) {
                        bufferValue = bufferValue.substring(0, bufferValue.length() - 2);
                    }
                    this.buffer = Integer.parseInt(bufferValue) * 1024; // Convert KB to bytes
                } else {
                    this.buffer = 0; // No buffering
                }
            } catch (NumberFormatException e) {
                // Ignore invalid buffer values, keep default
            }
        }
        
        // Apply default content type
        if (jspProperties.getDefaultContentType() != null) {
            this.contentType = jspProperties.getDefaultContentType();
        }
    }

    /**
     * Generates the complete Java servlet source code from the JSP page.
     * 
     * @throws IOException If an I/O error occurs during code generation.
     */
    public void generateCode() throws IOException {
        try {
            // First pass: process page directives and declarations
            processPageDirectives();
            
            // Second pass: generate service method body
            generateServiceMethodBody();
            
            // Generate the complete Java class
            generateJavaClass();
            
        } finally {
            writer.flush();
        }
    }

    /**
     * Determines the class name for the generated servlet.
     * 
     * @return The class name to use for the generated servlet.
     */
    public String getGeneratedClassName() {
        if (className != null) {
            return className;
        }
        
        // Extract class name from JSP URI
        String jspUri = jspPage.getUri();
        if (jspUri != null) {
            // Remove path and extension, convert to valid Java class name
            int lastSlash = jspUri.lastIndexOf('/');
            String filename = lastSlash >= 0 ? jspUri.substring(lastSlash + 1) : jspUri;
            
            int lastDot = filename.lastIndexOf('.');
            if (lastDot > 0) {
                filename = filename.substring(0, lastDot);
            }
            
            // Convert to valid Java identifier
            StringBuilder sb = new StringBuilder();
            boolean capitalizeNext = true;
            for (char c : filename.toCharArray()) {
                if (Character.isJavaIdentifierPart(c)) {
                    sb.append(capitalizeNext ? Character.toUpperCase(c) : c);
                    capitalizeNext = false;
                } else {
                    capitalizeNext = true;
                }
            }
            
            String result = sb.toString();
            if (result.isEmpty() || !Character.isJavaIdentifierStart(result.charAt(0))) {
                result = "JSP_" + result;
            }
            
            return result + "_jsp";
        }
        
        return "GeneratedJSP";
    }

    private void processPageDirectives() {
        // Process page directives from JSPPage
        // Note: Page directives are handled by JSPPage internally
        // Extract the current settings from JSPPage
        contentType = jspPage.getContentType();
        session = jspPage.isSessionEnabled();
        autoFlush = jspPage.isAutoFlush();
        buffer = jspPage.getBuffer();
        isErrorPage = jspPage.isErrorPage();
        errorPage = jspPage.getErrorPage();
        pageEncoding = jspPage.getEncoding();
        
        // Add imports from JSPPage
        imports.addAll(jspPage.getImports());
        
        // Process directive elements
        for (JSPElement element : jspPage.getElements()) {
            if (element instanceof DirectiveElement) {
                DirectiveElement directive = (DirectiveElement) element;
                if ("page".equals(directive.getName())) {
                    for (Map.Entry<String, String> attr : directive.getAttributes().entrySet()) {
                        processPageDirective(attr.getKey(), attr.getValue());
                    }
                }
            } else if (element instanceof DeclarationElement) {
                DeclarationElement declaration = (DeclarationElement) element;
                declarations.append("    ").append(declaration.getDeclaration()).append("\n");
            }
        }
    }

    private void processPageDirective(String name, String value) {
        switch (name) {
            case "contentType":
                contentType = value;
                break;
            case "session":
                session = "true".equalsIgnoreCase(value);
                break;
            case "buffer":
                if ("none".equalsIgnoreCase(value)) {
                    buffer = 0;
                    autoFlush = false;
                } else {
                    try {
                        String bufferValue = value.toLowerCase().replace("kb", "");
                        buffer = Integer.parseInt(bufferValue) * 1024;
                    } catch (NumberFormatException e) {
                        // Use default
                    }
                }
                break;
            case "autoFlush":
                autoFlush = "true".equalsIgnoreCase(value);
                break;
            case "errorPage":
                errorPage = value;
                break;
            case "isErrorPage":
                isErrorPage = "true".equalsIgnoreCase(value);
                break;
            case "pageEncoding":
                pageEncoding = value;
                break;
            case "language":
                language = value;
                break;
            case "import":
                // Handle comma-separated imports
                int impStart = 0;
                int impLen = value.length();
                while (impStart <= impLen) {
                    int impEnd = value.indexOf(',', impStart);
                    if (impEnd < 0) {
                        impEnd = impLen;
                    }
                    String imp = value.substring(impStart, impEnd).trim();
                    if (!imp.isEmpty()) {
                        imports.add(imp);
                    }
                    impStart = impEnd + 1;
                }
                break;
            case "extends":
                // TODO: Handle custom base class
                break;
            case "implements":
                // TODO: Handle additional interfaces
                break;
        }
    }

    private void generateServiceMethodBody() {
        // Declare JspWriter instead of PrintWriter to match pageContext.getOut()
        serviceMethodBody.append("        JspWriter out = null;\n");
        
        if (session) {
            serviceMethodBody.append("        HttpSession session = request.getSession();\n");
        }
        
        // Initialize PageContext for JSP tag support
        serviceMethodBody.append("        PageContext pageContext = null;\n");
        serviceMethodBody.append("        try {\n");
        serviceMethodBody.append("            // Create page context for JSP tags\n");
        serviceMethodBody.append("            javax.servlet.jsp.JspFactory jspFactory = javax.servlet.jsp.JspFactory.getDefaultFactory();\n");
        serviceMethodBody.append("            pageContext = jspFactory.getPageContext(this, request, response, null, ")
                       .append(session ? "true" : "false").append(", ").append(buffer).append(", ")
                       .append(autoFlush).append(");\n");
        serviceMethodBody.append("            out = pageContext.getOut();\n");
        serviceMethodBody.append("        \n");
        
        // Process all elements in the JSP page
        for (JSPElement element : jspPage.getElements()) {
            // Skip directives and declarations (already processed)
            if (!(element instanceof DirectiveElement) && !(element instanceof DeclarationElement)) {
                try {
                    element.accept(this);
                } catch (Exception e) {
                    // Convert visitor exceptions to runtime exceptions for code generation
                    throw new RuntimeException("Error processing JSP element: " + element.getClass().getSimpleName(), e);
                }
            }
        }
        
        serviceMethodBody.append("        } catch (Exception e) {\n");
        serviceMethodBody.append("            throw new ServletException(\"JSP processing error\", e);\n");
        serviceMethodBody.append("        } finally {\n");
        serviceMethodBody.append("            if (pageContext != null) {\n");
        serviceMethodBody.append("                javax.servlet.jsp.JspFactory.getDefaultFactory().releasePageContext(pageContext);\n");
        serviceMethodBody.append("            }\n");
        serviceMethodBody.append("        }\n");
    }

    private void generateJavaClass() {
        // Package declaration (if needed)
        // writer.println("package org.bluezoo.gumdrop.servlet.jsp.generated;");
        // writer.println();
        
        // Imports
        for (String imp : imports) {
            writer.println("import " + imp + ";");
        }
        writer.println();
        
        // Class declaration
        writer.println("/**");
        writer.println(" * Generated servlet from JSP: " + jspPage.getUri());
        writer.println(" * Generated at: " + new java.util.Date());
        writer.println(" */");
        writer.println("public class " + getGeneratedClassName() + " extends HttpServlet {");
        writer.println();
        
        // Class-level declarations
        if (declarations.length() > 0) {
            writer.println("    // JSP Declarations");
            writer.print(declarations.toString());
            writer.println();
        }
        
        // Service method
        writer.println("    @Override");
        writer.println("    protected void service(HttpServletRequest request, HttpServletResponse response)");
        writer.println("            throws ServletException, IOException {");
        writer.println("        ");
        writer.println("        response.setContentType(\"" + escapeJavaString(contentType) + "\");");
        
        if (pageEncoding != null && !pageEncoding.isEmpty()) {
            writer.println("        response.setCharacterEncoding(\"" + escapeJavaString(pageEncoding) + "\");");
        }
        
        writer.print(serviceMethodBody.toString());
        writer.println("    }");
        writer.println("}");
    }

    // JSPElementVisitor implementation

    @Override
    public void visitText(TextElement element) throws Exception {
        String content = element.getContent();
        if (content != null && !content.isEmpty()) {
            serviceMethodBody.append("            out.write(\"")
                           .append(escapeJavaString(content))
                           .append("\");\n");
        }
    }

    @Override
    public void visitScriptlet(ScriptletElement element) throws Exception {
        String javaCode = element.getCode();
        if (javaCode != null && !javaCode.trim().isEmpty()) {
            serviceMethodBody.append("            // Scriptlet at line ")
                           .append(element.getLineNumber())
                           .append("\n");
            
            // Add proper indentation to the Java code
            int lineStart = 0;
            int codeLen = javaCode.length();
            while (lineStart <= codeLen) {
                int lineEnd = javaCode.indexOf('\n', lineStart);
                if (lineEnd < 0) {
                    lineEnd = codeLen;
                }
                String line = javaCode.substring(lineStart, lineEnd);
                serviceMethodBody.append("            ").append(line).append("\n");
                lineStart = lineEnd + 1;
            }
        }
    }

    @Override
    public void visitExpression(ExpressionElement element) throws Exception {
        String javaExpression = element.getExpression();
        if (javaExpression != null && !javaExpression.trim().isEmpty()) {
            serviceMethodBody.append("            out.write(String.valueOf(")
                           .append(javaExpression.trim())
                           .append("));\n");
        }
    }

    @Override
    public void visitDeclaration(DeclarationElement element) throws Exception {
        // Declarations are processed in processPageDirectives()
        // This visitor method is called during service method generation,
        // but declarations should already be handled
    }

    @Override
    public void visitDirective(DirectiveElement element) throws Exception {
        // Directives are processed in processPageDirectives()
        // This visitor method is called during service method generation,
        // but directives should already be handled
    }

    @Override
    public void visitComment(CommentElement element) throws Exception {
        // JSP comments are not included in the generated output
        // Could optionally add as Java comments:
        // serviceMethodBody.append("            // JSP Comment: ")
        //                .append(escapeJavaString(element.getContent()))
        //                .append("\n");
    }

    @Override
    public void visitCustomTag(CustomTagElement element) throws Exception {
        String prefix = element.getPrefix();
        String tagName = element.getTagName();
        Map<String, String> attributes = element.getAttributes();
        
        if (prefix == null || tagName == null) {
            serviceMethodBody.append("            // Invalid custom tag at line ")
                           .append(element.getLineNumber()).append("\n");
            return;
        }
        
        // Get taglib URI for this prefix
        String taglibUri = jspPage.getTaglibUri(prefix);
        if (taglibUri == null) {
            serviceMethodBody.append("            // Unknown taglib prefix '")
                           .append(prefix).append("' for tag '").append(tagName)
                           .append("' at line ").append(element.getLineNumber()).append("\n");
            return;
        }
        
        try {
            // Check if taglibRegistry is available (might be null in examples/testing)
            if (taglibRegistry == null) {
                serviceMethodBody.append("            // Custom tag '").append(prefix).append(":")
                               .append(tagName).append("' skipped (no taglib registry available)\n");
                return;
            }
            
            // Resolve the taglib and get tag descriptor
            TagLibraryDescriptor tld = taglibRegistry.resolveTaglib(taglibUri);
            if (tld == null) {
                serviceMethodBody.append("            // Unable to resolve taglib URI '")
                               .append(taglibUri).append("' for tag '").append(prefix)
                               .append(":").append(tagName).append("' at line ")
                               .append(element.getLineNumber()).append("\n");
                return;
            }
            
            TagLibraryDescriptor.TagDescriptor tagDescriptor = tld.getTag(tagName);
            if (tagDescriptor == null) {
                serviceMethodBody.append("            // Tag '").append(tagName)
                               .append("' not found in taglib '").append(taglibUri)
                               .append("' at line ").append(element.getLineNumber()).append("\n");
                return;
            }
            
            generateCustomTagCode(element, tagDescriptor, prefix, tagName, attributes);
            
        } catch (Exception e) {
            serviceMethodBody.append("            // Error resolving custom tag '")
                           .append(prefix).append(":").append(tagName)
                           .append("': ").append(e.getMessage())
                           .append(" at line ").append(element.getLineNumber()).append("\n");
        }
    }

    /**
     * Generates Java code for a custom tag.
     */
    private void generateCustomTagCode(CustomTagElement element, TagLibraryDescriptor.TagDescriptor tagDescriptor, 
                                     String prefix, String tagName, Map<String, String> attributes) {
        
        String tagClass = tagDescriptor.getTagClass();
        if (tagClass == null || tagClass.isEmpty()) {
            serviceMethodBody.append("            // No tag class specified for ")
                           .append(prefix).append(":").append(tagName).append("\n");
            return;
        }
        
        // Add import for tag handler class
        imports.add(tagClass);
        
        // Generate unique variable name for the tag instance
        String tagVarName = "tag_" + prefix + "_" + tagName + "_" + element.getLineNumber();
        
        serviceMethodBody.append("            // Custom tag: ").append(prefix).append(":").append(tagName).append("\n");
        
        // Create tag instance
        String simpleClassName = getSimpleClassName(tagClass);
        serviceMethodBody.append("            ").append(simpleClassName).append(" ").append(tagVarName)
                       .append(" = new ").append(simpleClassName).append("();\n");
        
        // Set page context
        serviceMethodBody.append("            ").append(tagVarName).append(".setPageContext(pageContext);\n");
        
        // Set parent tag if needed (for nested tags - simplified implementation)
        serviceMethodBody.append("            ").append(tagVarName).append(".setParent(null);\n");
        
        // Set tag attributes
        for (Map.Entry<String, String> attr : attributes.entrySet()) {
            String attrName = attr.getKey();
            String attrValue = attr.getValue();
            
            // Validate attribute exists in TLD
            TagLibraryDescriptor.AttributeDescriptor attrDescriptor = tagDescriptor.getAttribute(attrName);
            if (attrDescriptor != null) {
                generateAttributeSetterCode(tagVarName, attrName, attrValue, attrDescriptor);
            } else {
                serviceMethodBody.append("            // Warning: Unknown attribute '")
                               .append(attrName).append("' for tag ").append(prefix)
                               .append(":").append(tagName).append("\n");
            }
        }
        
        // Handle tag lifecycle based on body content type
        String bodyContent = tagDescriptor.getBodyContent();
        if ("empty".equals(bodyContent)) {
            // Tag with no body
            serviceMethodBody.append("            try {\n");
            serviceMethodBody.append("                int result = ").append(tagVarName).append(".doStartTag();\n");
            serviceMethodBody.append("                ").append(tagVarName).append(".doEndTag();\n");
            serviceMethodBody.append("            } catch (javax.servlet.jsp.JspException e) {\n");
            serviceMethodBody.append("                throw new ServletException(\"JSP tag error\", e);\n");
            serviceMethodBody.append("            } finally {\n");
            serviceMethodBody.append("                ").append(tagVarName).append(".release();\n");
            serviceMethodBody.append("            }\n");
        } else {
            // Tag with body content (JSP, scriptless, tagdependent)
            serviceMethodBody.append("            // TODO: Implement body content handling for ")
                           .append(bodyContent).append(" tags\n");
            serviceMethodBody.append("            try {\n");
            serviceMethodBody.append("                int result = ").append(tagVarName).append(".doStartTag();\n");
            serviceMethodBody.append("                // Body content would be processed here\n");
            serviceMethodBody.append("                ").append(tagVarName).append(".doEndTag();\n");
            serviceMethodBody.append("            } catch (javax.servlet.jsp.JspException e) {\n");
            serviceMethodBody.append("                throw new ServletException(\"JSP tag error\", e);\n");
            serviceMethodBody.append("            } finally {\n");
            serviceMethodBody.append("                ").append(tagVarName).append(".release();\n");
            serviceMethodBody.append("            }\n");
        }
    }

    /**
     * Generates code to set a tag attribute value.
     */
    private void generateAttributeSetterCode(String tagVarName, String attrName, String attrValue, 
                                           TagLibraryDescriptor.AttributeDescriptor attrDescriptor) {
        
        // Convert attribute name to setter method name
        String setterName = "set" + Character.toUpperCase(attrName.charAt(0)) + attrName.substring(1);
        
        // Handle attribute value based on whether it can contain runtime expressions
        if (attrDescriptor.isRtexprvalue() && attrValue.startsWith("${") && attrValue.endsWith("}")) {
            // EL expression - for now just treat as string literal
            serviceMethodBody.append("            // TODO: Evaluate EL expression: ").append(attrValue).append("\n");
            serviceMethodBody.append("            ").append(tagVarName).append(".").append(setterName)
                           .append("(\"").append(escapeJavaString(attrValue)).append("\");\n");
        } else {
            // String literal value
            serviceMethodBody.append("            ").append(tagVarName).append(".").append(setterName)
                           .append("(\"").append(escapeJavaString(attrValue)).append("\");\n");
        }
    }

    /**
     * Extracts the simple class name from a fully qualified class name.
     */
    private String getSimpleClassName(String fullyQualifiedName) {
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? fullyQualifiedName.substring(lastDot + 1) : fullyQualifiedName;
    }

    @Override
    public void visitStandardAction(StandardActionElement element) throws Exception {
        String actionName = element.getActionName();
        Map<String, String> attributes = element.getAttributes();
        
        serviceMethodBody.append("            // Standard action ")
                       .append(actionName)
                       .append(" at line ")
                       .append(element.getLineNumber())
                       .append("\n");
        
        switch (actionName) {
            case "include":
                String includePage = attributes.get("page");
                if (includePage != null) {
                    serviceMethodBody.append("            request.getRequestDispatcher(\"")
                                   .append(escapeJavaString(includePage))
                                   .append("\").include(request, response);\n");
                }
                break;
                
            case "forward":
                String forwardPage = attributes.get("page");
                if (forwardPage != null) {
                    serviceMethodBody.append("            request.getRequestDispatcher(\"")
                                   .append(escapeJavaString(forwardPage))
                                   .append("\").forward(request, response);\n");
                    serviceMethodBody.append("            return;\n");
                }
                break;
                
            case "useBean":
                String beanId = attributes.get("id");
                String beanClass = attributes.get("class");
                String beanScope = attributes.get("scope");
                if (beanId != null && beanClass != null) {
                    serviceMethodBody.append("            // TODO: Implement jsp:useBean for ")
                                   .append(beanId)
                                   .append(" (")
                                   .append(beanClass)
                                   .append(")\n");
                }
                break;
                
            default:
                serviceMethodBody.append("            // TODO: Implement ")
                               .append(actionName)
                               .append(" action\n");
                break;
        }
    }

    /**
     * Escapes a string for use in Java string literals.
     * 
     * @param str The string to escape.
     * @return The escaped string.
     */
    private String escapeJavaString(String str) {
        if (str == null) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (char c : str.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 32 || c > 126) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }
}
