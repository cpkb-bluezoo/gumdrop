# JSP Code Generation Example

This example demonstrates how to use Gumdrop's JSP code generation facility programmatically. It shows the complete workflow from parsing JSP source files to generating Java servlet source code.

## What This Example Shows

- **JSP Parsing**: How to parse JSP files using `JspParserFactory`
- **Code Generation**: How to generate Java servlet source code from JSP AST
- **Dual Format Support**: Works with both traditional JSP (`.jsp`) and XML JSP (`.jspx`) formats
- **Taglib Integration**: How to integrate with the `TaglibRegistry` for custom tag support

## Files

- `JSPCodeGeneratorExample.java` - Main example class with two demonstration methods
- `test-example.jsp` - Sample input
- `TestExample_jsp.java` - Sample output (regenerate with `./build.sh`)
- `build.sh` - Compiles and runs the example against the container distribution

## Key Features Demonstrated

### 1. JSP-to-Java File Generation
```java
JSPCodeGeneratorExample.convertJSPToJava(
    "example.jsp",          // Input JSP file
    "ExampleServlet.java",  // Output Java file
    "UTF-8"                 // Character encoding
);
```

### 2. JSP-to-String Conversion
```java
String javaSource = JSPCodeGeneratorExample.convertJSPToString(
    "example.jsp",  // Input JSP file
    "UTF-8"         // Character encoding
);
```

## Workflow Steps

1. **Parse JSP File**: Uses `new JspParserFactory().parseJSP()` to create JSP Abstract Syntax Tree (AST)
2. **Create Code Generator**: Instantiates `JspCodeGenerator` with the JSP page, an output stream and a `TaglibRegistry` (null here)
3. **Generate Code**: Calls `generateCode()` to produce Java servlet source
4. **Output Results**: Writes to file or returns as string

## Usage Example

```java
public class MyJSPProcessor {
    public void processJSP() throws IOException, JspParseException {
        // Generate servlet source file from JSP
        JSPCodeGeneratorExample.convertJSPToJava(
            "src/main/webapp/hello.jsp",
            "generated/HelloServlet.java", 
            "UTF-8"
        );
        
        // Or get source as string for further processing
        String servletSource = JSPCodeGeneratorExample.convertJSPToString(
            "src/main/webapp/hello.jsp",
            "UTF-8"
        );
        
        System.out.println("Generated servlet class:");
        System.out.println(servletSource);
    }
}
```

## Supported JSP Features

The code generator handles all JSP elements:

- **Page Directives**: `<%@ page ... %>`
- **Import Directives**: `<%@ page import="..." %>`
- **Taglib Directives**: `<%@ taglib ... %>`
- **Declarations**: `<%! ... %>`
- **Scriptlets**: `<% ... %>`
- **Expressions**: `<%= ... %>`
- **Comments**: `<%-- ... --%>`
- **Text Content**: Static HTML/XML content
- **Custom Tags**: Via TaglibRegistry integration

## Generated Servlet Structure

The generated servlet:

- Extends `jakarta.servlet.http.HttpServlet`
- Implements the `_jspService(HttpServletRequest, HttpServletResponse)` method
- Includes proper imports and class structure
- Handles JSP page configuration (session, buffer, etc.)
- Integrates with JSP runtime for tag support

## Integration with Gumdrop

This example shows the same code generation process that Gumdrop uses internally when processing JSP requests in the web container. The generated servlets are then compiled and loaded dynamically to handle client requests.

## Notes

- **TaglibRegistry**: The example passes `null` for the `TaglibRegistry`, so custom tags are not resolved. In a real application it would be configured with the servlet context.
- **Error Handling**: Both methods include proper exception handling for I/O and parsing errors.
- **Encoding**: Always specify the correct character encoding for your JSP files.
- **File Paths**: Input JSP files should exist and be readable; output paths should be writable.

## Building and Running

Build the distribution (`ant assemble-container` in the project root), then:

```bash
./build.sh                       # regenerates TestExample_jsp.java from test-example.jsp
./build.sh hello.jsp Hello_jsp.java
```

The script compiles the example against `dist/container-home/lib` (override with
`GUMDROP_LIB`), runs it, and with no arguments checks that the generated servlet
compiles. The output file should be named after the generated class
(`test-example.jsp` becomes `TestExample_jsp`).

This example provides a foundation for building custom JSP processing tools, static site generators, or development utilities that need to work with JSP source code programmatically.
