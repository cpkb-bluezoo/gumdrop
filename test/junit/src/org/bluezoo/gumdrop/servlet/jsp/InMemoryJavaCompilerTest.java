/*
 * InMemoryJavaCompilerTest.java
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

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * Unit tests for InMemoryJavaCompiler.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class InMemoryJavaCompilerTest {

    private InMemoryJavaCompiler compiler;

    @Before
    public void setUp() {
        compiler = new InMemoryJavaCompiler();
    }

    @Test
    public void testSimpleClassCompilation() throws Exception {
        String source = 
            "public class TestClass {\n" +
            "    public String getMessage() {\n" +
            "        return \"Hello, World!\";\n" +
            "    }\n" +
            "}";

        InMemoryJavaCompiler.CompilationResult result = compiler.compile("TestClass", source);
        
        assertTrue("Compilation should succeed", result.isSuccess());
        assertNotNull("Compiled class should not be null", result.getCompiledClass());
        assertEquals("Class name should match", "TestClass", result.getCompiledClass().getName());
        
        // Verify we can instantiate and call methods
        Object instance = result.getCompiledClass().getDeclaredConstructor().newInstance();
        Method method = result.getCompiledClass().getMethod("getMessage");
        String message = (String) method.invoke(instance);
        assertEquals("Hello, World!", message);
    }

    @Test
    public void testClassWithFields() throws Exception {
        String source = 
            "public class FieldTest {\n" +
            "    private int value = 42;\n" +
            "    public int getValue() {\n" +
            "        return value;\n" +
            "    }\n" +
            "    public void setValue(int v) {\n" +
            "        value = v;\n" +
            "    }\n" +
            "}";

        InMemoryJavaCompiler.CompilationResult result = compiler.compile("FieldTest", source);
        
        assertTrue("Compilation should succeed", result.isSuccess());
        
        Object instance = result.getCompiledClass().getDeclaredConstructor().newInstance();
        Method getValue = result.getCompiledClass().getMethod("getValue");
        Method setValue = result.getCompiledClass().getMethod("setValue", int.class);
        
        assertEquals(42, getValue.invoke(instance));
        setValue.invoke(instance, 100);
        assertEquals(100, getValue.invoke(instance));
    }

    @Test
    public void testClassWithImports() throws Exception {
        String source = 
            "import java.util.ArrayList;\n" +
            "import java.util.List;\n" +
            "public class ImportTest {\n" +
            "    public List<String> getList() {\n" +
            "        List<String> list = new ArrayList<String>();\n" +
            "        list.add(\"item1\");\n" +
            "        list.add(\"item2\");\n" +
            "        return list;\n" +
            "    }\n" +
            "}";

        InMemoryJavaCompiler.CompilationResult result = compiler.compile("ImportTest", source);
        
        assertTrue("Compilation should succeed", result.isSuccess());
        
        Object instance = result.getCompiledClass().getDeclaredConstructor().newInstance();
        Method getList = result.getCompiledClass().getMethod("getList");
        Object list = getList.invoke(instance);
        
        assertTrue(list instanceof java.util.List);
        assertEquals(2, ((java.util.List<?>) list).size());
    }

    @Test
    public void testCompilationError() throws Exception {
        String source = 
            "public class BrokenClass {\n" +
            "    public void badMethod() {\n" +
            "        this is not valid java\n" +  // Syntax error
            "    }\n" +
            "}";

        InMemoryJavaCompiler.CompilationResult result = compiler.compile("BrokenClass", source);
        
        assertFalse("Compilation should fail", result.isSuccess());
        assertNull("Compiled class should be null", result.getCompiledClass());
        assertFalse("Should have errors", result.getErrors().isEmpty());
    }

    @Test
    public void testCompilationErrorDetails() throws Exception {
        String source = 
            "public class ErrorDetails {\n" +
            "    public void method1() {\n" +
            "        String x = 5;\n" +  // Type mismatch error
            "    }\n" +
            "}";

        InMemoryJavaCompiler.CompilationResult result = compiler.compile("ErrorDetails", source);
        
        assertFalse("Compilation should fail", result.isSuccess());
        
        boolean foundTypeError = false;
        for (InMemoryJavaCompiler.CompilationError error : result.getErrors()) {
            assertTrue("Error should have line number", error.getJavaLine() > 0);
            if (error.getMessage().contains("incompatible types") || 
                error.getMessage().contains("cannot convert")) {
                foundTypeError = true;
            }
        }
        assertTrue("Should find type mismatch error", foundTypeError);
    }

    @Test
    public void testMultipleMethods() throws Exception {
        String source = 
            "public class MultiMethod {\n" +
            "    public int add(int a, int b) {\n" +
            "        return a + b;\n" +
            "    }\n" +
            "    public int multiply(int a, int b) {\n" +
            "        return a * b;\n" +
            "    }\n" +
            "    public double divide(double a, double b) {\n" +
            "        return a / b;\n" +
            "    }\n" +
            "}";

        InMemoryJavaCompiler.CompilationResult result = compiler.compile("MultiMethod", source);
        
        assertTrue("Compilation should succeed", result.isSuccess());
        
        Object instance = result.getCompiledClass().getDeclaredConstructor().newInstance();
        
        Method add = result.getCompiledClass().getMethod("add", int.class, int.class);
        Method multiply = result.getCompiledClass().getMethod("multiply", int.class, int.class);
        Method divide = result.getCompiledClass().getMethod("divide", double.class, double.class);
        
        assertEquals(7, add.invoke(instance, 3, 4));
        assertEquals(12, multiply.invoke(instance, 3, 4));
        assertEquals(2.5, divide.invoke(instance, 5.0, 2.0));
    }

    @Test
    public void testInnerClass() throws Exception {
        String source = 
            "public class OuterClass {\n" +
            "    private String message;\n" +
            "    public OuterClass(String msg) {\n" +
            "        this.message = msg;\n" +
            "    }\n" +
            "    public class InnerClass {\n" +
            "        public String getOuterMessage() {\n" +
            "            return message;\n" +
            "        }\n" +
            "    }\n" +
            "    public InnerClass createInner() {\n" +
            "        return new InnerClass();\n" +
            "    }\n" +
            "}";

        InMemoryJavaCompiler.CompilationResult result = compiler.compile("OuterClass", source);
        
        assertTrue("Compilation with inner class should succeed", result.isSuccess());
    }

    @Test
    public void testStaticMethod() throws Exception {
        String source = 
            "public class StaticTest {\n" +
            "    public static int factorial(int n) {\n" +
            "        if (n <= 1) return 1;\n" +
            "        return n * factorial(n - 1);\n" +
            "    }\n" +
            "}";

        InMemoryJavaCompiler.CompilationResult result = compiler.compile("StaticTest", source);
        
        assertTrue("Compilation should succeed", result.isSuccess());
        
        Method factorial = result.getCompiledClass().getMethod("factorial", int.class);
        assertEquals(120, factorial.invoke(null, 5));
        assertEquals(1, factorial.invoke(null, 0));
    }

    @Test
    public void testInterface() throws Exception {
        String source = 
            "public interface TestInterface {\n" +
            "    String getValue();\n" +
            "    void setValue(String value);\n" +
            "}";

        InMemoryJavaCompiler.CompilationResult result = compiler.compile("TestInterface", source);
        
        assertTrue("Interface compilation should succeed", result.isSuccess());
        assertTrue("Should be an interface", result.getCompiledClass().isInterface());
    }

    @Test
    public void testEnumCompilation() throws Exception {
        String source = 
            "public enum Status {\n" +
            "    PENDING, ACTIVE, COMPLETED, FAILED;\n" +
            "    public boolean isTerminal() {\n" +
            "        return this == COMPLETED || this == FAILED;\n" +
            "    }\n" +
            "}";

        InMemoryJavaCompiler.CompilationResult result = compiler.compile("Status", source);
        
        assertTrue("Enum compilation should succeed", result.isSuccess());
        assertTrue("Should be an enum", result.getCompiledClass().isEnum());
    }

    @Test
    public void testExceptionHandling() throws Exception {
        String source = 
            "public class ExceptionTest {\n" +
            "    public int safeDivide(int a, int b) {\n" +
            "        try {\n" +
            "            return a / b;\n" +
            "        } catch (ArithmeticException e) {\n" +
            "            return 0;\n" +
            "        }\n" +
            "    }\n" +
            "}";

        InMemoryJavaCompiler.CompilationResult result = compiler.compile("ExceptionTest", source);
        
        assertTrue("Compilation should succeed", result.isSuccess());
        
        Object instance = result.getCompiledClass().getDeclaredConstructor().newInstance();
        Method safeDivide = result.getCompiledClass().getMethod("safeDivide", int.class, int.class);
        
        assertEquals(5, safeDivide.invoke(instance, 10, 2));
        assertEquals(0, safeDivide.invoke(instance, 10, 0)); // Should not throw
    }
}

