# OAuth 2.0 Servlet Authentication Example

This example demonstrates how to configure a Gumdrop servlet container to authenticate 
requests using OAuth 2.0 Bearer tokens via the built-in `OAuthRealm`.

Gumdrop is a **resource server** here only: you run a separate authorization server.
Gumdrop does not issue tokens and does not implement PKCE or DPoP. See
[Scope and non-goals](../../web/security.html#oauth-non-goals) in the security guide.

## Overview

The `OAuthRealm` validates OAuth 2.0 access tokens by performing token introspection 
(RFC 7662) against your OAuth authorization server. It supports:

- Bearer token authentication via the `Authorization: Bearer <token>` header
- OAUTHBEARER SASL mechanism for mail protocols
- Scope-to-role mapping for authorization
- Optional token caching for performance

## Configuration

> **Gumdrop 3** wires servers via Java composition (`HttpServer` +
> `ServletRequestHandler`), not XML. See
> [web/configuration.html](../../web/configuration.html).

### 1. OAuth Properties File (`oauth.properties`)

```properties
# Required: OAuth authorization server URL
oauth.authorization.server.url=https://auth.example.com

# Required: Client credentials for token introspection
oauth.client.id=my-client-id
oauth.client.secret=my-client-secret

# Optional: Token introspection endpoint (default: /oauth/introspect)
oauth.token.introspection.endpoint=/oauth2/introspect

# Optional: Caching settings
oauth.cache.enabled=true
oauth.cache.ttl=300
oauth.cache.max.size=1000

# Optional: HTTP timeout in milliseconds (default: 5000)
oauth.http.timeout=5000

# Optional: Scope-to-role mappings
oauth.scope.mapping.admin=admin,superuser
oauth.scope.mapping.user=read,write
oauth.scope.mapping.readonly=read
```

### 2. Gumdrop Server Setup (Java composition)

`OAuthRealm` takes a `Properties` instance directly in its constructor —
load `oauth.properties` and hand it straight to the realm, then wire the
container and listener with `HttpServer.compose()`:

```java
Properties oauthConfig = new Properties();
try (InputStream in = Files.newInputStream(Path.of("config/oauth.properties"))) {
    oauthConfig.load(in);
}
OAuthRealm realm = new OAuthRealm(oauthConfig);

Container container = new Container();
container.addRealm("oauth", realm);
container.addContext(new Context(container, "/api", new File("webapps/api")));

HttpServer server = HttpServer.compose()
        .listener(new Http2Listener()
                .port(8443)
                .secure(true)
                // PEM files are the simplest identity (ant tls-certs makes some)
                .tls(TlsConfig.pem(Path.of("config/tls/cert.pem"), Path.of("config/tls/key.pem"))))
                // or a Java keystore:
                // .tls(TlsConfig.keystore(Path.of("config/keystore.p12"), "changeit")))
        .streamHandler(new ServletRequestHandler(container))
        .server();

Gumdrop gumdrop = Gumdrop.boot();
gumdrop.addServer(server);
gumdrop.join();
```

### 3. Web Application Security (`web.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<web-app xmlns="http://xmlns.jcp.org/xml/ns/javaee" version="4.0">
    
    <!-- Protected resources require authentication -->
    <security-constraint>
        <web-resource-collection>
            <web-resource-name>Protected API</web-resource-name>
            <url-pattern>/protected/*</url-pattern>
        </web-resource-collection>
        <auth-constraint>
            <role-name>user</role-name>
        </auth-constraint>
    </security-constraint>
    
    <!-- Admin resources require admin role -->
    <security-constraint>
        <web-resource-collection>
            <web-resource-name>Admin API</web-resource-name>
            <url-pattern>/admin/*</url-pattern>
        </web-resource-collection>
        <auth-constraint>
            <role-name>admin</role-name>
        </auth-constraint>
    </security-constraint>
    
    <!-- Security roles -->
    <security-role>
        <role-name>user</role-name>
    </security-role>
    <security-role>
        <role-name>admin</role-name>
    </security-role>
    
</web-app>
```

## Usage

### Making Authenticated Requests

Clients include the OAuth access token in the `Authorization` header:

```bash
curl -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..." \
     https://localhost:8443/api/protected/resource
```

### Token Introspection Flow

When a request arrives with a Bearer token:

1. Gumdrop extracts the token from the `Authorization` header
2. `OAuthRealm` sends an introspection request to the OAuth server
3. The OAuth server responds with token validity, username, and scopes
4. If valid, the request proceeds; if not, a 401 Unauthorized is returned
5. For protected resources, scopes are mapped to roles for authorization

### Token Introspection Request

```http
POST /oauth2/introspect HTTP/1.1
Host: auth.example.com
Authorization: Basic <base64(client_id:client_secret)>
Content-Type: application/x-www-form-urlencoded

token=eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...&token_type_hint=access_token
```

### Token Introspection Response

```json
{
  "active": true,
  "username": "john.doe",
  "sub": "user-123",
  "scope": "read write admin",
  "exp": 1735689600
}
```

## Accessing User Information in Servlets

```java
@WebServlet("/protected/profile")
public class ProfileServlet extends HttpServlet {
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        // Get authenticated username
        String username = req.getRemoteUser();
        
        // Check role membership
        boolean isAdmin = req.isUserInRole("admin");
        
        resp.setContentType("application/json");
        resp.getWriter().println("{\"user\": \"" + username + "\", \"admin\": " + isAdmin + "}");
    }
}
```

## References

- [RFC 7662 - OAuth 2.0 Token Introspection](https://www.rfc-editor.org/rfc/rfc7662)
- [RFC 6749 - OAuth 2.0 Authorization Framework](https://www.rfc-editor.org/rfc/rfc6749)
- [RFC 6750 - OAuth 2.0 Bearer Token Usage](https://www.rfc-editor.org/rfc/rfc6750)
