import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class DelphiInterpreter extends delphiBaseVisitor<Object> {

    // variable to turn on or off debug print statements
    private boolean debug = false;

    public boolean debugOn() {
        return debug;
    }

    DelphiInterpreter(boolean debug) {
        this.debug = debug;
    }

    // create new exceptions for break and continue statements, while visiting loop block
    private static class BreakSignal extends RuntimeException {}
    private static class ContinueSignal extends RuntimeException {}

    // class to store class definitions
    public static class ClassDef {
        String name;
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, delphiParser.MethodDeclarationContext> methods = new HashMap<>();

        public ClassDef(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            List<String> methodNames = new ArrayList<>(methods.keySet());
            return "class " + name + " { " + fields + " }, methods: " + methodNames;
        }
    }

    // class to store object definitions
    public class ObjectInstance {
        String className;
        Map<String, Object> fields = new HashMap<>();

        public ObjectInstance(String className, Map<String, String> declaredFields) {
            this.className = className;
            for (String field : declaredFields.keySet()) {
                fields.put(field, null);
            }
        }

        public Object getField(String name) {
            return fields.get(name);
        }

        public void setField(String name, Object value) {
            fields.put(name, value);
        }

        public boolean hasField(String name) {
            return fields.containsKey(name);
        }

        @Override
        public String toString() {
            return "Object of " + className + ": " + fields;
        }
    }

    private ObjectInstance currentObject = null;
    private String currentClassName = null;
    Deque<Map<String, Object>> variableStack = new ArrayDeque<>();
    Map<String, delphiParser.ProcedureDeclarationContext> procedures = new HashMap<>();
    Map<String, delphiParser.FunctionDeclarationContext> functions = new HashMap<>();
    Map<String, ClassDef> classDefs = new HashMap<>();
    Map<String, delphiParser.ConstructorDefinitionContext> constructorDefs = new HashMap<>();
    Map<String, delphiParser.DestructorDefinitionContext> destructorDefs = new HashMap<>();

    // ============== HELPER FUNCTIONS START ==============

    private void printVariableStack() {
        System.out.println("------ VARIABLE STACK ------");
        int level = variableStack.size();
        for (Map<String, Object> scope : variableStack) {
            System.out.println("Scope Level " + (level--) + ": " + scope);
        }
        System.out.println("----------------------------");
    }

    private Object getVariable(String name) {
        for (Map<String, Object> scope : variableStack) {
            if (scope.containsKey(name)) {
                return scope.get(name);
            }
        }
        throw new RuntimeException("Variable '" + name + "' is not initialized.");
    }

    private Object getVariableOrDefault(String name, Object defaultValue) {
        for (Map<String, Object> scope : variableStack) {
            if (scope.containsKey(name)) {
                return scope.get(name);
            }
        }
        return defaultValue;
    }

    private void setVariable(String name, Object value) {
        for (Map<String, Object> scope : variableStack) {
            if (scope.containsKey(name)) {
                scope.put(name, value);
                return;
            }
        }
        // Default to global if not declared yet
        variableStack.peek().put(name, value);
    }

    // Evaluate relational expressions like "I < 10"
    private boolean isConditionTrue(delphiParser.ExpressionContext exprCtx) {
        Object left = visit(exprCtx.simpleExpression());

        if (exprCtx.relationaloperator() != null) {
            Object right = visit(exprCtx.expression());
            String op = exprCtx.relationaloperator().getText();

            int l = (int) left;
            int r = (int) right;

            return switch (op) {
                case "<" -> l < r;
                case "<=" -> l <= r;
                case ">" -> l > r;
                case ">=" -> l >= r;
                case "=" -> l == r;
                case "<>" -> l != r;
                default -> throw new RuntimeException("Unsupported operator: " + op);
            };
        }

        return (left instanceof Boolean b) ? b : false;
    }

    // ============== HELPER FUNCTIONS END ==============

    // ============== VISIT FUNCTIONS START ==============

    @Override
    public Object visitUnsignedInteger(delphiParser.UnsignedIntegerContext ctx) {
        return Integer.parseInt(ctx.getText());
    }

    @Override
    public Object visitUnsignedConstant(delphiParser.UnsignedConstantContext ctx) {
        return visit(ctx.unsignedNumber());
    }

    @Override
    public Object visitUnsignedNumber(delphiParser.UnsignedNumberContext ctx) {
        return visit(ctx.unsignedInteger());
    }

    @Override
    public Object visitTerm(delphiParser.TermContext ctx) {
        return visit(ctx.signedFactor());
    }

    @Override
    public Object visitSignedFactor(delphiParser.SignedFactorContext ctx) {
        return visit(ctx.factor());
    }

    @Override
    public Object visitExpression(delphiParser.ExpressionContext ctx) {
        return visit(ctx.simpleExpression());
    }

    @Override
    public Object visitSimpleExpression(delphiParser.SimpleExpressionContext ctx) {
        Object leftVal = visit(ctx.term());
        if (ctx.additiveoperator() != null) {
            Object rightVal = visit(ctx.simpleExpression());
            String op = ctx.additiveoperator().getText();

            if (leftVal instanceof Integer left && rightVal instanceof Integer right) {
                return switch (op) {
                    case "+" -> left + right;
                    case "-" -> left - right;
                    default -> throw new RuntimeException("Unsupported operator: " + op);
                };
            } else {
                throw new RuntimeException("Operands must be integers, got: "
                        + leftVal.getClass().getSimpleName() + " and " + rightVal.getClass().getSimpleName());
            }
        }

        return leftVal;
    }

    @Override
    public Object visitVariableDeclaration(delphiParser.VariableDeclarationContext ctx) {
        // Example: I: Integer;
        List<delphiParser.IdentifierContext> ids = ctx.identifierList().identifier();
        for (delphiParser.IdentifierContext idCtx : ids) {
            String varName = idCtx.getText();
            variableStack.peek().put(varName, null);
        }
        return null;
    }

    @Override
    public Object visitAssignmentStatement(delphiParser.AssignmentStatementContext ctx) {
        String varText = ctx.variable().getText();
        Object value = visit(ctx.expression());

        // Handle object field assignment: Obj.X := 10;
        if (varText.contains(".")) {
            String[] parts = varText.split("\\.");
            if (parts.length == 2) {
                String objName = parts[0];
                String fieldName = parts[1];
                Object objRaw = getVariable(objName);

                if (objRaw instanceof ObjectInstance obj) {
                    obj.setField(fieldName, value);
                    return null;
                } else {
                    throw new RuntimeException("Variable '" + objName + "' is not an object.");
                }
            }
        }

        // Assign to field inside object method
        if (currentObject != null && currentObject.hasField(varText)) {
            currentObject.setField(varText, value);
            return null;
        }

        setVariable(varText, value);
        return null;
    }

    @Override
    public Object visitWriteln(delphiParser.WritelnContext ctx) {
        if (ctx.writeArguments() != null) {
            List<delphiParser.WriteArgContext> args = ctx.writeArguments().writeArg();
            for (delphiParser.WriteArgContext arg : args) {
                Object value = visit(arg);
                System.out.print(value + " ");
            }
        }
        System.out.println(); // Move to next line after all args
        return null;
    }

    @Override
    public Object visitWriteArg(delphiParser.WriteArgContext ctx) {
        if (ctx.writeExpr() != null) {
            return visit(ctx.writeExpr());
        } else if (ctx.STRING_LITERAL() != null) {
            // Remove surrounding quotes
            String raw = ctx.STRING_LITERAL().getText();
            return raw.substring(1, raw.length() - 1).replace("''", "'");
        }
        return null;
    }

    @Override
    public Object visitWriteExpr(delphiParser.WriteExprContext ctx) {
        if (ctx.IDENT().size() == 2) {
            String objName = ctx.IDENT(0).getText();
            String fieldName = ctx.IDENT(1).getText();
            ObjectInstance obj = (ObjectInstance) getVariable(objName);
            return obj.getField(fieldName);
        } else if (ctx.IDENT().size() == 1) {
            return getVariable(ctx.IDENT(0).getText());
        }
        return null;
    }

    @Override
    public Object visitBreak(delphiParser.BreakContext ctx) {
        throw new BreakSignal();
    }

    @Override
    public Object visitContinue(delphiParser.ContinueContext ctx) {
        throw new ContinueSignal();
    }

    @Override
    public Object visitIfStatement(delphiParser.IfStatementContext ctx) {
        if (isConditionTrue(ctx.expression())) {
            visit(ctx.statement(0));
        } else if (ctx.statement().size() > 1) {
            visit(ctx.statement(1)); // ELSE block
        }
        return null;
    }

    @Override
    public Object visitWhileStatement(delphiParser.WhileStatementContext ctx) {
        while (isConditionTrue(ctx.expression())) {
            variableStack.push(new HashMap<>()); // New scope for each iteration
            try {
                visit(ctx.statement());
            } catch (ContinueSignal e) {
                // continue to next iteration
            } catch (BreakSignal e) {
                if(debugOn())
                    printVariableStack();
                variableStack.pop(); // Clean up scope
                break;
            }
            if(debugOn())
                printVariableStack();
            variableStack.pop(); // Clean up after each iteration
        }
        return null;
    }

    @Override
    public Object visitForStatement(delphiParser.ForStatementContext ctx) {
        String loopVar = ctx.identifier().getText();

        int start = (int) visit(ctx.forList().initialValue().expression());
        int end = (int) visit(ctx.forList().finalValue().expression());
        boolean isTo = ctx.forList().TO() != null;

        for (int i = start; isTo ? i <= end : i >= end; i += isTo ? 1 : -1) {
            variableStack.peek().put(loopVar, i); // Always in outer (global) scope

            variableStack.push(new HashMap<>()); // Loop body scope
            try {
                visit(ctx.statement());
            } catch (ContinueSignal e) {
                // skip to next iteration
            } catch (BreakSignal e) {
                if(debugOn())
                    printVariableStack();
                variableStack.pop(); // Clean up
                break;
            }
            if(debugOn())
                printVariableStack();
            variableStack.pop(); // Clean up after each iteration
        }

        return null;
    }

    @Override
    public Object visitProcedureDeclaration(delphiParser.ProcedureDeclarationContext ctx) {
        String procName = ctx.identifier().getText();
        procedures.put(procName, ctx);
        return null;
    }

    @Override
    public Object visitFunctionDeclaration(delphiParser.FunctionDeclarationContext ctx) {
        String funcName = ctx.identifier().getText();
        functions.put(funcName, ctx);

        // Pre-define the function name as a global variable to hold return value
        setVariable(funcName, null);
        return null;
    }

    @Override
    public Object visitProcedureStatement(delphiParser.ProcedureStatementContext ctx) {
        String procName = ctx.identifier().getText();

        // Handle object method call: Obj.SetX(42)
        if (procName.contains(".")) {
            String[] parts = procName.split("\\.");
            if (parts.length == 2) {
                String objName = parts[0];
                String methodName = parts[1];

                Object objRaw = getVariable(objName);
                if (objRaw instanceof ObjectInstance obj) {
                    String className = obj.className;

                    // Look up method definition
                    String fullMethodName = className + "." + methodName;
                    if (methodName.equals("Destroy") && destructorDefs.containsKey(className)) {
                        delphiParser.DestructorDefinitionContext dtorCtx = destructorDefs.get(className);

                        ObjectInstance prev = currentObject;
                        currentObject = obj;
                        variableStack.push(new HashMap<>());
                        variableStack.peek().put("this", obj);

                        visit(dtorCtx.block());

                        variableStack.pop();
                        currentObject = prev;
                        return null;
                    }
                    if (procedures.containsKey(fullMethodName)) {
                        delphiParser.ProcedureDeclarationContext methodCtx = procedures.get(fullMethodName);

                        // Push object context and method scope
                        ObjectInstance prev = currentObject;
                        currentObject = obj;
                        variableStack.push(new HashMap<>());

                        // Set parameters if any
                        if (ctx.parameterList() != null) {
                            List<delphiParser.ExpressionContext> actualArgs = ctx.parameterList().actualParameter().stream()
                                    .map(delphiParser.ActualParameterContext::expression).toList();

                            List<delphiParser.ParameterGroupContext> params = methodCtx.formalParameterList().formalParameterSection().stream()
                                    .map(delphiParser.FormalParameterSectionContext::parameterGroup)
                                    .toList();

                            for (int i = 0; i < actualArgs.size(); i++) {
                                String paramName = params.get(i).identifierList().identifier(0).getText();
                                Object value = visit(actualArgs.get(i));
                                setVariable(paramName, value);
                            }
                        }

                        visit(methodCtx.block());
                        if(debugOn())
                            printVariableStack();
                        variableStack.pop();
                        currentObject = prev;
                        return null;
                    } else {
                        throw new RuntimeException("Undefined method: " + fullMethodName);
                    }
                } else {
                    throw new RuntimeException("Variable '" + objName + "' is not an object.");
                }
            }
        }

        if (procedures.containsKey(procName)) {
            delphiParser.ProcedureDeclarationContext procDecl = procedures.get(procName);

            variableStack.push(new HashMap<>()); // New local scope

            // Bind parameters if provided
            if (procDecl.formalParameterList() != null && ctx.parameterList() != null) {
                List<delphiParser.ExpressionContext> actualArgs = ctx.parameterList().actualParameter().stream()
                        .map(delphiParser.ActualParameterContext::expression).toList();

                List<delphiParser.ParameterGroupContext> params = procDecl.formalParameterList().formalParameterSection().stream()
                        .map(delphiParser.FormalParameterSectionContext::parameterGroup)
                        .toList();

                List<String> paramNames = new ArrayList<>();
                for (delphiParser.ParameterGroupContext paramGroup : params) {
                    for (delphiParser.IdentifierContext idCtx : paramGroup.identifierList().identifier()) {
                        paramNames.add(idCtx.getText());
                    }
                }

                if (paramNames.size() != actualArgs.size()) {
                    throw new RuntimeException("Parameter count mismatch for procedure: " + procName);
                }

                for (int i = 0; i < paramNames.size(); i++) {
                    String paramName = paramNames.get(i);
                    Object value = visit(actualArgs.get(i));
                    setVariable(paramName, value);
                }
            }

            visit(procDecl.block());

            if (debugOn())
                printVariableStack();
            variableStack.pop();
            return null;
        }

        throw new RuntimeException("Undefined procedure: " + procName);
    }

    @Override
    public Object visitMethodDeclaration(delphiParser.MethodDeclarationContext ctx) {
        if (currentClassName == null) {
            throw new RuntimeException("Method declared outside of class context.");
        }

        String methodName = ctx.identifier().getText();

        if (classDefs.containsKey(currentClassName)) {
            classDefs.get(currentClassName).methods.put(methodName, ctx);
            if(debugOn())
                System.out.println("Stored method: " + currentClassName + "." + methodName);
        } else {
            throw new RuntimeException("Class '" + currentClassName + "' not found while storing method '" + methodName + "'");
        }

        return null;
    }

    @Override
    public Object visitTypeDefinition(delphiParser.TypeDefinitionContext ctx) {
        String typeName = ctx.identifier().getText();
        currentClassName = typeName;

        if (ctx.classType() != null) {
            ClassDef classDef = (ClassDef) visit(ctx.classType());
            classDef.name = typeName;
            classDefs.put(typeName, classDef);
            visit(ctx.classType());
        }

        currentClassName = null;

        return null;
    }

    @Override
    public Object visitClassType(delphiParser.ClassTypeContext ctx) {
        ClassDef classDef = new ClassDef(currentClassName); // Use already-set currentClassName

        // Visit each visibility section to find fields and methods
        for (delphiParser.VisibilitySectionContext sectionCtx : ctx.classBlock().visibilitySection()) {
            for (delphiParser.ClassMemberContext member : sectionCtx.classMember()) {
                if (member.variableDeclaration() != null) {
                    List<delphiParser.IdentifierContext> ids = member.variableDeclaration().identifierList().identifier();
                    String type = member.variableDeclaration().type_().getText();

                    for (delphiParser.IdentifierContext idCtx : ids) {
                        classDef.fields.put(idCtx.getText(), type);
                    }
                } else if (member.methodDeclaration() != null) {
                    String methodName = member.methodDeclaration().identifier().getText();
                    classDef.methods.put(methodName, member.methodDeclaration());
                }
            }
        }

        classDefs.put(currentClassName, classDef);
        return classDef;
    }

    @Override
    public Object visitConstructorDefinition(delphiParser.ConstructorDefinitionContext ctx) {
        String className = ctx.identifier().IDENT(0).getText();
        if(debugOn())
            System.out.println("Constructor stored for class: " + className);
        constructorDefs.put(className, ctx);
        return null;
    }

    @Override
    public Object visitDestructorDefinition(delphiParser.DestructorDefinitionContext ctx) {
        String className = ctx.identifier().IDENT(0).getText();  // TMyClass
        if(debugOn())
            System.out.println("Destructor stored for class: " + className);
        destructorDefs.put(className, ctx);
        return null;
    }

    @Override
    public Object visitFactor(delphiParser.FactorContext ctx) {
        if (ctx.functionDesignator() != null) {
            return visit(ctx.functionDesignator());
        } else if (ctx.variable() != null) {
            String varText = ctx.variable().getText();

            // Object constructor call
            if (varText.endsWith(".Create")) {
                String className = varText.replace(".Create", "");
                if (!classDefs.containsKey(className)) {
                    throw new RuntimeException("Class '" + className + "' not defined.");
                }

                ObjectInstance newObj = new ObjectInstance(className, classDefs.get(className).fields);
                ObjectInstance prev = currentObject;
                currentObject = newObj;

                variableStack.push(new HashMap<>());
                variableStack.peek().put("this", newObj);

                if (constructorDefs.containsKey(className)) {
                    visit(constructorDefs.get(className).block());
                }

                if(debugOn())
                    printVariableStack();
                variableStack.pop();
                currentObject = prev;
                return newObj;
            }

            // Object field access like Obj.X
            if (varText.contains(".")) {
                String[] parts = varText.split("\\.");
                if (parts.length == 2) {
                    String objName = parts[0];
                    String fieldName = parts[1];
                    Object objRaw = getVariable(objName);
                    if (objRaw instanceof ObjectInstance obj) {
                        return obj.getField(fieldName);
                    } else {
                        throw new RuntimeException("Variable '" + objName + "' is not an object.");
                    }
                }
            }

            return getVariable(varText);
        } else if (ctx.unsignedConstant() != null) {
            return visit(ctx.unsignedConstant());
        } else if (ctx.expression() != null) {
            return visit(ctx.expression());
        }

        return null;
    }

    @Override
    public Object visitFunctionDesignator(delphiParser.FunctionDesignatorContext ctx) {
        String funcName = ctx.identifier().getText();

        if (!functions.containsKey(funcName)) {
            throw new RuntimeException("Function not defined: " + funcName);
        }

        delphiParser.FunctionDeclarationContext funcDecl = functions.get(funcName);

        // === Push new local scope ===
        Map<String, Object> localScope = new HashMap<>();
        variableStack.push(localScope);

        // Handle parameters
        if (funcDecl.formalParameterList() != null && ctx.parameterList() != null) {
            // Get formal parameter names
            List<String> paramNames = new ArrayList<>();

            for (delphiParser.FormalParameterSectionContext sectionCtx : funcDecl.formalParameterList().formalParameterSection()) {
                delphiParser.ParameterGroupContext paramGroup = sectionCtx.parameterGroup();
                if (paramGroup != null) {
                    for (delphiParser.IdentifierContext idCtx : paramGroup.identifierList().identifier()) {
                        paramNames.add(idCtx.getText());
                    }
                }
            }

            // Get actual argument values
            List<delphiParser.ActualParameterContext> actuals = ctx.parameterList().actualParameter();

            if (paramNames.size() != actuals.size()) {
                throw new RuntimeException("Parameter count mismatch for function: " + funcName);
            }

            // Bind actual to local scope
            for (int i = 0; i < paramNames.size(); i++) {
                String paramName = paramNames.get(i);
                Object value = visit(actuals.get(i));
                localScope.put(paramName, value);
            }
        }

        // Predefine return variable
        localScope.put(funcName, null);

        // Execute function block
        visit(funcDecl.block());

        // Get return value
        Object returnValue = localScope.get(funcName);

        // Pop local scope
        if(debugOn())
            printVariableStack();
        variableStack.pop();

        return returnValue;
    }

    // ============== VISIT FUNCTIONS END ==============

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Enter file name to parse");
            System.exit(1);
        }

        String filePath = "TestCases/";
        String fileName = args[0];
        String content = Files.readString(Path.of(filePath + fileName), StandardCharsets.UTF_8);

        boolean debug = args.length == 2 && Boolean.parseBoolean(args[1]);

        ANTLRInputStream input = new ANTLRInputStream(content);
        delphiLexer delphiLexer = new delphiLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(delphiLexer);
        delphiParser delphiParser = new delphiParser(tokens);

        ParseTree tree = delphiParser.program();
        DelphiInterpreter interpreter = new DelphiInterpreter(debug);
        System.out.println("-------DELPHI EXECUTION BEGINS-------");
        interpreter.variableStack.push(new HashMap<>());
        interpreter.visit(tree);
        System.out.println("-------DELPHI EXECUTION ENDS-------");
        if(interpreter.debugOn()) {
            System.out.println("Global variables: " + interpreter.variableStack);
            System.out.println("Defined Classes:");
            for (String className : interpreter.classDefs.keySet()) {
                System.out.println(interpreter.classDefs.get(className));
            }
            interpreter.printVariableStack();
        }
    }
}
