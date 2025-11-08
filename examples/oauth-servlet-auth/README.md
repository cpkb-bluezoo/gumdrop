# OAuth 2.0 Servlet Authentication Example

This example demonstrates how to configure OAuth 2.0 authentication in a Gumdrop servlet application. It shows complete integration with OAuth 2.0 authorization servers using token introspection (RFC 7662).

## Overview

This example implements:
- ✅ **OAuth 2.0 Token Validation** via token introspection
- ✅ **Role-based Authorization** using OAuth scopes  
- ✅ **Event-driven JSON Parsing** using cpkb-bluezoo jsonparser
- ✅ **Gumdrop HTTP Client** for OAuth server communication
- ✅ **Automatic Authentication** for protected resources
- ✅ **Proper Error Handling** and security responses
- ✅ **Configurable OAuth Provider** support

## Architecture

```
Client Request with Bearer Token
         ↓
Gumdrop HTTP Server
         ↓
ServletAuthenticationProvider
         ↓
OAuthRealm.validateOAuthToken()
         ↓ 
OAuth Authorization Server
    (Token Introspection)
         ↓
Success: Create ServletPrincipal
Failure: Send 401 Unauthorized
```

## Quick Start

### 1. Prerequisites

- Gumdrop server with servlet support
- OAuth 2.0 authorization server (e.g., Keycloak, Auth0, etc.)
- cpkb-bluezoo jsonparser library (jsonparser.jar in classpath)
- Java 8+ (uses Gumdrop HTTP client, not Java 11 HTTP client)

### 2. Configuration

Edit `oauth-config.properties`:

```properties
# Your OAuth 2.0 Authorization Server
oauth.authorization.server.url=https://your-auth-server.com
oauth.client.id=your-client-id
oauth.client.secret=your-client-secret

# Token introspection endpoint (RFC 7662)
oauth.token.introspection.endpoint=/oauth/introspect

# Scope to role mappings
oauth.scope.mapping.user=read,write
oauth.scope.mapping.admin=admin
```

### 3. Deploy and Test

1. **Build the application:**
   ```bash
   javac -cp "gumdrop.jar:servlet-api.jar:jsonparser.jar" src/main/java/com/example/oauth/*.java
   ```

2. **Deploy web.xml and classes to your Gumdrop server**

3. **Test with OAuth token:**
   ```bash
   # Get a token from your OAuth server first
   TOKEN="eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
   
   # Test protected endpoint
   curl -H "Authorization: Bearer $TOKEN" \
        http://localhost:8080/oauth-example/api/test
   ```

4. **Expected response:**
   ```json
   {
     "authenticated": true,
     "username": "john.doe",
     "isUser": true,
     "isAdmin": false,
     "scopes": ["read", "write"]
   }
   ```

## Step-by-Step Setup Guide

### Step 1: Web Application Configuration

The `web.xml` configures OAuth authentication and security constraints:

```xml
<!-- Key configuration points: -->
<login-config>
    <auth-method>OAUTH</auth-method>    <!-- Use OAuth authentication -->
    <realm-name>MyOAuthRealm</realm-name>  <!-- Must match realm name in code -->
</login-config>

<security-constraint>
    <url-pattern>/api/*</url-pattern>   <!-- Protect API endpoints -->
    <auth-constraint>
        <role-name>user</role-name>     <!-- Require 'user' role -->
    </auth-constraint>
</security-constraint>
```

### Step 2: OAuth Realm Implementation

The `OAuthRealm` class handles token validation:

**Key Methods:**
- `validateOAuthToken()` - Performs RFC 7662 token introspection
- `isMember()` - Maps OAuth scopes to servlet roles
- `passwordMatch()` - Returns false (OAuth doesn't use passwords)

**Token Introspection Flow:**
1. Receive Bearer token from Authorization header
2. Use Gumdrop HTTP client to POST to OAuth server's `/introspect` endpoint
3. Parse JSON response using event-driven cpkb-bluezoo jsonparser
4. Extract username, scopes, and expiration via streaming JSON handler
5. Return `TokenValidationResult`

**Key Technical Features:**
- **Gumdrop HTTP Client**: Uses the same HTTP client framework for OAuth communication
- **Event-driven JSON**: Streaming JSON parsing without loading entire response into memory
- **Asynchronous Design**: HTTP client operations wrapped in synchronous interface for Realm compatibility

### Step 3: Configuration and Initialization

The `OAuth2ConfigurationListener` sets up the realm:

1. **Load configuration** from properties file
2. **Create OAuthRealm** with server credentials
3. **Register realm** with Gumdrop context
4. **Configure scope mappings** for authorization

### Step 4: Testing and Validation

Use the included test servlet to verify OAuth integration:

- **Authentication Test**: Verify tokens are validated
- **Authorization Test**: Check role-based access control
- **Error Handling**: Test invalid/expired tokens

## Configuration Options

### OAuth Server Settings

| Property | Description | Example |
|----------|-------------|---------|
| `oauth.authorization.server.url` | Base URL of OAuth server | `https://auth.example.com` |
| `oauth.client.id` | OAuth client identifier | `webapp-client` |
| `oauth.client.secret` | OAuth client secret | `secret123` |
| `oauth.token.introspection.endpoint` | Introspection path | `/oauth/introspect` |

### Role Mapping

Map OAuth scopes to servlet roles:

```properties
# Users with 'read' or 'write' scope get 'user' role
oauth.scope.mapping.user=read,write

# Users with 'admin' scope get 'admin' role  
oauth.scope.mapping.admin=admin

# Multiple scopes can map to same role
oauth.scope.mapping.moderator=moderate,delete
```

## Security Considerations

### ✅ **Best Practices Implemented**

1. **Token Introspection**: Validates tokens with authorization server in real-time
2. **Secure Storage**: Client credentials loaded from configuration, not hardcoded
3. **Role Separation**: OAuth scopes mapped to application roles
4. **Error Handling**: Proper HTTP status codes (401, 403, 500)
5. **Logging**: Security events logged for monitoring

### ⚠️  **Additional Security Recommendations**

1. **HTTPS Only**: Always use HTTPS in production
2. **Token Caching**: Consider caching introspection results with short TTL
3. **Rate Limiting**: Implement rate limits on introspection calls
4. **Scope Validation**: Validate required scopes for specific operations
5. **Audit Logging**: Log all authentication and authorization decisions

## Common OAuth Providers

### Keycloak
```properties
oauth.authorization.server.url=https://keycloak.example.com/auth/realms/myrealm
oauth.token.introspection.endpoint=/protocol/openid-connect/token/introspect
```

### Auth0
```properties
oauth.authorization.server.url=https://yourapp.auth0.com
oauth.token.introspection.endpoint=/oauth/introspect
```

### Okta
```properties
oauth.authorization.server.url=https://dev-123456.okta.com/oauth2/default
oauth.token.introspection.endpoint=/v1/introspect
```

## Troubleshooting

### Common Issues

**401 Unauthorized**
- Check token is valid and not expired
- Verify client credentials in config
- Check authorization server logs

**403 Forbidden** 
- User authenticated but lacks required role
- Check scope to role mappings
- Verify security constraints in web.xml

**500 Internal Server Error**
- OAuth server unreachable
- Invalid client credentials
- Configuration errors

### Debug Logging

Enable debug logging to troubleshoot:

```properties
# In logging.properties
com.example.oauth.level=FINE
org.bluezoo.gumdrop.servlet.level=FINE
```

## Advanced Features

### Custom Token Validation

Extend `OAuthRealm` for custom validation logic:

```java
public class CustomOAuthRealm extends OAuthRealm {
    
    @Override
    public TokenValidationResult validateOAuthToken(String token) {
        // Custom validation (e.g., JWT verification)
        TokenValidationResult result = super.validateOAuthToken(token);
        
        if (result.valid) {
            // Additional custom checks
            return performCustomValidation(result, token);
        }
        
        return result;
    }
}
```

### Scope-based Method Security

Use OAuth scopes for fine-grained authorization:

```java
@WebServlet("/api/admin")
public class AdminServlet extends HttpServlet {
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        // Check specific scope
        if (!hasRequiredScope(req, "admin")) {
            resp.sendError(403, "Admin scope required");
            return;
        }
        
        // Admin operation
    }
}
```

## Files in this Example

- `README.md` - This documentation
- `web.xml` - Servlet configuration with OAuth authentication
- `oauth-config.properties` - OAuth server configuration
- `src/main/java/com/example/oauth/OAuthRealm.java` - OAuth token validation
- `src/main/java/com/example/oauth/OAuth2ConfigurationListener.java` - Setup and initialization
- `src/main/java/com/example/oauth/OAuthTestServlet.java` - Test endpoint
- `src/main/java/com/example/oauth/ScopeUtils.java` - Utility methods for scope checking

## Further Reading

- [RFC 6749: The OAuth 2.0 Authorization Framework](https://tools.ietf.org/html/rfc6749)
- [RFC 7662: OAuth 2.0 Token Introspection](https://tools.ietf.org/html/rfc7662)  
- [RFC 6750: The OAuth 2.0 Authorization Framework: Bearer Token Usage](https://tools.ietf.org/html/rfc6750)
- [Gumdrop HTTP Server Authentication Guide](../README.md)
