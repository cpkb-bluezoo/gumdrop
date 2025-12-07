# Gumdrop Coding Standards

This document defines the coding standards and conventions for the Gumdrop project. All contributions should adhere to these guidelines.

## Java Version Compatibility

**Gumdrop requires Java 8 (1.8) and must not use language features from later versions.**

This requirement exists to ensure broad compatibility with:
- Legacy enterprise Java runtimes still in production use
- Embedded systems and appliances with older JVMs
- Organizations with strict upgrade policies requiring long validation cycles
- Environments where upgrading the JVM is impractical or impossible

This is enforced at compile time via `source` and `target` settings in `build.xml`.

**Prohibited features include (but are not limited to):**
- `var` keyword (Java 10+)
- Switch expressions (Java 12+)
- Text blocks (Java 15+)
- Records (Java 16+)
- Pattern matching (Java 16+)
- Sealed classes (Java 17+)
- Virtual threads (Java 21+)

**Additionally, the following Java 8 features are prohibited by project policy** (see sections below):
- Lambda expressions
- Method references
- Streams API (`java.util.stream`)
- `Optional<T>`
- `CompletableFuture` and `Future`
- Default methods in interfaces (except when implementing J2EE APIs that require them)

## File Headers

All source files must include a proper file header containing:
- Filename
- Copyright owner and date (created/modified year)
- Copyright notice with license reference

Example:
```java
/*
 * ExampleClass.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */
```

## Documentation

- All Java classes must have proper Javadoc with `@author` tag
- Document the intent and purpose, not the obvious mechanics
- Don't write comments that simply restate what the code does

**Good:**
```java
// Ensure session is replicated before response completes
cluster.replicate(context, session);
```

**Bad:**
```java
// Call replicate method on cluster with context and session
cluster.replicate(context, session);
```

## Control Flow

### Conditional Blocks

All conditional blocks must be properly delimited with curly braces and indented, even for single-line blocks. No short-form statements on the same line after `if`.

**Good:**
```java
if (value == null) {
    return;
}

if (count > 0) {
    processItems();
}
```

**Bad:**
```java
if (value == null) return;

if (count > 0) processItems();
```

This prevents a common source of programmer error when modifying code later.

## Imports

- Use proper import statements for all classes
- No fully qualified class names in code unless there is a genuine name clash
- Organize imports logically (java.*, javax.*, then project packages)

**Good:**
```java
import java.util.List;
import java.util.Map;

public void process(List<String> items, Map<String, Object> context) {
```

**Bad:**
```java
public void process(java.util.List<String> items, java.util.Map<String, Object> context) {
```

## Annotations

- Only `@Override` and `@Deprecated` are permitted in main source code
- Other annotations may be used in:
  - Example code demonstrating annotation support (e.g., `@WebServlet`)
  - JUnit tests (e.g., `@Test`, `@Before`)
  - Code specifically designed to process annotations

## Language Features to Avoid

### No Lambdas

Use traditional anonymous classes or explicit method implementations instead.

**Good:**
```java
executor.submit(new Runnable() {
    @Override
    public void run() {
        processTask();
    }
});
```

**Bad:**
```java
executor.submit(() -> processTask());
```

### No Functional Paradigm

Use clear, traditional procedural code. Avoid streams, functional interfaces, and method references.

**Good:**
```java
List<String> result = new ArrayList<>();
for (Item item : items) {
    if (item.isValid()) {
        result.add(item.getName());
    }
}
```

**Bad:**
```java
List<String> result = items.stream()
    .filter(Item::isValid)
    .map(Item::getName)
    .collect(Collectors.toList());
```

### No Method Chaining

Avoid chaining method calls (except for builders, used sparingly). Write each operation as a separate statement for clarity.

**Good:**
```java
StringBuilder sb = new StringBuilder();
sb.append("Hello");
sb.append(" ");
sb.append("World");
String result = sb.toString();
```

**Acceptable (builder pattern):**
```java
DNSMessage response = new DNSMessage.Builder()
    .id(query.getId())
    .flags(FLAG_QR | FLAG_RA)
    .build();
```

**Bad:**
```java
String result = new StringBuilder().append("Hello").append(" ").append("World").toString();
```

### No Inline Function Calls as Parameters

Avoid calling functions inline as parameters. Assign to variables first for clarity.

**Exception:** The `++` operator may be used inline.

**Good:**
```java
String name = user.getName();
String formatted = formatter.format(name);
logger.info(formatted);
```

**Less ideal:**
```java
logger.info(formatter.format(user.getName()));
```

**Acceptable (increment operator):**
```java
array[index++] = value;
```

### No Future/Promise

Avoid `Future`, `CompletableFuture`, and similar constructs. Use traditional callback patterns instead, similar to SAX or JavaScript XMLHttpRequest.

**Good:**
```java
public interface ResponseCallback {
    void onSuccess(Response response);
    void onError(Exception error);
}

public void sendRequest(Request request, ResponseCallback callback) {
    // Implementation calls callback.onSuccess() or callback.onError()
}
```

**Bad:**
```java
public Future<Response> sendRequest(Request request) {
    return executor.submit(() -> doRequest(request));
}
```

### No Regular Expressions

Avoid `java.util.regex` patterns. Use traditional string parsing methods instead.

**Good:**
```java
int colonIndex = header.indexOf(':');
if (colonIndex > 0) {
    String name = header.substring(0, colonIndex).trim();
    String value = header.substring(colonIndex + 1).trim();
}
```

**Bad:**
```java
Pattern pattern = Pattern.compile("^([^:]+):\\s*(.*)$");
Matcher matcher = pattern.matcher(header);
if (matcher.matches()) {
    String name = matcher.group(1);
    String value = matcher.group(2);
}
```

## Concurrency

### Thread Pools

Use `ExecutorService` and `ScheduledExecutorService` for thread pool management. Create threads using `ThreadFactory` implementations with descriptive names.

**Good:**
```java
private static final ExecutorService EXECUTOR = 
    Executors.newCachedThreadPool(new WorkerThreadFactory());

private static class WorkerThreadFactory implements ThreadFactory {
    private final AtomicInteger count = new AtomicInteger(0);
    
    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "worker-" + count.incrementAndGet());
        t.setDaemon(true);
        return t;
    }
}
```

### Callbacks Instead of Futures

Use callback interfaces for asynchronous operations. This is consistent with the prohibition on `Future` and `CompletableFuture`.

**Good:**
```java
public interface CompilationCallback {
    void onSuccess(Class<?> compiledClass);
    void onError(Exception error);
}

public void compileAsync(String source, CompilationCallback callback) {
    executor.execute(new Runnable() {
        @Override
        public void run() {
            try {
                Class<?> result = compile(source);
                callback.onSuccess(result);
            } catch (Exception e) {
                callback.onError(e);
            }
        }
    });
}
```

## Localisation

All strings that will be user facing in Gumdrop use ResourceBundle for
localisation. Packages will have a ResourceBundle called L10N. We maintain
translations for English, French, Spanish and German.

All code additions that add user facing strings including most exceptions
must use the localisation system.

## Telemetry

Gumdrop uses its own telemetry system which is compatible with OpenTelemetry
and uses the same concepts. When adding new features consider if they
require a new span within the current trace. When implementing, if there are
any error conditions ensure that they are logged into the trace.

## Summary

The goal of these standards is to produce code that is:
- **Clear**: Easy to read and understand at a glance
- **Predictable**: Follows consistent patterns throughout
- **Maintainable**: Easy to modify without introducing bugs
- **Traditional**: Uses well-understood Java idioms
- **Compatible**: Runs on Java 8 and later without modification

When in doubt, prefer clarity over cleverness.

