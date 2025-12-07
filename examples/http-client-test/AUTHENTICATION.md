# HTTP Client Authentication Guide

The Gumdrop HTTP client provides comprehensive authentication support with automatic challenge handling and retry logic. This guide demonstrates the various authentication schemes available.

## Supported Authentication Types

### ✅ **Basic Authentication (RFC 7617)**
Simple username/password authentication with base64 encoding.

```java
HTTPClient client = new HTTPClient("api.example.com", 443, true);
client.setBasicAuth("username", "password");

// Or manually
client.setAuthentication(new BasicAuthentication("username", "password"));
```

**Security:** Only use over HTTPS - credentials are easily decoded from base64.

### ✅ **Bearer Token Authentication (RFC 6750)**
Token-based authentication commonly used for API keys and OAuth access tokens.

```java
HTTPClient client = new HTTPClient("api.example.com", 443, true);
client.setBearerAuth("eyJhbGciOiJIUzI1NiJ9...");

// With expiration time
BearerAuthentication bearer = new BearerAuthentication("token", "JWT", expirationTime);
client.setAuthentication(bearer);
```

**Features:**
- Automatic token expiration handling
- Support for JWT and other token types
- Compatible with OAuth 2.0 access tokens

### ✅ **Digest Authentication (RFC 7616)**
Secure challenge-response authentication that never transmits passwords.

```java
HTTPClient client = new HTTPClient("api.example.com", 80);
client.setDigestAuth("username", "password");

// With specific algorithm
DigestAuthentication digest = new DigestAuthentication("user", "pass", "realm", "SHA-256");
client.setAuthentication(digest);
```

**Features:**
- Challenge-response mechanism with nonce
- Password never transmitted over network
- Support for MD5, SHA-256, SHA-512-256 algorithms
- Automatic retry after 401 challenges

### ✅ **OAuth 2.0 Authentication (RFC 6749)**
Full OAuth 2.0 support with automatic token refresh.

```java
// Simple access token
OAuthAuthentication oauth = new OAuthAuthentication("access_token");

// With refresh capability
OAuthAuthentication oauth = new OAuthAuthentication(
    "access_token", "refresh_token", expirationTime);

oauth.setTokenRefreshCallback(refreshToken -> {
    // Call your OAuth server to refresh the token
    return callTokenEndpoint(refreshToken);
});

client.setAuthentication(oauth);
```

**Features:**
- Access token management with expiration
- Automatic refresh token handling
- Support for multiple OAuth flows
- Extensible token refresh callbacks

## Authentication Manager Features

### 🚀 **Proactive Authentication**
Automatically applies authentication headers before sending requests.

```java
// Authentication is applied automatically to all requests
HTTPRequest request = new HTTPRequest("GET", "/protected");
stream.sendRequest(request); // Auth headers added automatically
```

### 🔄 **Automatic Challenge Handling**
Handles 401 (Unauthorized) and 407 (Proxy Auth Required) responses automatically.

```java
client.getAuthenticationManager().setChallengeHandlingEnabled(true);
client.getAuthenticationManager().setMaxRetries(3);
```

**Flow:**
1. Send request without authentication (or with wrong credentials)
2. Receive 401/407 challenge response
3. Parse challenge parameters (nonce, realm, etc.)
4. Generate authenticated request
5. Automatically retry request with authentication
6. Report success or failure to application

### 🎯 **Multiple Authentication Schemes**
Support for fallback authentication with multiple schemes.

```java
HTTPClient client = new HTTPClient("api.example.com", 443, true);

// Add multiple auth schemes in priority order
client.addAuthentication(new DigestAuthentication("user", "pass"));     // Try digest first
client.addAuthentication(new BasicAuthentication("user", "pass"));      // Fall back to basic
client.addAuthentication(new BearerAuthentication("backup-token"));     // Final fallback
```

## Usage Examples

### **Basic API Authentication**
```java
HTTPClient client = new HTTPClient("api.github.com", 443, true);
client.setBearerAuth("ghp_1234567890abcdef");

client.connect(new HTTPClientHandler() {
    @Override
    public void onStreamCreated(HTTPClientStream stream) {
        HTTPRequest request = new HTTPRequest("GET", "/user");
        stream.sendRequest(request);
        stream.completeRequest();
    }
    
    @Override
    public void onStreamResponse(HTTPClientStream stream, HTTPResponse response) {
        if (response.isSuccess()) {
            System.out.println("Authenticated successfully!");
        }
    }
    // ... other handlers
});
```

### **Digest Authentication with Challenge**
```java
HTTPClient client = new HTTPClient("secure.example.com", 80);
client.setDigestAuth("alice", "secret123");

// First request will get 401 challenge
// Client automatically handles challenge and retries
// Handler receives the final successful response

client.connect(handler);
// No manual challenge handling needed!
```

### **OAuth with Token Refresh**
```java
OAuthAuthentication oauth = new OAuthAuthentication(accessToken, refreshToken, expirationTime);

oauth.setTokenRefreshCallback(refreshToken -> {
    // Make HTTP request to OAuth server
    HTTPClient tokenClient = new HTTPClient("auth.example.com", 443, true);
    
    Map<String, String> params = new HashMap<>();
    params.put("grant_type", "refresh_token");
    params.put("refresh_token", refreshToken);
    
    // ... make token refresh request ...
    
    return new OAuthAuthentication.TokenResponse(
        newAccessToken, newRefreshToken, "Bearer", 3600, "read write"
    );
});

client.setAuthentication(oauth);
// Tokens are automatically refreshed when they expire
```

### **Custom Authentication Header**
```java
// For custom API authentication
Map<String, String> headers = new HashMap<>();
headers.put("X-API-Key", "your-api-key");
headers.put("X-Client-ID", "client-123");

HTTPRequest request = new HTTPRequest("GET", "/api/data", headers);
```

## Configuration Options

### **Authentication Manager Settings**
```java
HTTPAuthenticationManager authManager = client.getAuthenticationManager();

// Enable/disable automatic features
authManager.setProactiveAuthEnabled(true);        // Apply auth before sending
authManager.setChallengeHandlingEnabled(true);    // Handle 401 challenges
authManager.setMaxRetries(3);                     // Max retry attempts

// Check current configuration
System.out.println("Auth schemes: " + authManager.getAuthenticationCount());
System.out.println("Description: " + authManager.getDescription());
```

### **Per-Request Authentication**
```java
// Apply authentication to specific requests only
HTTPAuthentication auth = new BasicAuthentication("user", "pass");

Map<String, String> headers = new HashMap<>();
auth.applyAuthentication(headers);

HTTPRequest request = new HTTPRequest("GET", "/protected", headers);
```

## Security Best Practices

### 🔒 **Always Use HTTPS**
```java
// GOOD: Secure connection
HTTPClient client = new HTTPClient("api.example.com", 443, true);

// BAD: Credentials exposed over plaintext
HTTPClient client = new HTTPClient("api.example.com", 80, false);
```

### 🎫 **Token Management**
```java
// Check token expiration
BearerAuthentication bearer = (BearerAuthentication) auth;
if (bearer.isExpired()) {
    // Refresh or obtain new token
}

// Set reasonable expiration times
long oneHour = System.currentTimeMillis() + 3600000;
BearerAuthentication auth = new BearerAuthentication("token", "JWT", oneHour);
```

### 🔄 **Credential Rotation**
```java
// Update credentials periodically
client.clearAuthentication();
client.setBasicAuth(newUsername, newPassword);
```

### 🛡️ **Error Handling**
```java
@Override
public void onError(Exception e) {
    if (e.getMessage().contains("authentication")) {
        // Handle authentication failures
        // Maybe refresh credentials or notify user
    }
}

@Override
public void onStreamResponse(HTTPClientStream stream, HTTPResponse response) {
    if (response.getStatusCode() == 401) {
        // Authentication failed even after retries
        System.err.println("Authentication failed permanently");
    } else if (response.getStatusCode() == 403) {
        // Authenticated but not authorized
        System.err.println("Access forbidden - check permissions");
    }
}
```

## Testing Authentication

Run the authentication test suite:

```bash
cd examples/http-client-test
javac -cp ../../dist/server.jar AuthenticationTest.java
java -cp ../../dist/server.jar:. AuthenticationTest
```

**Test Coverage:**
- ✅ Basic authentication with httpbin.org
- ✅ Bearer token verification
- ✅ Digest authentication challenge handling
- ✅ Multiple authentication scheme fallback
- ✅ Automatic retry logic
- ✅ Error handling and timeouts

## Integration Examples

### **Spring Boot Application**
```java
@Service
public class ApiClient {
    private final HTTPClient httpClient;
    
    public ApiClient(@Value("${api.token}") String apiToken) {
        this.httpClient = new HTTPClient("api.example.com", 443, true);
        this.httpClient.setBearerAuth(apiToken);
    }
    
    public CompletableFuture<String> fetchUserData(String userId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        try {
            httpClient.connect(new HTTPClientHandler() {
                @Override
                public void onStreamCreated(HTTPClientStream stream) {
                    HTTPRequest request = new HTTPRequest("GET", "/users/" + userId);
                    stream.sendRequest(request);
                    stream.completeRequest();
                }
                
                @Override
                public void onStreamResponse(HTTPClientStream stream, HTTPResponse response) {
                    if (!response.isSuccess()) {
                        future.completeExceptionally(new ApiException("Request failed: " + response.getStatusCode()));
                    }
                }
                
                @Override
                public void onStreamData(HTTPClientStream stream, ByteBuffer data, boolean endStream) {
                    if (endStream) {
                        String jsonResponse = StandardCharsets.UTF_8.decode(data).toString();
                        future.complete(jsonResponse);
                    }
                }
                
                @Override
                public void onError(Exception e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        
        return future;
    }
}
```

The Gumdrop HTTP client authentication framework provides enterprise-grade security with minimal configuration! 🚀🔒
