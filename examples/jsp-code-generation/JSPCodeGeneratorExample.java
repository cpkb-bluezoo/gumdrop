/*
 * JSPCodeGeneratorExample.java
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

// JSP Code Generation Example - no package for simplicity

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

// Import the JSP classes from the Gumdrop servlet package
import org.bluezoo.gumdrop.servlet.jsp.JSPPage;
import org.bluezoo.gumdrop.servlet.jsp.JSPParser;
import org.bluezoo.gumdrop.servlet.jsp.JSPParserFactory;
import org.bluezoo.gumdrop.servlet.jsp.JSPParseException;
import org.bluezoo.gumdrop.servlet.jsp.JSPCodeGenerator;
import org.bluezoo.gumdrop.servlet.jsp.TaglibRegistry;

/**
 * Example demonstrating how to use the JSP code generation facility.
 * 
 * <p>This example shows the complete workflow:
 * <ol>
 *   <li>Parse a JSP file using {@link JSPParserFactory}</li>
 *   <li>Generate Java servlet source code using {@link JSPCodeGenerator}</li>
 *   <li>Write the generated code to a file or stream</li>
 * </ol>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSPCodeGeneratorExample {

    /**
     * Converts a JSP file to a Java servlet source file.
     * 
     * @param jspFilePath    Path to the input JSP file
     * @param javaFilePath   Path to the output Java file
     * @param encoding       Character encoding (e.g., "UTF-8")
     * @throws IOException   If an I/O error occurs
     * @throws JSPParseException If the JSP file cannot be parsed
     */
    public static void convertJSPToJava(String jspFilePath, String javaFilePath, String encoding) 
            throws IOException, JSPParseException {
        
        // Step 1: Parse the JSP file
        try (InputStream jspInput = new FileInputStream(jspFilePath)) {
            JSPPage jspPage = JSPParserFactory.parseJSP(jspInput, encoding, jspFilePath);
            
            // Step 2: Generate Java servlet source code
            try (FileOutputStream javaOutput = new FileOutputStream(javaFilePath)) {
                // For this example, we use null for TaglibRegistry to keep it simple
                // In a real servlet context, this would be properly initialized
                JSPCodeGenerator generator = new JSPCodeGenerator(jspPage, javaOutput, null);
                generator.generateCode();
                
                System.out.println("Generated servlet class: " + generator.getGeneratedClassName());
                System.out.println("JSP file: " + jspFilePath);
                System.out.println("Java file: " + javaFilePath);
            }
        }
    }

    /**
     * Converts a JSP file to Java source code returned as a string.
     * 
     * @param jspFilePath Path to the input JSP file
     * @param encoding    Character encoding (e.g., "UTF-8")
     * @return The generated Java source code
     * @throws IOException   If an I/O error occurs
     * @throws JSPParseException If the JSP file cannot be parsed
     */
    public static String convertJSPToString(String jspFilePath, String encoding) 
            throws IOException, JSPParseException {
        
        try (InputStream jspInput = new FileInputStream(jspFilePath)) {
            JSPPage jspPage = JSPParserFactory.parseJSP(jspInput, encoding, jspFilePath);
            
            ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();
            // For this example, we use null for TaglibRegistry to keep it simple
            JSPCodeGenerator generator = new JSPCodeGenerator(jspPage, byteOutput, null);
            generator.generateCode();
            
            return byteOutput.toString("UTF-8");
        }
    }

    /**
     * Example usage of the JSP code generation facility.
     * 
     * <p>If no arguments are provided, uses the included test-example.jsp file.
     * Otherwise, expects: &lt;input.jsp&gt; &lt;output.java&gt; [encoding]
     */
    public static void main(String[] args) {
        String jspFile, javaFile, encoding;
        
        if (args.length == 0) {
            // Use default test file if no arguments provided
            jspFile = "test-example.jsp";
            javaFile = "GeneratedTestServlet.java";
            encoding = "UTF-8";
            
            System.out.println("JSP Code Generation Example");
            System.out.println("===========================");
            System.out.println("No arguments provided - using default test file:");
            System.out.println("Input:  " + jspFile);
            System.out.println("Output: " + javaFile);
            System.out.println();
            
        } else if (args.length < 2) {
            System.err.println("Usage: JSPCodeGeneratorExample <input.jsp> <output.java> [encoding]");
            System.err.println("       JSPCodeGeneratorExample (uses test-example.jsp)");
            System.err.println("Example: JSPCodeGeneratorExample hello.jsp HelloServlet.java UTF-8");
            System.exit(1);
            return;
            
        } else {
            jspFile = args[0];
            javaFile = args[1];
            encoding = args.length > 2 ? args[2] : "UTF-8";
        }

        try {
            System.out.println("Converting JSP file to Java servlet...");
            convertJSPToJava(jspFile, javaFile, encoding);
            System.out.println("✓ Conversion completed successfully!");
            System.out.println("✓ Generated file: " + javaFile);
            
            // Also demonstrate the string conversion method
            System.out.println();
            System.out.println("Demonstrating string conversion (preview):");
            String javaSource = convertJSPToString(jspFile, encoding);
            System.out.println("Generated " + javaSource.length() + " characters of Java source code.");
            
            // Show first few lines as a preview
            String[] lines = javaSource.split("\n");
            int previewLines = Math.min(10, lines.length);
            System.out.println("Preview (first " + previewLines + " lines):");
            for (int i = 0; i < previewLines; i++) {
                System.out.println("  " + lines[i]);
            }
            if (lines.length > previewLines) {
                System.out.println("  ... (" + (lines.length - previewLines) + " more lines)");
            }
            
        } catch (IOException e) {
            System.err.println("✗ I/O error: " + e.getMessage());
            System.exit(1);
            
        } catch (JSPParseException e) {
            System.err.println("✗ JSP parsing error: " + e.getMessage());
            System.exit(1);
        }
    }
}
