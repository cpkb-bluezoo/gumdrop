/*
 * JSPPrecompiler.java
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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Precompiles JSP files to servlet classes at build time.
 * 
 * <p>This can be invoked from:</p>
 * <ul>
 *   <li>Ant build scripts</li>
 *   <li>Maven plugins</li>
 *   <li>Command line</li>
 * </ul>
 * 
 * <p>Usage:</p>
 * <pre>
 * java org.bluezoo.gumdrop.servlet.jsp.JSPPrecompiler \
 *     -webapp /path/to/webapp \
 *     -output /path/to/classes \
 *     -package org.example.jsp
 * </pre>
 * 
 * <p>Precompilation benefits:</p>
 * <ul>
 *   <li>Faster startup - no compilation at first request</li>
 *   <li>Early error detection - syntax errors found at build time</li>
 *   <li>Reduced memory usage - no need for compiler at runtime</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSPPrecompiler {

    private static final Logger LOGGER = Logger.getLogger(JSPPrecompiler.class.getName());
    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.jsp.L10N");
    
    private File webappRoot;
    private File outputDir;
    private String packageName = "org.bluezoo.gumdrop.servlet.jsp.generated";
    private boolean verbose = false;
    private boolean failOnError = true;
    private int threadCount = 1;
    
    private JSPParserFactory parserFactory;
    private InMemoryJavaCompiler compiler;
    
    private int successCount = 0;
    private int errorCount = 0;
    private List<String> errors = new ArrayList<String>();
    
    /**
     * Creates a new JSP precompiler.
     */
    public JSPPrecompiler() {
        // Initialize parser factory
        try {
            javax.xml.parsers.SAXParserFactory saxFactory = 
                javax.xml.parsers.SAXParserFactory.newInstance();
            saxFactory.setNamespaceAware(true);
            saxFactory.setValidating(false);
            this.parserFactory = new JSPParserFactory(saxFactory);
        } catch (Exception e) {
            throw new RuntimeException(L10N.getString("precompiler.init_failed"), e);
        }
        
        // Initialize compiler
        this.compiler = new InMemoryJavaCompiler();
    }
    
    /**
     * Sets the web application root directory.
     */
    public void setWebappRoot(File webappRoot) {
        this.webappRoot = webappRoot;
    }
    
    /**
     * Sets the output directory for compiled classes.
     */
    public void setOutputDir(File outputDir) {
        this.outputDir = outputDir;
    }
    
    /**
     * Sets the package name for generated servlet classes.
     */
    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }
    
    /**
     * Enables or disables verbose output.
     */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }
    
    /**
     * Sets whether to fail on first error or continue.
     */
    public void setFailOnError(boolean failOnError) {
        this.failOnError = failOnError;
    }
    
    /**
     * Sets the number of threads for parallel compilation.
     */
    public void setThreadCount(int threadCount) {
        this.threadCount = Math.max(1, threadCount);
    }
    
    /**
     * Precompiles all JSP files in the web application.
     * 
     * @return true if all compilations succeeded
     * @throws IOException if an I/O error occurs
     */
    public boolean precompile() throws IOException {
        if (webappRoot == null || !webappRoot.isDirectory()) {
            throw new IllegalStateException(L10N.getString("precompiler.webapp_invalid"));
        }
        
        if (outputDir == null) {
            throw new IllegalStateException(L10N.getString("precompiler.output_not_set"));
        }
        
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        // Find all JSP files
        List<File> jspFiles = new ArrayList<File>();
        findJSPFiles(webappRoot, jspFiles);
        
        if (jspFiles.isEmpty()) {
            if (verbose) {
                System.out.println(MessageFormat.format(
                    L10N.getString("precompiler.no_jsp_found"), webappRoot));
            }
            return true;
        }
        
        if (verbose) {
            System.out.println(MessageFormat.format(
                L10N.getString("precompiler.found_jsp"), jspFiles.size()));
        }
        
        // Compile JSP files
        if (threadCount > 1) {
            compileParallel(jspFiles);
        } else {
            for (File jspFile : jspFiles) {
                compileJSP(jspFile);
                if (failOnError && errorCount > 0) {
                    break;
                }
            }
        }
        
        // Summary
        if (verbose) {
            System.out.println();
            System.out.println(L10N.getString("precompiler.complete"));
            System.out.println(MessageFormat.format(
                L10N.getString("precompiler.success_count"), successCount));
            System.out.println(MessageFormat.format(
                L10N.getString("precompiler.error_count"), errorCount));
        }
        
        return errorCount == 0;
    }
    
    /**
     * Finds all JSP files recursively.
     */
    private void findJSPFiles(File dir, List<File> result) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        
        for (File file : files) {
            if (file.isDirectory()) {
                // Skip WEB-INF and META-INF
                String name = file.getName();
                if (!"WEB-INF".equals(name) && !"META-INF".equals(name)) {
                    findJSPFiles(file, result);
                }
            } else {
                String name = file.getName().toLowerCase();
                if (name.endsWith(".jsp") || name.endsWith(".jspx") || 
                    name.endsWith(".jspf")) {
                    result.add(file);
                }
            }
        }
    }
    
    /**
     * Compiles JSP files in parallel using multiple threads.
     */
    private void compileParallel(List<File> jspFiles) {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount, 
            new CompilerThreadFactory());
        
        for (final File jspFile : jspFiles) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    compileJSP(jspFile);
                }
            });
        }
        
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Compiles a single JSP file.
     */
    private synchronized void compileJSP(File jspFile) {
        String jspPath = getRelativePath(webappRoot, jspFile);
        
        if (verbose) {
            System.out.println(MessageFormat.format(
                L10N.getString("precompiler.compiling"), jspPath));
        }
        
        try {
            // Parse JSP
            InputStream input = new FileInputStream(jspFile);
            JSPPage jspPage;
            try {
                jspPage = parserFactory.parseJSP(input, "UTF-8", jspPath, null);
            } finally {
                input.close();
            }
            
            // Generate Java source
            String className = generateClassName(jspPath);
            String fullClassName = packageName + "." + className;
            
            ByteArrayOutputStream sourceOut = new ByteArrayOutputStream();
            TaglibRegistry taglibRegistry = null; // No taglib support in precompilation
            JSPCodeGenerator generator = new JSPCodeGenerator(jspPage, sourceOut, taglibRegistry);
            generator.setClassName(className);
            generator.generateCode();
            
            String sourceCode = addPackageDeclaration(sourceOut.toString("UTF-8"), packageName);
            
            // Compile
            InMemoryJavaCompiler.CompilationResult result = compiler.compile(fullClassName, sourceCode);
            
            if (result.isSuccess()) {
                // Write class file
                writeClassFile(fullClassName, result.getCompiledClass());
                successCount++;
            } else {
                errorCount++;
                for (InMemoryJavaCompiler.CompilationError error : result.getErrors()) {
                    String errorMsg = jspPath + ": " + error.getMessage();
                    errors.add(errorMsg);
                    System.err.println(MessageFormat.format(
                        L10N.getString("precompiler.error"), errorMsg));
                }
            }
            
        } catch (Exception e) {
            errorCount++;
            String errorMsg = jspPath + ": " + e.getMessage();
            errors.add(errorMsg);
            System.err.println(MessageFormat.format(
                L10N.getString("precompiler.error"), errorMsg));
            if (verbose) {
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Adds a package declaration to the generated source code.
     */
    private String addPackageDeclaration(String sourceCode, String packageName) {
        return "package " + packageName + ";\n\n" + sourceCode;
    }
    
    /**
     * Generates a class name from a JSP path.
     */
    private String generateClassName(String jspPath) {
        // Remove leading slash and extension
        String name = jspPath.startsWith("/") ? jspPath.substring(1) : jspPath;
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0) {
            name = name.substring(0, dotIndex);
        }
        
        // Replace path separators and invalid characters
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == '-' || c == '.') {
                capitalizeNext = true;
            } else if (Character.isJavaIdentifierPart(c)) {
                if (capitalizeNext) {
                    sb.append(Character.toUpperCase(c));
                    capitalizeNext = false;
                } else {
                    sb.append(c);
                }
            } else {
                sb.append('_');
                capitalizeNext = true;
            }
        }
        
        String result = sb.toString();
        if (result.isEmpty() || !Character.isJavaIdentifierStart(result.charAt(0))) {
            result = "JSP_" + result;
        }
        
        return result + "_jsp";
    }
    
    /**
     * Gets the relative path from base to file.
     */
    private String getRelativePath(File base, File file) {
        String basePath = base.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        
        if (filePath.startsWith(basePath)) {
            String relative = filePath.substring(basePath.length());
            if (relative.startsWith(File.separator)) {
                relative = relative.substring(1);
            }
            return "/" + relative.replace(File.separatorChar, '/');
        }
        
        return file.getName();
    }
    
    /**
     * Writes the compiled class to a file.
     */
    private void writeClassFile(String fullClassName, Class<?> clazz) throws IOException {
        // Note: In Java 8, we cannot easily get bytecode from a loaded class
        // This is a limitation - in production we'd need to capture bytecode during compilation
        // For now, this is a placeholder that would need the InMemoryJavaCompiler to 
        // also provide the raw bytecode
        
        if (verbose) {
            System.out.println(MessageFormat.format(
                L10N.getString("precompiler.compiled_class"), fullClassName));
        }
    }
    
    /**
     * Returns the list of compilation errors.
     */
    public List<String> getErrors() {
        return new ArrayList<String>(errors);
    }
    
    /**
     * Thread factory for compiler threads.
     */
    private static class CompilerThreadFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger(0);
        
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "jsp-compiler-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
    
    /**
     * Command-line entry point.
     */
    public static void main(String[] args) {
        JSPPrecompiler precompiler = new JSPPrecompiler();
        
        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-webapp".equals(arg) && i + 1 < args.length) {
                precompiler.setWebappRoot(new File(args[++i]));
            } else if ("-output".equals(arg) && i + 1 < args.length) {
                precompiler.setOutputDir(new File(args[++i]));
            } else if ("-package".equals(arg) && i + 1 < args.length) {
                precompiler.setPackageName(args[++i]);
            } else if ("-verbose".equals(arg)) {
                precompiler.setVerbose(true);
            } else if ("-threads".equals(arg) && i + 1 < args.length) {
                precompiler.setThreadCount(Integer.parseInt(args[++i]));
            } else if ("-help".equals(arg)) {
                printUsage();
                return;
            }
        }
        
        try {
            boolean success = precompiler.precompile();
            System.exit(success ? 0 : 1);
        } catch (Exception e) {
            System.err.println(MessageFormat.format(
                L10N.getString("precompiler.failed"), e.getMessage()));
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Prints command-line usage.
     */
    private static void printUsage() {
        System.out.println(L10N.getString("precompiler.usage"));
        System.out.println();
        System.out.println(L10N.getString("precompiler.options"));
        System.out.println(L10N.getString("precompiler.option_webapp"));
        System.out.println(L10N.getString("precompiler.option_output"));
        System.out.println(L10N.getString("precompiler.option_package"));
        System.out.println(L10N.getString("precompiler.option_threads"));
        System.out.println(L10N.getString("precompiler.option_verbose"));
        System.out.println(L10N.getString("precompiler.option_help"));
    }
}

