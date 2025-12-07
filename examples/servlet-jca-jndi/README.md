# JCA and JNDI Integration Example

This example demonstrates comprehensive **JCA (Java Connector Architecture)** and **JNDI (Java Naming and Directory Interface)** support in Gumdrop servlet container.

## Overview

The implementation provides enterprise-grade resource management including:

- **Resource Injection** - Automatic `@Resource` annotation processing
- **JNDI Context** - Full `java:comp/env/` namespace support  
- **JCA Integration** - Connection factories and administered objects
- **Connection Pooling** - Efficient database resource management
- **Transaction Support** - Basic JTA integration (future enhancement)

## Supported Resource Types

### JDBC DataSources
```xml
<data-source>
    <name>jdbc/AppDatabase</name>
    <class-name>org.postgresql.ds.PGSimpleDataSource</class-name>
    <server-name>localhost</server-name>
    <port-number>5432</port-number>
    <database-name>myapp</database-name>
    <user>appuser</user>
    <password>password</password>
    <initial-pool-size>5</initial-pool-size>
    <max-pool-size>20</max-pool-size>
</data-source>
```

### JCA Connection Factories
```xml
<connection-factory>
    <jndi-name>jca/ERPConnector</jndi-name>
    <connection-definition-id>ERPConnection</connection-definition-id>
    <property>
        <name>ServerUrl</name>
        <value>erp.company.com</value>
    </property>
    <property>
        <name>Port</name>
        <value>8080</value>
    </property>
</connection-factory>
```

### JCA Administered Objects
```xml
<administered-object>
    <jndi-name>jca/ProcessingQueue</jndi-name>
    <administered-object-interface>javax.jms.Queue</administered-object-interface>
    <administered-object-class>com.vendor.QueueImpl</administered-object-class>
    <property>
        <name>QueueName</name>
        <value>ProcessingQueue</value>
    </property>
</administered-object>
```

### JavaMail Sessions
```xml
<mail-session>
    <name>mail/AppMailSession</name>
    <host>smtp.company.com</host>
    <port>587</port>
    <user>app@company.com</user>
    <password>mailpassword</password>
    <property>
        <name>mail.smtp.auth</name>
        <value>true</value>
    </property>
    <property>
        <name>mail.smtp.starttls.enable</name>
        <value>true</value>
    </property>
</mail-session>
```

### JMS Resources
```xml
<jms-connection-factory>
    <name>jms/AppConnectionFactory</name>
    <interface-name>javax.jms.ConnectionFactory</interface-name>
    <class-name>org.apache.activemq.ActiveMQConnectionFactory</class-name>
    <property>
        <name>brokerURL</name>
        <value>tcp://localhost:61616</value>
    </property>
</jms-connection-factory>

<jms-destination>
    <name>jms/AppQueue</name>
    <interface-name>javax.jms.Queue</interface-name>
    <class-name>org.apache.activemq.command.ActiveMQQueue</class-name>
    <property>
        <name>PhysicalName</name>
        <value>APP.PROCESSING.QUEUE</value>
    </property>
</jms-destination>
```

## Usage Patterns

### Resource Injection
```java
@WebServlet("/app")
public class ApplicationServlet extends HttpServlet {
    
    // Field injection
    @Resource(name = "jdbc/AppDatabase")
    private DataSource database;
    
    @Resource(name = "jca/ERPConnector")
    private ConnectionFactory erpConnector;
    
    @Resource(name = "mail/AppMailSession")
    private Session mailSession;
    
    // Method injection
    private ConnectionFactory jmsFactory;
    
    @Resource(name = "jms/AppConnectionFactory")
    public void setJmsConnectionFactory(ConnectionFactory jmsFactory) {
        this.jmsFactory = jmsFactory;
    }
}
```

### Manual JNDI Lookup
```java
public void businessMethod() throws NamingException, SQLException {
    InitialContext ctx = new InitialContext();
    
    // Lookup DataSource
    DataSource ds = (DataSource) ctx.lookup("java:comp/env/jdbc/AppDatabase");
    
    // Lookup JCA ConnectionFactory
    ConnectionFactory cf = (ConnectionFactory) ctx.lookup("java:comp/env/jca/ERPConnector");
    
    // Use resources...
    try (Connection dbConn = ds.getConnection()) {
        // Database operations
    }
    
    try (javax.resource.cci.Connection erpConn = cf.getConnection()) {
        // External system integration
    }
}
```

### Database Access Pattern
```java
@Resource(name = "jdbc/AppDatabase")
private DataSource database;

public List<User> getActiveUsers() throws SQLException {
    List<User> users = new ArrayList<>();
    
    try (Connection conn = database.getConnection()) {
        PreparedStatement stmt = conn.prepareStatement(
            "SELECT id, username, email FROM users WHERE active = ?");
        stmt.setBoolean(1, true);
        
        ResultSet rs = stmt.executeQuery();
        while (rs.next()) {
            User user = new User();
            user.setId(rs.getLong("id"));
            user.setUsername(rs.getString("username"));
            user.setEmail(rs.getString("email"));
            users.add(user);
        }
    }
    
    return users;
}
```

### JCA Integration Pattern
```java
@Resource(name = "jca/ERPConnector")
private ConnectionFactory erpConnector;

public void processERPData(String customerID) throws ResourceException {
    try (javax.resource.cci.Connection conn = erpConnector.getConnection()) {
        Interaction interaction = conn.createInteraction();
        
        // Create input record
        RecordFactory recordFactory = erpConnector.getRecordFactory();
        MappedRecord input = recordFactory.createMappedRecord("CustomerLookup");
        input.put("customerId", customerID);
        
        // Execute business function
        InteractionSpec spec = new CustomerLookupSpec();
        Record output = interaction.execute(spec, input);
        
        // Process results...
    }
}
```

## Implementation Details

### JNDI Context Structure
```
java:comp/env/                    - Component environment root
├── jdbc/                        - JDBC DataSources
│   └── AppDatabase              - Application database  
├── jca/                         - JCA resources
│   ├── ERPConnector             - ERP connection factory
│   └── ProcessingQueue          - JCA administered object
├── jms/                         - JMS resources
│   ├── AppConnectionFactory     - JMS connection factory
│   └── AppQueue                 - JMS destination
└── mail/                        - JavaMail sessions
    └── AppMailSession           - Mail session
```

### Resource Injection Lifecycle
1. **Servlet Creation** - Container creates servlet instance
2. **Annotation Scanning** - `ResourceInjector` scans for `@Resource` annotations
3. **JNDI Lookup** - Resources looked up from `java:comp/env/` context
4. **Type Validation** - Ensures injected resources match field/method types
5. **Injection** - Fields set or methods called with resource instances

### JCA Connection Lifecycle
1. **Factory Lookup** - `ConnectionFactory` retrieved from JNDI
2. **Connection Request** - Application calls `getConnection()`
3. **Pool Management** - Container provides pooled connection
4. **Business Logic** - Application uses connection for operations
5. **Connection Close** - Resource returned to pool for reuse

## Error Handling

### Resource Not Found
```java
@Resource(name = "jdbc/NonExistentDB")
private DataSource database; // Will be null if resource not found

public void checkResource() {
    if (database == null) {
        // Handle missing resource
        log.warning("Database resource not available");
        return;
    }
    
    // Use resource normally
}
```

### JNDI Lookup Failures
```java
try {
    InitialContext ctx = new InitialContext();
    DataSource ds = (DataSource) ctx.lookup("java:comp/env/jdbc/AppDatabase");
} catch (NamingException e) {
    log.severe("JNDI lookup failed: " + e.getMessage());
    throw new ServletException("Required resource not available", e);
}
```

### JCA Connection Errors
```java
try (javax.resource.cci.Connection conn = erpConnector.getConnection()) {
    // Use connection
} catch (ResourceException e) {
    log.severe("ERP connection failed: " + e.getMessage());
    // Implement retry logic or fallback
}
```

## Performance Considerations

### Connection Pooling Configuration
- **Initial Pool Size** - Start with reasonable number of connections
- **Maximum Pool Size** - Balance between resource usage and scalability  
- **Connection Validation** - Test connections before use
- **Idle Timeout** - Remove unused connections from pool

### Resource Injection Overhead
- Injection occurs **once** during servlet initialization
- **Zero runtime overhead** after injection complete
- Field access is **direct reference** (no lookup cost)

### JNDI Lookup Performance
- **Manual lookups** have small performance cost
- Consider **caching** frequently accessed resources
- Use **resource injection** for better performance

## Security Considerations

### Resource Access Control
- Resources isolated per **web application**
- **JNDI namespace** prevents cross-application access
- **Container-managed** authentication and authorization

### Connection Security
- **Database credentials** managed by container
- **JCA security** handled by resource adapters
- **SSL/TLS** configured at resource level

## Running the Example

1. **Deploy Example** - Copy `JCAExampleServlet.java` to your webapp
2. **Configure Resources** - Add resource definitions to `web.xml`
3. **Access Demos** - Navigate to `/jca-demo` in your browser
4. **Explore Features** - Try different demo modes:
   - `?demo=injection` - Resource injection demonstration
   - `?demo=jndi` - Manual JNDI lookup examples
   - `?demo=jca` - JCA connector usage
   - `demo=database` - Database access patterns

This comprehensive example showcases enterprise-grade resource management capabilities in Gumdrop servlet container, demonstrating both the power and simplicity of JCA and JNDI integration.
