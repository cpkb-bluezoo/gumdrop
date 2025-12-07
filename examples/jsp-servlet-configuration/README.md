# JSP Servlet Configuration in Gumdrop

This document explains how JSP files are automatically processed in Gumdrop and how to configure custom JSP servlet mappings if needed.

## Automatic JSP Configuration (Default)

Gumdrop **automatically** configures JSP processing for all web applications. No manual configuration is required!

### What Happens Automatically

When Gumdrop starts a web application, it:

1. **Checks for existing JSP mappings** in `web.xml`
2. **If no JSP mappings exist**, it automatically creates:
   - A JSP servlet (`org.bluezoo.gumdrop.servlet.jsp.JSPServlet`)
   - URL patterns for `*.jsp` and `*.jspx` files
   - Proper servlet initialization and lifecycle management

### Default Configuration

The automatically created configuration is equivalent to this `web.xml` configuration:

```xml
<servlet>
    <servlet-name>jsp</servlet-name>
    <servlet-class>org.bluezoo.gumdrop.servlet.jsp.JSPServlet</servlet-class>
    <load-on-startup>3</load-on-startup>
</servlet>

<servlet-mapping>
    <servlet-name>jsp</servlet-name>
    <url-pattern>*.jsp</url-pattern>
</servlet-mapping>

<servlet-mapping>
    <servlet-name>jsp</servlet-name>
    <url-pattern>*.jspx</url-pattern>
</servlet-mapping>
```

## How JSP Processing Works

1. **Request arrives** for a `.jsp` or `.jspx` file
2. **JSPServlet handles** the request
3. **Context.parseJSPFile()** is called to:
   - Parse the JSP file using appropriate parser (Traditional or XML)
   - Apply JSP configuration from `web.xml` (encoding, scripting, etc.)
   - Generate Java servlet source code
   - Compile to bytecode using internal `javac`
   - Load the compiled servlet class
4. **Generated servlet** processes the request and returns dynamic content

## Manual Configuration (Optional)

If you want to customize JSP processing, you can override the automatic configuration by defining your own JSP servlet mapping in `web.xml`:

### Custom JSP Servlet Configuration

```xml
<?xml version="1.0" encoding="UTF-8"?>
<web-app xmlns="http://java.sun.com/xml/ns/javaee"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://java.sun.com/xml/ns/javaee
         http://java.sun.com/xml/ns/javaee/web-app_4_0.xsd"
         version="4.0">

    <servlet>
        <servlet-name>custom-jsp</servlet-name>
        <servlet-class>org.bluezoo.gumdrop.servlet.jsp.JSPServlet</servlet-class>
        <load-on-startup>1</load-on-startup>
        
        <!-- Custom init parameters (if needed) -->
        <init-param>
            <param-name>development</param-name>
            <param-value>true</param-value>
        </init-param>
    </servlet>

    <!-- Map JSP files -->
    <servlet-mapping>
        <servlet-name>custom-jsp</servlet-name>
        <url-pattern>*.jsp</url-pattern>
    </servlet-mapping>

    <!-- Map JSPX files -->
    <servlet-mapping>
        <servlet-name>custom-jsp</servlet-name>
        <url-pattern>*.jspx</url-pattern>
    </servlet-mapping>

    <!-- Optional: Map specific JSP directories -->
    <servlet-mapping>
        <servlet-name>custom-jsp</servlet-name>
        <url-pattern>/templates/*</url-pattern>
    </servlet-mapping>

</web-app>
```

### JSP Configuration Options

Configure JSP behavior using the `<jsp-config>` section:

```xml
<jsp-config>
    <!-- Global JSP settings -->
    <jsp-property-group>
        <url-pattern>*.jsp</url-pattern>
        <url-pattern>*.jspx</url-pattern>
        <page-encoding>UTF-8</page-encoding>
        <scripting-invalid>false</scripting-invalid>
        <default-content-type>text/html; charset=UTF-8</default-content-type>
    </jsp-property-group>

    <!-- Disable scripting in admin JSPs -->
    <jsp-property-group>
        <url-pattern>/admin/*.jsp</url-pattern>
        <scripting-invalid>true</scripting-invalid>
    </jsp-property-group>

    <!-- Include common header/footer -->
    <jsp-property-group>
        <url-pattern>/pages/*.jsp</url-pattern>
        <include-prelude>/WEB-INF/includes/header.jsp</include-prelude>
        <include-coda>/WEB-INF/includes/footer.jsp</include-coda>
    </jsp-property-group>
</jsp-config>
```

## Troubleshooting

### JSP Files Served as Static Content

**Problem**: Accessing `/example.jsp` returns JSP source code instead of processed output.

**Solution**: This indicates JSP servlet mapping is not working. Check:

1. **Verify automatic configuration**:
   ```
   INFO: Automatically configured JSP servlet for *.jsp and *.jspx files
   ```

2. **Check for conflicting mappings** in `web.xml`
3. **Verify JSP files are in correct location** (under webapp root)
4. **Check file extensions** (`.jsp` or `.jspx`)

### Compilation Errors

**Problem**: JSP processing fails with compilation errors.

**Solution**: 
1. **Check JSP syntax** - use JSP code generation example to test parsing
2. **Verify imports** - ensure all referenced classes are in classpath
3. **Check web.xml JSP config** - ensure encoding and other settings are correct
4. **Review servlet logs** for detailed error messages

### Performance Considerations

For production deployments:

1. **Pre-compile JSPs** using the code generation example
2. **Configure appropriate buffer sizes** in JSP config
3. **Use `scripting-invalid="true"`** where possible for security
4. **Consider static includes** vs dynamic includes for performance

## Integration with Gumdrop Features

### Security Integration

JSPs automatically integrate with Gumdrop's security framework:

- **Realm-based authentication** works transparently
- **Role-based authorization** supported via `request.isUserInRole()`
- **HTTPS enforcement** via security constraints

### Session Management

JSP session handling integrates with Gumdrop's session management:

- **Clustered sessions** work automatically with JSPs
- **Session replication** includes JSP-modified session data
- **Session timeouts** respect web.xml configuration

### Error Handling

JSP error pages integrate with Gumdrop's error handling:

```xml
<error-page>
    <exception-type>java.lang.Exception</exception-type>
    <location>/error/exception.jsp</location>
</error-page>

<error-page>
    <error-code>404</error-code>
    <location>/error/404.jsp</location>
</error-page>
```

## Examples

The Gumdrop distribution includes comprehensive JSP examples in `/jsp/`:

- `simple.jsp` - Basic JSP functionality
- `simple-xml.jspx` - XML JSP syntax
- `taglib-example.jsp` - Custom tag usage  
- `enhanced/prelude-coda-example.jsp` - Include preprocessing
- `restricted/blocked-scripting.jsp` - Security demonstration

Visit `http://localhost:8080/jsp/` when running Gumdrop to explore these examples.

## Advanced Topics

### Custom JSP Compiler Integration

For advanced use cases, you can extend the JSP processing pipeline:

1. **Custom JSP parsers** - implement `JSPParser` interface
2. **Custom code generators** - extend `JSPCodeGenerator` class  
3. **Custom tag libraries** - implement via `TaglibRegistry`
4. **Preprocessing filters** - intercept JSP content before parsing

### Development vs Production

**Development Mode**:
- JSPs recompiled on every request if source changes
- Detailed error reporting with source line numbers
- Debug information included in generated servlets

**Production Mode**:  
- JSPs compiled once and cached
- Minimal error reporting for security
- Optimized generated servlet code

The JSP servlet automatically detects file changes and recompiles as needed, making development seamless while maintaining production performance.
