/*
 * InMemoryJavaCompiler.java
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

package org.bluezoo.gumdrop.servlet.jsp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.security.SecureClassLoader;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles Java source code entirely in memory, eliminating the need for
 * temporary files during JSP compilation.
 * 
 * <p>This class provides:</p>
 * <ul>
 *   <li>In-memory source file representation</li>
 *   <li>In-memory bytecode output</li>
 *   <li>Custom classloader for loading compiled classes</li>
 *   <li>Detailed compilation error reporting</li>
 * </ul>
 * 
 * <p>Usage:</p>
 * <pre>
 * InMemoryJavaCompiler compiler = new InMemoryJavaCompiler(parentClassLoader);
 * 
 * CompilationResult result = compiler.compile("MyClass", sourceCode);
 * if (result.isSuccess()) {
 *     Class&lt;?&gt; clazz = result.getCompiledClass();
 * } else {
 *     List&lt;CompilationError&gt; errors = result.getErrors();
 * }
 * </pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class InMemoryJavaCompiler {

    private static final Logger LOGGER = Logger.getLogger(InMemoryJavaCompiler.class.getName());
    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.jsp.L10N");
    
    private final JavaCompiler compiler;
    private final ClassLoader parentClassLoader;
    private final List<String> compilerOptions;
    
    /**
     * Creates a new in-memory compiler with the specified parent classloader.
     * 
     * @param parentClassLoader the parent classloader for loading compiled classes
     * @throws IllegalStateException if no Java compiler is available
     */
    public InMemoryJavaCompiler(ClassLoader parentClassLoader) {
        this.compiler = ToolProvider.getSystemJavaCompiler();
        if (this.compiler == null) {
            throw new IllegalStateException(L10N.getString("compiler.no_compiler"));
        }
        this.parentClassLoader = parentClassLoader;
        
        // Default compiler options
        this.compilerOptions = new ArrayList<String>();
        this.compilerOptions.add("--release");
        this.compilerOptions.add("17");
        this.compilerOptions.add("-g"); // Include debug info
    }
    
    /**
     * Creates a new in-memory compiler with the context classloader as parent.
     * 
     * @throws IllegalStateException if no Java compiler is available
     */
    public InMemoryJavaCompiler() {
        this(Thread.currentThread().getContextClassLoader());
    }
    
    /**
     * Compiles Java source code and returns the result.
     * 
     * @param className the fully qualified class name
     * @param sourceCode the Java source code
     * @return the compilation result
     */
    public CompilationResult compile(String className, String sourceCode) {
        return compile(className, sourceCode, null);
    }
    
    /**
     * Compiles Java source code with JSP source mapping for error reporting.
     * 
     * @param className the fully qualified class name
     * @param sourceCode the Java source code
     * @param lineMapping optional mapping from Java line numbers to JSP locations
     * @return the compilation result
     */
    public CompilationResult compile(String className, String sourceCode, 
                                     Map<Integer, JSPSourceLocation> lineMapping) {
        
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        
        // Create source file object
        InMemorySourceFile sourceFile = new InMemorySourceFile(className, sourceCode);
        
        // Create file manager that stores output in memory
        StandardJavaFileManager standardFileManager = 
            compiler.getStandardFileManager(diagnostics, null, null);
        InMemoryFileManager fileManager = 
            new InMemoryFileManager(standardFileManager, parentClassLoader);
        
        // Create compilation task
        StringWriter compilerOutput = new StringWriter();
        List<JavaFileObject> compilationUnits = new ArrayList<JavaFileObject>();
        compilationUnits.add(sourceFile);
        
        JavaCompiler.CompilationTask task = compiler.getTask(
            compilerOutput,
            fileManager,
            diagnostics,
            compilerOptions,
            null,
            compilationUnits
        );
        
        // Compile
        boolean success = task.call();
        
        if (success) {
            try {
                Class<?> compiledClass = fileManager.getClassLoader().loadClass(className);
                return new CompilationResult(compiledClass);
            } catch (ClassNotFoundException e) {
                List<CompilationError> errors = new ArrayList<CompilationError>();
                String errorMsg = MessageFormat.format(
                    L10N.getString("compiler.load_failed"), e.getMessage());
                errors.add(new CompilationError(0, 0, errorMsg, null));
                return new CompilationResult(errors);
            }
        } else {
            // Collect errors with optional JSP mapping
            List<CompilationError> errors = new ArrayList<CompilationError>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                int javaLine = (int) diagnostic.getLineNumber();
                int javaColumn = (int) diagnostic.getColumnNumber();
                String message = diagnostic.getMessage(null);
                
                JSPSourceLocation jspLocation = null;
                if (lineMapping != null) {
                    jspLocation = lineMapping.get(javaLine);
                }
                
                errors.add(new CompilationError(javaLine, javaColumn, message, jspLocation));
                
                if (LOGGER.isLoggable(Level.FINE)) {
                    if (jspLocation != null) {
                        String logMsg = MessageFormat.format(
                            L10N.getString("compiler.error_at_jsp"),
                            jspLocation.getJspFile(), jspLocation.getJspLine(), message);
                        LOGGER.fine(logMsg);
                    } else {
                        String logMsg = MessageFormat.format(
                            L10N.getString("compiler.error_at_line"), javaLine, message);
                        LOGGER.fine(logMsg);
                    }
                }
            }
            return new CompilationResult(errors);
        }
    }
    
    /**
     * Represents a Java source file stored in memory.
     */
    private static class InMemorySourceFile extends SimpleJavaFileObject {
        
        private final String sourceCode;
        
        InMemorySourceFile(String className, String sourceCode) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                  Kind.SOURCE);
            this.sourceCode = sourceCode;
        }
        
        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return sourceCode;
        }
    }
    
    /**
     * Represents a compiled class file stored in memory.
     */
    private static class InMemoryClassFile extends SimpleJavaFileObject {
        
        private final ByteArrayOutputStream bytecode = new ByteArrayOutputStream();
        
        InMemoryClassFile(String className) {
            super(URI.create("mem:///" + className.replace('.', '/') + Kind.CLASS.extension),
                  Kind.CLASS);
        }
        
        @Override
        public OutputStream openOutputStream() {
            return bytecode;
        }
        
        byte[] getBytes() {
            return bytecode.toByteArray();
        }
    }
    
    /**
     * File manager that stores compiled classes in memory.
     */
    private static class InMemoryFileManager 
            extends ForwardingJavaFileManager<StandardJavaFileManager> {
        
        private final Map<String, InMemoryClassFile> classFiles = new HashMap<String, InMemoryClassFile>();
        private final ClassLoader parentClassLoader;
        
        InMemoryFileManager(StandardJavaFileManager fileManager, ClassLoader parentClassLoader) {
            super(fileManager);
            this.parentClassLoader = parentClassLoader;
        }
        
        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                   JavaFileObject.Kind kind, FileObject sibling) {
            InMemoryClassFile classFile = new InMemoryClassFile(className);
            classFiles.put(className, classFile);
            return classFile;
        }
        
        ClassLoader getClassLoader() {
            return new InMemoryClassLoader(parentClassLoader, classFiles);
        }
    }
    
    /**
     * Classloader that loads classes from in-memory bytecode.
     */
    private static class InMemoryClassLoader extends SecureClassLoader {
        
        private final Map<String, InMemoryClassFile> classFiles;
        
        InMemoryClassLoader(ClassLoader parent, Map<String, InMemoryClassFile> classFiles) {
            super(parent);
            this.classFiles = classFiles;
        }
        
        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            InMemoryClassFile classFile = classFiles.get(name);
            if (classFile != null) {
                byte[] bytecode = classFile.getBytes();
                return defineClass(name, bytecode, 0, bytecode.length);
            }
            throw new ClassNotFoundException(name);
        }
    }
    
    /**
     * Result of a compilation operation.
     */
    public static class CompilationResult {
        
        private final Class<?> compiledClass;
        private final List<CompilationError> errors;
        
        CompilationResult(Class<?> compiledClass) {
            this.compiledClass = compiledClass;
            this.errors = Collections.emptyList();
        }
        
        CompilationResult(List<CompilationError> errors) {
            this.compiledClass = null;
            this.errors = errors;
        }
        
        /**
         * Returns true if compilation was successful.
         */
        public boolean isSuccess() {
            return compiledClass != null;
        }
        
        /**
         * Returns the compiled class, or null if compilation failed.
         */
        public Class<?> getCompiledClass() {
            return compiledClass;
        }
        
        /**
         * Returns compilation errors, or an empty list if successful.
         */
        public List<CompilationError> getErrors() {
            return errors;
        }
    }
    
    /**
     * Represents a compilation error with optional JSP source mapping.
     */
    public static class CompilationError {
        
        private final int javaLine;
        private final int javaColumn;
        private final String message;
        private final JSPSourceLocation jspLocation;
        
        CompilationError(int javaLine, int javaColumn, String message, JSPSourceLocation jspLocation) {
            this.javaLine = javaLine;
            this.javaColumn = javaColumn;
            this.message = message;
            this.jspLocation = jspLocation;
        }
        
        public int getJavaLine() {
            return javaLine;
        }
        
        public int getJavaColumn() {
            return javaColumn;
        }
        
        public String getMessage() {
            return message;
        }
        
        /**
         * Returns the JSP source location, or null if not mapped.
         */
        public JSPSourceLocation getJspLocation() {
            return jspLocation;
        }
        
        /**
         * Returns true if this error has been mapped to a JSP source location.
         */
        public boolean hasJspLocation() {
            return jspLocation != null;
        }
        
        @Override
        public String toString() {
            if (jspLocation != null) {
                return jspLocation.getJspFile() + ":" + jspLocation.getJspLine() + 
                       " - " + message;
            }
            return MessageFormat.format(L10N.getString("compiler.error_line_format"), 
                                       javaLine, javaColumn, message);
        }
    }
}

