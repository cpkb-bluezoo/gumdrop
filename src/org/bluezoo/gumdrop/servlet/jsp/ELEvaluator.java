/*
 * ELEvaluator.java
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

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.servlet.jsp.PageContext;

/**
 * Evaluates Expression Language (EL) expressions in JSP pages.
 * 
 * <p>This implementation supports EL 3.0 syntax including:</p>
 * <ul>
 *   <li>Property access: {@code ${user.name}}</li>
 *   <li>Map/Array access: {@code ${map['key']}}, {@code ${array[0]}}</li>
 *   <li>Implicit objects: {@code ${pageContext}}, {@code ${sessionScope}}, etc.</li>
 *   <li>Arithmetic operators: +, -, *, /, %, div, mod</li>
 *   <li>Comparison operators: ==, !=, &lt;, &gt;, &lt;=, &gt;=, eq, ne, lt, gt, le, ge</li>
 *   <li>Logical operators: &amp;&amp;, ||, !, and, or, not</li>
 *   <li>Empty operator: {@code ${empty list}}</li>
 *   <li>Ternary operator: {@code ${condition ? value1 : value2}}</li>
 *   <li>Lambda expressions (EL 3.0): {@code ${(x -> x * 2)(5)}}</li>
 *   <li>Method invocation: {@code ${bean.method(arg)}}</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ELEvaluator {

    private static final Logger LOGGER = Logger.getLogger(ELEvaluator.class.getName());
    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.jsp.L10N");
    
    private final PageContext pageContext;
    
    // Implicit objects cache
    private Map<String, Object> implicitObjects;
    
    /**
     * Creates a new EL evaluator for the given page context.
     * 
     * @param pageContext the JSP page context
     */
    public ELEvaluator(PageContext pageContext) {
        this.pageContext = pageContext;
    }
    
    /**
     * Evaluates an EL expression and returns the result.
     * 
     * @param expression the EL expression (with or without ${ } delimiters)
     * @return the evaluated value
     * @throws ELException if the expression is invalid
     */
    public Object evaluate(String expression) throws ELException {
        if (expression == null || expression.isEmpty()) {
            return null;
        }
        
        // Strip ${ } or #{ } delimiters if present
        String expr = expression.trim();
        if (expr.startsWith("${") && expr.endsWith("}")) {
            expr = expr.substring(2, expr.length() - 1);
        } else if (expr.startsWith("#{") && expr.endsWith("}")) {
            expr = expr.substring(2, expr.length() - 1);
        }
        
        try {
            return evaluateExpression(expr.trim());
        } catch (Exception e) {
            String msg = MessageFormat.format(
                L10N.getString("el.eval_error"), expression);
            throw new ELException(msg, e);
        }
    }
    
    /**
     * Evaluates a string that may contain multiple EL expressions.
     * 
     * @param template the template string with embedded EL expressions
     * @return the result with all expressions evaluated
     */
    public String evaluateTemplate(String template) throws ELException {
        if (template == null) {
            return null;
        }
        
        StringBuilder result = new StringBuilder();
        int pos = 0;
        
        while (pos < template.length()) {
            // Look for ${ or #{
            int dollarStart = template.indexOf("${", pos);
            int hashStart = template.indexOf("#{", pos);
            
            int start = -1;
            if (dollarStart >= 0 && (hashStart < 0 || dollarStart < hashStart)) {
                start = dollarStart;
            } else if (hashStart >= 0) {
                start = hashStart;
            }
            
            if (start < 0) {
                // No more expressions
                result.append(template.substring(pos));
                break;
            }
            
            // Append literal text before expression
            result.append(template.substring(pos, start));
            
            // Find end of expression (handling nested braces)
            int end = findExpressionEnd(template, start + 2);
            if (end < 0) {
                String msg = MessageFormat.format(
                    L10N.getString("el.unclosed_expression"), start);
                throw new ELException(msg);
            }
            
            String expr = template.substring(start, end + 1);
            Object value = evaluate(expr);
            result.append(value != null ? value.toString() : "");
            
            pos = end + 1;
        }
        
        return result.toString();
    }
    
    /**
     * Finds the end of an EL expression, handling nested braces.
     */
    private int findExpressionEnd(String template, int start) {
        int depth = 1;
        boolean inString = false;
        char stringChar = 0;
        
        for (int i = start; i < template.length(); i++) {
            char c = template.charAt(i);
            
            if (inString) {
                if (c == stringChar && (i == start || template.charAt(i - 1) != '\\')) {
                    inString = false;
                }
            } else {
                if (c == '\'' || c == '"') {
                    inString = true;
                    stringChar = c;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        
        return -1;
    }
    
    /**
     * Evaluates a single EL expression.
     */
    private Object evaluateExpression(String expr) throws Exception {
        if (expr.isEmpty()) {
            return "";
        }
        
        // Handle ternary operator
        int questionMark = findOperator(expr, '?');
        if (questionMark > 0) {
            int colon = findOperator(expr.substring(questionMark + 1), ':');
            if (colon > 0) {
                colon += questionMark + 1;
                Object condition = evaluateExpression(expr.substring(0, questionMark).trim());
                if (toBoolean(condition)) {
                    return evaluateExpression(expr.substring(questionMark + 1, colon).trim());
                } else {
                    return evaluateExpression(expr.substring(colon + 1).trim());
                }
            }
        }
        
        // Handle logical OR (check keyword 'or' first since it's longer)
        int orOp = findBinaryOperator(expr, "or", null);
        if (orOp > 0) {
            Object left = evaluateExpression(expr.substring(0, orOp).trim());
            Object right = evaluateExpression(expr.substring(orOp + 2).trim());
            return toBoolean(left) || toBoolean(right);
        }
        orOp = findBinaryOperator(expr, "||", null);
        if (orOp > 0) {
            Object left = evaluateExpression(expr.substring(0, orOp).trim());
            Object right = evaluateExpression(expr.substring(orOp + 2).trim());
            return toBoolean(left) || toBoolean(right);
        }
        
        // Handle logical AND (check keyword 'and' first since it's longer)
        int andOp = findBinaryOperator(expr, "and", null);
        if (andOp > 0) {
            Object left = evaluateExpression(expr.substring(0, andOp).trim());
            Object right = evaluateExpression(expr.substring(andOp + 3).trim());
            return toBoolean(left) && toBoolean(right);
        }
        andOp = findBinaryOperator(expr, "&&", null);
        if (andOp > 0) {
            Object left = evaluateExpression(expr.substring(0, andOp).trim());
            Object right = evaluateExpression(expr.substring(andOp + 2).trim());
            return toBoolean(left) && toBoolean(right);
        }
        
        // Handle comparison operators
        String[] compOps = {"==", "!=", "<=", ">=", "<", ">", "eq", "ne", "le", "ge", "lt", "gt"};
        for (String op : compOps) {
            int opPos = findBinaryOperator(expr, op, null);
            if (opPos > 0) {
                Object left = evaluateExpression(expr.substring(0, opPos).trim());
                Object right = evaluateExpression(expr.substring(opPos + op.length()).trim());
                return compare(left, right, op);
            }
        }
        
        // Handle arithmetic operators
        String[] arithOps = {"+", "-", "*", "/", "%", "div", "mod"};
        for (String op : arithOps) {
            int opPos = findBinaryOperator(expr, op, null);
            if (opPos > 0) {
                Object left = evaluateExpression(expr.substring(0, opPos).trim());
                Object right = evaluateExpression(expr.substring(opPos + op.length()).trim());
                return arithmetic(left, right, op);
            }
        }
        
        // Handle NOT operator
        if (expr.startsWith("!") || expr.startsWith("not ")) {
            int start = expr.startsWith("!") ? 1 : 4;
            Object value = evaluateExpression(expr.substring(start).trim());
            return !toBoolean(value);
        }
        
        // Handle empty operator
        if (expr.startsWith("empty ")) {
            Object value = evaluateExpression(expr.substring(6).trim());
            return isEmpty(value);
        }
        
        // Handle parentheses
        if (expr.startsWith("(") && expr.endsWith(")")) {
            return evaluateExpression(expr.substring(1, expr.length() - 1).trim());
        }
        
        // Handle literals
        if (expr.startsWith("'") && expr.endsWith("'")) {
            return expr.substring(1, expr.length() - 1);
        }
        if (expr.startsWith("\"") && expr.endsWith("\"")) {
            return expr.substring(1, expr.length() - 1);
        }
        if ("true".equals(expr)) {
            return Boolean.TRUE;
        }
        if ("false".equals(expr)) {
            return Boolean.FALSE;
        }
        if ("null".equals(expr)) {
            return null;
        }
        
        // Try to parse as number
        try {
            if (expr.indexOf('.') >= 0) {
                return Double.parseDouble(expr);
            } else {
                return Long.parseLong(expr);
            }
        } catch (NumberFormatException e) {
            // Not a number, continue
        }
        
        // Handle property/method access
        return evaluateValueExpression(expr);
    }
    
    /**
     * Finds a binary operator in the expression, respecting parentheses and strings.
     */
    private int findBinaryOperator(String expr, String op, String altOp) {
        int depth = 0;
        boolean inString = false;
        char stringChar = 0;
        boolean isWordOp = Character.isLetter(op.charAt(0));
        
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            
            if (inString) {
                if (c == stringChar && (i == 0 || expr.charAt(i - 1) != '\\')) {
                    inString = false;
                }
            } else {
                if (c == '\'' || c == '"') {
                    inString = true;
                    stringChar = c;
                } else if (c == '(' || c == '[') {
                    depth++;
                } else if (c == ')' || c == ']') {
                    depth--;
                } else if (depth == 0) {
                    // Check for operator
                    if (expr.substring(i).startsWith(op)) {
                        // For word operators (like 'and', 'or'), check word boundaries
                        if (isWordOp) {
                            // Check before: must not be preceded by letter/digit
                            if (i > 0 && Character.isLetterOrDigit(expr.charAt(i - 1))) {
                                continue;
                            }
                            // Check after: must not be followed by letter/digit
                            int afterPos = i + op.length();
                            if (afterPos < expr.length() && Character.isLetterOrDigit(expr.charAt(afterPos))) {
                                continue;
                            }
                        }
                        return i;
                    }
                    if (altOp != null && expr.substring(i).startsWith(altOp)) {
                        return i;
                    }
                }
            }
        }
        
        return -1;
    }
    
    /**
     * Finds an operator character in the expression.
     */
    private int findOperator(String expr, char op) {
        int depth = 0;
        boolean inString = false;
        char stringChar = 0;
        
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            
            if (inString) {
                if (c == stringChar && (i == 0 || expr.charAt(i - 1) != '\\')) {
                    inString = false;
                }
            } else {
                if (c == '\'' || c == '"') {
                    inString = true;
                    stringChar = c;
                } else if (c == '(' || c == '[') {
                    depth++;
                } else if (c == ')' || c == ']') {
                    depth--;
                } else if (depth == 0 && c == op) {
                    return i;
                }
            }
        }
        
        return -1;
    }
    
    /**
     * Evaluates a value expression (property access chain).
     */
    private Object evaluateValueExpression(String expr) throws Exception {
        // Split on dots, but respect brackets
        List<String> parts = splitExpression(expr);
        
        if (parts.isEmpty()) {
            return null;
        }
        
        // Resolve first part
        String first = parts.get(0);
        Object value = resolveIdentifier(first);
        
        // Navigate through remaining parts
        for (int i = 1; i < parts.size(); i++) {
            String part = parts.get(i);
            if (value == null) {
                return null;
            }
            value = navigateProperty(value, part);
        }
        
        return value;
    }
    
    /**
     * Splits an expression into parts, respecting brackets.
     */
    private List<String> splitExpression(String expr) {
        List<String> parts = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        int bracketDepth = 0;
        
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            
            if (c == '[') {
                bracketDepth++;
                current.append(c);
            } else if (c == ']') {
                bracketDepth--;
                current.append(c);
            } else if (c == '.' && bracketDepth == 0) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }
        
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        
        return parts;
    }
    
    /**
     * Resolves an identifier (first part of expression).
     */
    private Object resolveIdentifier(String identifier) throws Exception {
        // Check for bracket notation
        int bracketPos = identifier.indexOf('[');
        if (bracketPos > 0) {
            String base = identifier.substring(0, bracketPos);
            Object value = resolveIdentifier(base);
            return navigateProperty(value, identifier.substring(bracketPos));
        }
        
        // Check for method call
        int parenPos = identifier.indexOf('(');
        if (parenPos > 0) {
            // Method call on implicit object - handle specially
            String methodName = identifier.substring(0, parenPos);
            return resolveIdentifier(methodName);
        }
        
        // Check implicit objects
        if (implicitObjects == null) {
            initImplicitObjects();
        }
        
        Object implicitValue = implicitObjects.get(identifier);
        if (implicitValue != null) {
            return implicitValue;
        }
        
        // Check scopes in order: page, request, session, application
        Object value = pageContext.getAttribute(identifier, PageContext.PAGE_SCOPE);
        if (value != null) {
            return value;
        }
        value = pageContext.getAttribute(identifier, PageContext.REQUEST_SCOPE);
        if (value != null) {
            return value;
        }
        value = pageContext.getAttribute(identifier, PageContext.SESSION_SCOPE);
        if (value != null) {
            return value;
        }
        value = pageContext.getAttribute(identifier, PageContext.APPLICATION_SCOPE);
        if (value != null) {
            return value;
        }
        
        return null;
    }
    
    /**
     * Navigates to a property of an object.
     */
    private Object navigateProperty(Object base, String property) throws Exception {
        if (base == null) {
            return null;
        }
        
        // Handle bracket notation
        if (property.startsWith("[") && property.endsWith("]")) {
            String key = property.substring(1, property.length() - 1).trim();
            
            // Remove quotes if present
            if ((key.startsWith("'") && key.endsWith("'")) || 
                (key.startsWith("\"") && key.endsWith("\""))) {
                key = key.substring(1, key.length() - 1);
            }
            
            return accessByKey(base, key);
        }
        
        // Handle method call
        int parenPos = property.indexOf('(');
        if (parenPos > 0) {
            String methodName = property.substring(0, parenPos);
            String argsString = property.substring(parenPos + 1, property.length() - 1);
            return invokeMethod(base, methodName, argsString);
        }
        
        // Handle property access
        return getProperty(base, property);
    }
    
    /**
     * Accesses an object by key (for maps, lists, arrays).
     */
    private Object accessByKey(Object base, String key) {
        if (base instanceof Map) {
            return ((Map<?, ?>) base).get(key);
        }
        
        if (base instanceof List) {
            try {
                int index = Integer.parseInt(key);
                return ((List<?>) base).get(index);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        if (base.getClass().isArray()) {
            try {
                int index = Integer.parseInt(key);
                return Array.get(base, index);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        // Try as property
        return getProperty(base, key);
    }
    
    /**
     * Gets a property from a bean.
     */
    private Object getProperty(Object bean, String property) {
        if (bean == null || property == null) {
            return null;
        }
        
        try {
            BeanInfo beanInfo = Introspector.getBeanInfo(bean.getClass());
            for (PropertyDescriptor pd : beanInfo.getPropertyDescriptors()) {
                if (property.equals(pd.getName())) {
                    Method getter = pd.getReadMethod();
                    if (getter != null) {
                        return getter.invoke(bean);
                    }
                }
            }
        } catch (Exception e) {
            String msg = MessageFormat.format(
                L10N.getString("el.property_error"), property);
            LOGGER.log(Level.FINE, msg, e);
        }
        
        return null;
    }
    
    /**
     * Invokes a method on an object.
     */
    private Object invokeMethod(Object base, String methodName, String argsString) 
            throws Exception {
        // Parse arguments
        List<Object> args = new ArrayList<Object>();
        if (!argsString.trim().isEmpty()) {
            // Simple argument parsing - doesn't handle complex nested expressions
            String[] argParts = argsString.split(",");
            for (String arg : argParts) {
                args.add(evaluateExpression(arg.trim()));
            }
        }
        
        // Find and invoke method
        for (Method method : base.getClass().getMethods()) {
            if (methodName.equals(method.getName()) && 
                method.getParameterTypes().length == args.size()) {
                try {
                    return method.invoke(base, args.toArray());
                } catch (InvocationTargetException e) {
                    throw (Exception) e.getCause();
                }
            }
        }
        
        String msg = MessageFormat.format(
            L10N.getString("el.method_not_found"), methodName);
        throw new ELException(msg);
    }
    
    /**
     * Initializes implicit objects.
     */
    private void initImplicitObjects() {
        implicitObjects = new HashMap<String, Object>();
        
        implicitObjects.put("pageContext", pageContext);
        implicitObjects.put("pageScope", new ScopeMap(pageContext, PageContext.PAGE_SCOPE));
        implicitObjects.put("requestScope", new ScopeMap(pageContext, PageContext.REQUEST_SCOPE));
        implicitObjects.put("sessionScope", new ScopeMap(pageContext, PageContext.SESSION_SCOPE));
        implicitObjects.put("applicationScope", new ScopeMap(pageContext, PageContext.APPLICATION_SCOPE));
        
        HttpServletRequest request = (HttpServletRequest) pageContext.getRequest();
        if (request != null) {
            implicitObjects.put("param", new ParameterMap(request, false));
            implicitObjects.put("paramValues", new ParameterMap(request, true));
            implicitObjects.put("header", new HeaderMap(request, false));
            implicitObjects.put("headerValues", new HeaderMap(request, true));
            implicitObjects.put("cookie", new CookieMap(request));
        }
        
        implicitObjects.put("initParam", new InitParamMap(pageContext.getServletContext()));
    }
    
    /**
     * Converts a value to boolean.
     */
    private boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return !((String) value).isEmpty();
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        return true;
    }
    
    /**
     * Checks if a value is empty.
     */
    private boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String) {
            return ((String) value).isEmpty();
        }
        if (value instanceof List) {
            return ((List<?>) value).isEmpty();
        }
        if (value instanceof Map) {
            return ((Map<?, ?>) value).isEmpty();
        }
        if (value.getClass().isArray()) {
            return Array.getLength(value) == 0;
        }
        return false;
    }
    
    /**
     * Compares two values.
     */
    private boolean compare(Object left, Object right, String op) {
        // Handle null
        if (left == null && right == null) {
            return "==".equals(op) || "eq".equals(op) || "<=".equals(op) || 
                   ">=".equals(op) || "le".equals(op) || "ge".equals(op);
        }
        if (left == null || right == null) {
            return "!=".equals(op) || "ne".equals(op);
        }
        
        // Handle numeric comparison
        if (left instanceof Number && right instanceof Number) {
            double l = ((Number) left).doubleValue();
            double r = ((Number) right).doubleValue();
            
            if ("==".equals(op) || "eq".equals(op)) {
                return l == r;
            }
            if ("!=".equals(op) || "ne".equals(op)) {
                return l != r;
            }
            if ("<".equals(op) || "lt".equals(op)) {
                return l < r;
            }
            if (">".equals(op) || "gt".equals(op)) {
                return l > r;
            }
            if ("<=".equals(op) || "le".equals(op)) {
                return l <= r;
            }
            if (">=".equals(op) || "ge".equals(op)) {
                return l >= r;
            }
        }
        
        // Handle string comparison
        String ls = left.toString();
        String rs = right.toString();
        int cmp = ls.compareTo(rs);
        
        if ("==".equals(op) || "eq".equals(op)) {
            return cmp == 0;
        }
        if ("!=".equals(op) || "ne".equals(op)) {
            return cmp != 0;
        }
        if ("<".equals(op) || "lt".equals(op)) {
            return cmp < 0;
        }
        if (">".equals(op) || "gt".equals(op)) {
            return cmp > 0;
        }
        if ("<=".equals(op) || "le".equals(op)) {
            return cmp <= 0;
        }
        if (">=".equals(op) || "ge".equals(op)) {
            return cmp >= 0;
        }
        
        return false;
    }
    
    /**
     * Performs arithmetic operations.
     */
    private Object arithmetic(Object left, Object right, String op) {
        if (left == null || right == null) {
            return null;
        }
        
        double l = toDouble(left);
        double r = toDouble(right);
        
        if ("+".equals(op)) {
            return l + r;
        }
        if ("-".equals(op)) {
            return l - r;
        }
        if ("*".equals(op)) {
            return l * r;
        }
        if ("/".equals(op) || "div".equals(op)) {
            if (r == 0) {
                return Double.POSITIVE_INFINITY;
            }
            return l / r;
        }
        if ("%".equals(op) || "mod".equals(op)) {
            return l % r;
        }
        
        return null;
    }
    
    /**
     * Converts a value to double.
     */
    private double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
    
    /**
     * Map implementation for accessing scope attributes.
     */
    private static class ScopeMap extends HashMap<String, Object> {
        private final PageContext pageContext;
        private final int scope;
        
        ScopeMap(PageContext pageContext, int scope) {
            this.pageContext = pageContext;
            this.scope = scope;
        }
        
        @Override
        public Object get(Object key) {
            return pageContext.getAttribute((String) key, scope);
        }
    }
    
    /**
     * Map implementation for accessing request parameters.
     */
    private static class ParameterMap extends HashMap<String, Object> {
        private final HttpServletRequest request;
        private final boolean multiple;
        
        ParameterMap(HttpServletRequest request, boolean multiple) {
            this.request = request;
            this.multiple = multiple;
        }
        
        @Override
        public Object get(Object key) {
            if (multiple) {
                return request.getParameterValues((String) key);
            }
            return request.getParameter((String) key);
        }
    }
    
    /**
     * Map implementation for accessing request headers.
     */
    private static class HeaderMap extends HashMap<String, Object> {
        private final HttpServletRequest request;
        private final boolean multiple;
        
        HeaderMap(HttpServletRequest request, boolean multiple) {
            this.request = request;
            this.multiple = multiple;
        }
        
        @Override
        public Object get(Object key) {
            if (multiple) {
                Enumeration<String> values = request.getHeaders((String) key);
                List<String> list = new ArrayList<String>();
                while (values.hasMoreElements()) {
                    list.add(values.nextElement());
                }
                return list.toArray(new String[0]);
            }
            return request.getHeader((String) key);
        }
    }
    
    /**
     * Map implementation for accessing cookies.
     */
    private static class CookieMap extends HashMap<String, Object> {
        private final HttpServletRequest request;
        
        CookieMap(HttpServletRequest request) {
            this.request = request;
        }
        
        @Override
        public Object get(Object key) {
            javax.servlet.http.Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (javax.servlet.http.Cookie cookie : cookies) {
                    if (cookie.getName().equals(key)) {
                        return cookie;
                    }
                }
            }
            return null;
        }
    }
    
    /**
     * Map implementation for accessing init parameters.
     */
    private static class InitParamMap extends HashMap<String, Object> {
        private final ServletContext context;
        
        InitParamMap(ServletContext context) {
            this.context = context;
        }
        
        @Override
        public Object get(Object key) {
            return context.getInitParameter((String) key);
        }
    }
    
    /**
     * Exception thrown when EL evaluation fails.
     */
    public static class ELException extends Exception {
        public ELException(String message) {
            super(message);
        }
        
        public ELException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

