# HTTP File Server Security Documentation

## Overview

The Gumdrop HTTP file server implements comprehensive security measures to prevent unauthorized access to files outside the configured root directory. This document outlines the security features, attack prevention mechanisms, and best practices.

## Sandboxing Architecture

### Root Directory Isolation

The file server enforces strict sandboxing through multiple layers of validation:

1. **Path Normalization**: All request paths are normalized to resolve `.` and `..` components
2. **Real Path Resolution**: Symbolic links are resolved to their actual targets
3. **Root Boundary Checks**: Both logical and physical path validation ensures no access outside root
4. **Component Validation**: Each path component is individually validated for dangerous patterns

### Security Features

#### Directory Traversal Prevention

**Protected Against:**
- Classic attacks: `/../../../etc/passwd`
- URL encoded attacks: `/%2e%2e/etc/passwd` 
- Double encoding: `/%252e%252e/etc/passwd`
- Mixed encoding attempts
- Nested traversal: `/subdir/../../etc/passwd`

**Implementation:**
```java
// Path components containing ".." are rejected
if ("..".equals(component) || component.contains("..")) {
    return true; // Dangerous
}
```

#### Symbolic Link Security

**Protection:**
- Symbolic links are resolved to their real paths
- Links pointing outside the root directory are rejected
- Broken symlinks are handled gracefully

**Implementation:**
```java
if (Files.exists(resolvedPath)) {
    Path realPath = resolvedPath.toRealPath();
    if (!isWithinRoot(realPath)) {
        // Reject symlink outside root
        return null;
    }
}
```

#### Character-Based Attacks

**Null Byte Injection Protection:**
- Paths containing null bytes (`\0`) are rejected
- URL encoded null bytes (`%00`) are detected and blocked

**Control Character Protection:**
- All ISO control characters are rejected in path components
- This prevents various injection and manipulation attacks

**Windows-Specific Protection:**
- Device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9) are blocked
- Invalid filename characters (`<`, `>`, `|`, `"`, `?`, `*`, `:`) are rejected

#### Encoding Attack Prevention

**Double Encoding Detection:**
- Path components are checked for multiple encoding layers
- Double-encoded traversal attempts are detected and blocked

**Implementation:**
```java
String doubleDecoded = URLDecoder.decode(decodedComponent, "UTF-8");
if (!doubleDecoded.equals(decodedComponent)) {
    // Double encoding detected - reject
    return null;
}
```

#### DoS Protection

**Path Length Limits:**
- Maximum path length: 2048 characters
- Prevents resource exhaustion attacks

**Resource Management:**
- File channels are properly closed in all code paths
- Temporary resources are cleaned up on exceptions

## Configuration Security

### Root Path Validation

The `FileHTTPConnector` validates root paths at configuration time:

```java
private void validateRootPath(Path path) {
    Path realPath = path.toRealPath();
    
    // Must be a directory
    if (!Files.isDirectory(realPath)) {
        throw new IllegalArgumentException("Root path must be a directory");
    }
    
    // Must be readable
    if (!Files.isReadable(realPath)) {
        throw new IllegalArgumentException("Root path must be readable");
    }
    
    // Log canonical path for audit trail
    LOGGER.info("File server root validated: " + realPath);
}
```

### Write Protection

When `allowWrite = false`:
- PUT and DELETE requests return `405 Method Not Allowed`
- Only GET, HEAD, and OPTIONS methods are permitted

When `allowWrite = true`:
- Additional validation ensures parent directories can be created safely
- File operations are performed within the sandboxed environment

## Security Logging

### Attack Detection Logging

All security violations are logged with appropriate detail:

```java
LOGGER.warning("Rejected dangerous path component: " + logSafePath(component));
LOGGER.warning("Rejected path outside root: " + logSafePath(path));
LOGGER.warning("Rejected null byte attack: " + logSafePath(requestPath));
```

### Safe Logging

Potentially malicious path data is sanitized before logging:

```java
private String logSafePath(String path) {
    // Remove control characters and limit length
    String safe = path.replaceAll("[\\p{Cntrl}]", "?");
    if (safe.length() > 100) {
        safe = safe.substring(0, 100) + "...[truncated]";
    }
    return safe;
}
```

## Testing Coverage

### Comprehensive Security Tests

The `FileSecurityTest` class validates protection against:

1. **Directory Traversal Attacks**
   - Classic `../` patterns
   - URL encoded variants
   - Multiple encoding layers
   - Nested traversal attempts

2. **Character-Based Attacks**
   - Null byte injection
   - Control characters
   - Windows device names
   - Invalid filename characters

3. **Symbolic Link Attacks**
   - Links pointing outside root
   - Broken symlinks
   - Chain of symlinks

4. **Edge Cases**
   - Empty and null paths
   - Overly long paths
   - Malformed URLs
   - Path normalization edge cases

### Test Examples

```java
// Directory traversal
assertNull("../ should be rejected", 
          testStream.testValidateAndResolvePath("/../"));

// Null byte injection
assertNull("Null byte should be rejected", 
          testStream.testValidateAndResolvePath("/subdir\0/../../etc/passwd"));

// Windows device names
assertNull("CON device name should be rejected", 
          testStream.testValidateAndResolvePath("/CON"));
```

## Best Practices

### Deployment Security

1. **Root Directory Selection**
   - Use a dedicated directory with minimal privileges
   - Avoid system directories or user home directories
   - Set appropriate filesystem permissions

2. **Write Operations**
   - Only enable write operations when necessary
   - Consider using separate read-only and read-write roots
   - Monitor write operations via logging

3. **Network Security**
   - Use HTTPS for sensitive file operations
   - Implement proper authentication and authorization
   - Consider IP-based access controls

### Monitoring

1. **Log Monitoring**
   - Monitor for repeated security violations from the same IP
   - Alert on unusual path patterns or attack attempts
   - Track file access patterns

2. **Performance Monitoring**
   - Monitor for DoS attempts via long paths or excessive requests
   - Track resource usage during file operations

### Maintenance

1. **Regular Updates**
   - Keep the server software updated
   - Review security logs regularly
   - Test security measures periodically

2. **Configuration Review**
   - Regularly audit root path configurations
   - Review write permissions and necessity
   - Validate filesystem permissions

## Known Limitations

### Platform-Specific Behavior

- Symbolic link resolution may behave differently on various filesystems
- Windows and Unix path handling differences are accounted for
- Some attacks may be filesystem-specific

### Performance Considerations

- Path validation adds computational overhead
- Real path resolution requires filesystem operations
- Logging overhead for security events

### Compatibility

- Requires Java NIO.2 features (Java 7+)
- Filesystem-dependent behavior for symbolic links
- Case sensitivity varies by filesystem

## Conclusion

The HTTP file server implements defense-in-depth security through:

1. **Multiple validation layers**
2. **Comprehensive attack prevention**
3. **Extensive testing coverage**
4. **Security logging and monitoring**
5. **Safe configuration practices**

