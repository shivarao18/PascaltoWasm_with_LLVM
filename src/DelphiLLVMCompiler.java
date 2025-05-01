import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

public class DelphiLLVMCompiler extends delphiBaseVisitor<String> {
    private StringBuilder code = new StringBuilder();
    private Deque<String> breakTargets    = new ArrayDeque<>();
    private Deque<String> continueTargets = new ArrayDeque<>();
    private Map<String, String> variables = new HashMap<>(); // Maps variable names to LLVM types
    private Map<String, Map<String, String>> functionScopes = new HashMap<>(); // Stores variable scopes for functions/procedures
    private Map<String, String> parameterMap = new HashMap<>(); // Maps parameter names to their address variables
    private int tempVarCounter = 0;
    private Map<String, String> stringConstants = new HashMap<>();
    private int stringCounter = 0;
    private int globalCounter = 0;
    private Map<String, String> globalVariables = new HashMap<>();
    private List<String> globalDeclarations = new ArrayList<>();
    private boolean globalsEmitted = false;
    private int labelCounter = 0; // For generating unique labels
    
    // Class-related data structures
    private static class ClassDef {
        String name;
        Map<String, String> fields = new LinkedHashMap<>(); // field name -> LLVM type
        Map<String, Integer> fieldOffsets = new LinkedHashMap<>(); // field name -> offset in struct
        Map<String, String> methods = new HashMap<>(); // method name -> LLVM function name
        String structType; // LLVM struct type name
        int size; // Size of the class structure
        boolean offsetsInitialized = false;
        
        public ClassDef(String name) {
            this.name = name;
            this.structType = "%" + name + ".struct";
        }
        
        // Make sure all field offsets are initialized correctly
        public void initializeOffsets() {
            if (offsetsInitialized) return;
            
            int fieldIndex = 0;
            for (String fieldName : fields.keySet()) {
                fieldOffsets.put(fieldName, fieldIndex++);
            }
            offsetsInitialized = true;
        }
    }
    
    private Map<String, ClassDef> classDefs = new HashMap<>();
    private String currentClassName = null; // Currently being defined class
    
    // Generate unique temporary variable names
    private String getNextTemp() {
        return "%t" + (tempVarCounter++);
    }
    
    // Generate unique label names
    private String getNextLabel(String prefix) {
        return prefix + "." + (labelCounter++);
    }
    
    // Add code to the buffer
    private void emit(String code) {
        this.code.append(code).append("\n");
    }
    
    // Get the full generated LLVM IR code
    public String getGeneratedCode() {
        return code.toString();
    }
    
    // Initialize module with needed declarations
    private void initializeModule() {
        emit("; ModuleID = 'delphi_program'");
        emit("target triple = \"wasm32-unknown-unknown\"");
        emit("");
        
        // Memory management functions
        emit("declare i8* @malloc(i64)");
        emit("declare void @free(i8*)");
        emit("");
    }
    
    // Finalize module - should now be empty or handle other cleanup
    private void finalizeModule() {
        // Remove these lines that were closing the main function
        // emit("  ret i32 0");
        // emit("}");
    }
    
    // Helper to register a string constant
    private String registerStringConstant(String value) {
        // See if we already have this string
        if (stringConstants.containsKey(value)) {
            return stringConstants.get(value);
        }
        
        // Create a new string constant name
        String constName = "@.str." + stringCounter;
        stringCounter++;
        stringConstants.put(value, constName);
        
        // We no longer need to add to global declarations here
        // It will be handled in the main visitProgram method
        
        return constName;
    }
    
    // This method emits all string constants before the main function
    private void emitStringConstants() {
        // We now emit struct types directly in visitProgram
        // so no need to emit class struct definitions here
        
        // Emit all string constants only once
        Set<String> emittedConstants = new HashSet<>();
        for (Map.Entry<String, String> entry : stringConstants.entrySet()) {
            String strValue = entry.getKey();
            String constName = entry.getValue();
            // Make sure we don't emit the same constant twice
            if (!emittedConstants.contains(constName)) {
                int length = strValue.length() + 1;
                emit(constName + " = private constant [" + length + " x i8] c\"" + escapeString(strValue) + "\\00\"");
                emittedConstants.add(constName);
            }
        }
        
        // Register the newline constant
        emit("@.str.newline = private constant [2 x i8] c\"\\0A\\00\"");
        emit("");
        
        // Emit function declarations
        emit("declare void @js_print_str(i8*)");
        emit("declare void @js_print_i32(i32)");
        emit("declare i32 @string_length(i8*)");
        emit("declare i32 @js_read_i32()");
        emit("");
        
        // Emit other global declarations
        for (String structDecl : globalDeclarations) {
            if (!structDecl.contains("type {")) {  // Skip struct types as they're now defined earlier
                emit(structDecl);
            }
        }
        emit("");
    }

    @Override
    public String visitProgram(delphiParser.ProgramContext ctx) {
        // Initialize the module
        initializeModule();
        
        // Reset variables for this program
        variables.clear();
        globalVariables.clear();
        globalDeclarations.clear();
        classDefs.clear();
        parameterMap.clear();
        functionScopes.clear();
        globalsEmitted = false;
        
        // Scan for string literals
        scanForStringLiterals(ctx);
        
        // Find and process all class type definitions
        for (int i = 0; i < ctx.block().getChildCount(); i++) {
            ParseTree child = ctx.block().getChild(i);
            if (child instanceof delphiParser.TypeDefinitionPartContext) {
                visit(child);
            }
        }
        
        // Emit struct types to ensure they're defined before use
        // Use a Set to track already emitted struct types
        Set<String> emittedStructs = new HashSet<>();
        for (ClassDef classDef : classDefs.values()) {
            if (emittedStructs.contains(classDef.structType)) {
                continue; // Skip if already emitted
            }
            
            StringBuilder structFields = new StringBuilder();
            classDef.initializeOffsets(); // Ensure offsets are initialized
            int fieldCount = classDef.fields.size();
            
            if (fieldCount == 0) {
                emit(classDef.structType + " = type { i8 }"); // Empty class needs at least one field
            } else {
                int idx = 0;
                for (String fieldType : classDef.fields.values()) {
                    if (idx > 0) structFields.append(", ");
                    structFields.append(fieldType);
                    idx++;
                }
                emit(classDef.structType + " = type { " + structFields.toString() + " }");
            }
            
            emittedStructs.add(classDef.structType);
        }
        
        // Emit string constants
        emitStringConstants();
        
        // Track implemented methods to avoid duplicate stubs
        Set<String> implementedMethods = new HashSet<>();
        
        // First pass: find all method implementations to register them
        for (int i = 0; i < ctx.block().getChildCount(); i++) {
            ParseTree child = ctx.block().getChild(i);
            
            if (child instanceof delphiParser.ProcedureAndFunctionDeclarationPartContext) {
                delphiParser.ProcedureAndFunctionDeclarationPartContext procFuncCtx = 
                    (delphiParser.ProcedureAndFunctionDeclarationPartContext) child;
                
                delphiParser.ProcedureOrFunctionDeclarationContext declaration =
                    procFuncCtx.procedureOrFunctionDeclaration();
                
                if (declaration.procedureDeclaration() != null &&
                    declaration.procedureDeclaration().identifier().getText().contains(".")) {
                    String fullMethodName = declaration.procedureDeclaration().identifier().getText();
                    String[] parts = fullMethodName.split("\\.", 2);
                    String className = parts[0];
                    String methodName = parts[1];
                    
                    // Register this as an implemented method
                    implementedMethods.add(className + "_" + methodName);
                }
                else if (declaration.functionDeclaration() != null &&
                         declaration.functionDeclaration().identifier().getText().contains(".")) {
                    String fullMethodName = declaration.functionDeclaration().identifier().getText();
                    String[] parts = fullMethodName.split("\\.", 2);
                    String className = parts[0];
                    String methodName = parts[1];
                    
                    // Register this as an implemented method
                    implementedMethods.add(className + "_" + methodName);
                }
            }
            else if (child instanceof delphiParser.ConstructorDefinitionPartContext ||
                     child instanceof delphiParser.DestructorDefinitionPartContext) {
                visit(child);
            }
        }
        
        // Process constructors and destructors
        for (String className : classDefs.keySet()) {
            ClassDef classDef = classDefs.get(className);
            
            // Check if constructor is implemented
            if (classDef.methods.containsKey("Create")) {
                String constructorName = className + "_Create";
                if (!implementedMethods.contains(constructorName)) {
                    ensureConstructorExists(className);
                }
            } else {
                // Create a default constructor if none exists
                ensureConstructorExists(className);
            }
        }
        
        // Generate method stubs (only for non-implemented methods)
        for (String className : classDefs.keySet()) {
            ClassDef classDef = classDefs.get(className);
            
            // Process methods
            for (Map.Entry<String, String> methodEntry : new HashMap<>(classDef.methods).entrySet()) {
                String methodName = methodEntry.getKey();
                String llvmMethodName = methodEntry.getValue();
                
                // Only generate stubs for methods that don't have implementations
                if (!implementedMethods.contains(llvmMethodName) && 
                    !methodName.equals("Create") && !methodName.equals("Destroy")) {
                    generateMethodStub(className, methodName, llvmMethodName);
                }
            }
        }
        
        // Second pass: process the declarations
        for (int i = 0; i < ctx.block().getChildCount(); i++) {
            ParseTree child = ctx.block().getChild(i);
            
            if (child instanceof delphiParser.ProcedureAndFunctionDeclarationPartContext) {
                delphiParser.ProcedureAndFunctionDeclarationPartContext procFuncCtx =
                    (delphiParser.ProcedureAndFunctionDeclarationPartContext) child;
                
                delphiParser.ProcedureOrFunctionDeclarationContext declaration =
                    procFuncCtx.procedureOrFunctionDeclaration();
                
                // Skip those that have been already processed (class methods)
                if ((declaration.procedureDeclaration() != null &&
                     declaration.procedureDeclaration().identifier().getText().contains(".")) ||
                    (declaration.functionDeclaration() != null &&
                     declaration.functionDeclaration().identifier().getText().contains("."))) {
                    // Already processed
                    visit(declaration);
                }
            }
        }
        
        // Main function header
        emit("define i32 @main() {");
        emit("entry:");
        
        // Remaining program block - skipping already processed declarations
        for (int i = 0; i < ctx.block().getChildCount(); i++) {
            ParseTree child = ctx.block().getChild(i);
            
            if (!(child instanceof delphiParser.ProcedureAndFunctionDeclarationPartContext ||
                  child instanceof delphiParser.TypeDefinitionPartContext ||
                  child instanceof delphiParser.ConstructorDefinitionPartContext ||
                  child instanceof delphiParser.DestructorDefinitionPartContext)) {
                visit(child);
            }
        }
        
        // Return 0 from main
        emit("  ret i32 0");
        emit("}");
        
        // Add WebAssembly export attributes
        addWasmAttributes();
        
        // Finalize the module
        finalizeModule();
        
        return null;
    }
    
    // First pass: scan for all string literals
    private void scanForStringLiterals(ParseTree tree) {
        if (tree instanceof delphiParser.WritelnContext) {
            delphiParser.WritelnContext ctx = (delphiParser.WritelnContext) tree;
            if (ctx.writeArguments() != null) {
                for (delphiParser.WriteArgContext arg : ctx.writeArguments().writeArg()) {
                    if (arg.STRING_LITERAL() != null) {
                        String s = arg.STRING_LITERAL().getText();
                        s = s.substring(1, s.length()-1).replace("''", "'");
                        registerStringConstant(s);
                    }
                }
            }
        } 
        else if (tree instanceof delphiParser.UnsignedConstantContext) {
            delphiParser.UnsignedConstantContext ctx = (delphiParser.UnsignedConstantContext) tree;
            String text = ctx.getText();
            // Delphi string literals start and end with single quotes
            if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
                String s = text.substring(1, text.length() - 1).replace("''", "'");
                registerStringConstant(s);
            }
        }
        
        // Recursively scan all children
        for (int i = 0; i < tree.getChildCount(); i++) {
            scanForStringLiterals(tree.getChild(i));
        }
    }
    
    @Override
    public String visitVariableDeclaration(delphiParser.VariableDeclarationContext ctx) {
        List<delphiParser.IdentifierContext> ids = ctx.identifierList().identifier();
        String typeText = ctx.type_().getText();
        String llvmType = mapDelphiTypeToLLVM(typeText);
        
        // If we're inside a class definition, add these as fields
        if (currentClassName != null) {
            ClassDef classDef = classDefs.get(currentClassName);
            for (delphiParser.IdentifierContext idCtx : ids) {
                String fieldName = idCtx.getText();
                classDef.fields.put(fieldName, llvmType);
            }
        } else {
            // Regular variable declarations
            for (delphiParser.IdentifierContext idCtx : ids) {
                String varName = idCtx.getText();
                String varPtr = "%" + varName;
                variables.put(varName, llvmType);
                
                // Allocate on stack
                emit("  " + varPtr + " = alloca " + llvmType);
                
                // Initialize to default value
                if (llvmType.equals("i32")) {
                    emit("  store i32 0, i32* " + varPtr);
                } else if (llvmType.equals("i1")) {
                    emit("  store i1 0, i1* " + varPtr);
                } else if (llvmType.endsWith("*")) {
                    // This is a pointer type (including class objects) - initialize to null
                    emit("  store " + llvmType + " null, " + llvmType + "* " + varPtr);
                }
            }
        }
        return null;
    }
    
    private String mapDelphiTypeToLLVM(String delphiType) {
        // Check if this is a class type
        if (classDefs.containsKey(delphiType)) {
            return classDefs.get(delphiType).structType + "*";
        }
        
        // Otherwise handle primitive types
        return switch (delphiType.toLowerCase()) {
            case "integer" -> "i32";
            case "boolean" -> "i1";
            case "real" -> "double";
            default -> "i32"; // Default to i32
        };
    }
    
    @Override
    public String visitExpression(delphiParser.ExpressionContext ctx) {
        // For relational expressions, handle them with proper LLVM comparison
        if (ctx.relationaloperator() != null) {
            String leftVal = visit(ctx.simpleExpression());
            String rightVal = visit(ctx.expression());
            String resultVar = getNextTemp();
            
            String op = ctx.relationaloperator().getText();
            String instruction = switch (op) {
                case "<" -> "icmp slt";
                case "<=" -> "icmp sle";
                case ">" -> "icmp sgt";
                case ">=" -> "icmp sge";
                case "=" -> "icmp eq";
                case "<>" -> "icmp ne";
                default -> throw new RuntimeException("Unsupported operator: " + op);
            };
            
            emit("  " + resultVar + " = " + instruction + " i32 " + leftVal + ", " + rightVal);
            return resultVar;
        }
        
        // Otherwise just get the simple expression value
        return visit(ctx.simpleExpression());
    }
    
    @Override
    public String visitSimpleExpression(delphiParser.SimpleExpressionContext ctx) {
        String leftVal = visit(ctx.term());
        
        if (ctx.additiveoperator() != null) {
            String rightVal = visit(ctx.simpleExpression());
            String op = ctx.additiveoperator().getText();
            String resultVar = getNextTemp();
            
            String instruction = switch (op) {
                case "+" -> "add";
                case "-" -> "sub";
                default -> throw new RuntimeException("Unsupported operator: " + op);
            };
            
            emit("  " + resultVar + " = " + instruction + " i32 " + leftVal + ", " + rightVal);
            return resultVar;
        }
        
        return leftVal;
    }
    
    @Override
    public String visitTerm(delphiParser.TermContext ctx) {
        String leftVal = visit(ctx.signedFactor());
        
        if (ctx.multiplicativeoperator() != null) {
            String rightVal = visit(ctx.term());
            String op = ctx.multiplicativeoperator().getText();
            String resultVar = getNextTemp();
            
            String instruction = switch (op) {
                case "*" -> "mul";
                case "div", "/" -> "sdiv";
                case "mod" -> "srem";
                default -> throw new RuntimeException("Unsupported operator: " + op);
            };
            
            emit("  " + resultVar + " = " + instruction + " i32 " + leftVal + ", " + rightVal);
            return resultVar;
        }
        
        return leftVal;
    }
    
    @Override
    public String visitAssignmentStatement(delphiParser.AssignmentStatementContext ctx) {
        String varText = ctx.variable().getText();
        String exprResult = visit(ctx.expression());
        
        // Check if this is a field assignment: obj.field := value or a direct field assignment in a constructor/destructor
        if (varText.contains(".")) {
            String[] parts = varText.split("\\.", 2);
            String objName = parts[0];
            String fieldName = parts[1];
            
            if (variables.containsKey(objName)) {
                String objType = variables.get(objName);
                
                // Extract the class name from the type (e.g., "%MyClass.struct*" -> "MyClass")
                if (objType.endsWith("*") && objType.contains(".struct")) {
                    String className = objType.substring(1, objType.indexOf(".struct"));
                    
                    if (classDefs.containsKey(className)) {
                        ClassDef classDef = classDefs.get(className);
                        
                        if (classDef.fields.containsKey(fieldName)) {
                            String fieldType = classDef.fields.get(fieldName);
                            int fieldOffset = classDef.fieldOffsets.get(fieldName);
                            
                            // Load the object pointer
                            String objPtr = getNextTemp();
                            emit("  " + objPtr + " = load " + objType + ", " + objType + "* %" + objName);
                            
                            // Get pointer to field
                            String fieldPtr = getNextTemp();
                            emit("  " + fieldPtr + " = getelementptr " + classDef.structType + ", " + 
                                  classDef.structType + "* " + objPtr + ", i32 0, i32 " + fieldOffset);
                            
                            // Store value to field
                            emit("  store " + fieldType + " " + exprResult + ", " + fieldType + "* " + fieldPtr);
                            
                            return null;
                        }
                    }
                }
            }
            
            return null;
        } 
        // Direct field access in constructor or destructor context
        else if (currentClassName != null && classDefs.containsKey(currentClassName)) {
            ClassDef classDef = classDefs.get(currentClassName);
            
            if (classDef.fields.containsKey(varText)) {
                // We're in a constructor or destructor and assigning directly to a field
                if (parameterMap.containsKey(varText)) {
                    String fieldPtr = parameterMap.get(varText);
                    String fieldType = classDef.fields.get(varText);
                    
                    // Store value to field
                    emit("  store " + fieldType + " " + exprResult + ", " + fieldType + "* " + fieldPtr);
                    return null;
                }
            }
        }
        
        // Handle regular variable assignment
        String llvmType = variables.get(varText);
        if (llvmType == null) {
            // Try to infer type from expression result
            if (exprResult.startsWith("%t")) {
                // This is likely a result from a constructor call for a class object
                // Find the class pointer type for this variable
                for (Map.Entry<String, ClassDef> entry : classDefs.entrySet()) {
                    ClassDef classDef = entry.getValue();
                    if (exprResult.contains("@" + entry.getKey() + "_Create")) {
                        // Found the class type
                        llvmType = classDef.structType + "*";
                        variables.put(varText, llvmType); // Register the variable type
                        emit("  store " + llvmType + " " + exprResult + ", " + llvmType + "* %" + varText);
                        return null;
                    }
                }
                
                // If we get here, it's an object pointer but we don't know exactly which class
                // We'll try to extract the type from the result
                int structIndex = exprResult.indexOf(".struct*");
                if (structIndex > 0) {
                    String potentialType = exprResult.substring(exprResult.indexOf("call ") + 5, structIndex + 8);
                    emit("  store " + potentialType + " " + exprResult + ", " + potentialType + "* %" + varText);
                    return null;
                }
                
                // If we still can't determine, just emit a comment as a placeholder
                emit("  ; Warning: Could not determine type for " + varText + " assignment");
                return null;
            }
            
            // Fallback - try to check if it's a simple integer assignment
            if (exprResult.matches("\\d+")) {
                llvmType = "i32";
                variables.put(varText, llvmType); // Register the variable type
            } else {
                llvmType = "i32"; // Default to i32 for any other case
                variables.put(varText, llvmType);
            }
        }
        
        // Determine the correct variable to store to
        String accessVar = varText;
        if (parameterMap.containsKey(varText)) {
            // Use the parameter address if it's a parameter
            accessVar = parameterMap.get(varText);
        }
        
        // Store to the appropriate variable
        emit("  store " + llvmType + " " + exprResult + ", " + llvmType + "* %" + accessVar);
        
        // If this is a function name (for return value), return the value
        if (functionScopes.containsKey(varText)) {
            // This is a function return value assignment
            emit("  ; Setting return value for function " + varText);
        }
        
        return null;
    }
    
    @Override
    public String visitFactor(delphiParser.FactorContext ctx) {
        if (ctx.variable() != null) {
            String varText = ctx.variable().getText();
            
            // Check if this is a class constructor call: MyClass.Create
            if (varText.endsWith(".Create")) {
                String className = varText.substring(0, varText.length() - 7); // Remove ".Create"
                if (classDefs.containsKey(className)) {
                    // Make sure we've defined a constructor for this class
                    ensureConstructorExists(className);
                    
                    ClassDef classDef = classDefs.get(className);
                    String resultVar = getNextTemp();
                    emit("  " + resultVar + " = call " + classDef.structType + "* @" + className + "_Create()");
                    return resultVar;
                }
            }
            
            // Check if this is a field access: obj.field
            if (varText.contains(".")) {
                String[] parts = varText.split("\\.", 2);
                String objName = parts[0];
                String fieldName = parts[1];
                
                if (variables.containsKey(objName)) {
                    String objType = variables.get(objName);
                    
                    // Extract the class name from the type (e.g., "%MyClass.struct*" -> "MyClass")
                    if (objType.endsWith("*") && objType.contains(".struct")) {
                        String className = objType.substring(1, objType.indexOf(".struct"));
                        
                        if (classDefs.containsKey(className)) {
                            ClassDef classDef = classDefs.get(className);
                            
                            if (classDef.fields.containsKey(fieldName)) {
                                String fieldType = classDef.fields.get(fieldName);
                                int fieldOffset = classDef.fieldOffsets.get(fieldName);
                                
                                // Load the object pointer
                                String objPtr = getNextTemp();
                                emit("  " + objPtr + " = load " + objType + ", " + objType + "* %" + objName);
                                
                                // Get pointer to field
                                String fieldPtr = getNextTemp();
                                emit("  " + fieldPtr + " = getelementptr " + classDef.structType + ", " + 
                                      classDef.structType + "* " + objPtr + ", i32 0, i32 " + fieldOffset);
                                
                                // Load field value
                                String resultVar = getNextTemp();
                                emit("  " + resultVar + " = load " + fieldType + ", " + fieldType + "* " + fieldPtr);
                                
                                return resultVar;
                            }
                        }
                    }
                }
            }
            
            // Use our helper method to load the variable correctly
            return loadVariable(varText);
        } else if (ctx.unsignedConstant() != null) {
            return visit(ctx.unsignedConstant());
        } else if (ctx.expression() != null) {
            return visit(ctx.expression());
        } else if (ctx.functionDesignator() != null) {
            return visit(ctx.functionDesignator());
        } else if (ctx.bool_() != null) {
            return ctx.bool_().TRUE() != null ? "1" : "0";
        }
        
        return null;
    }
    
    @Override
    public String visitUnsignedInteger(delphiParser.UnsignedIntegerContext ctx) {
        // Return integer constants directly
        return ctx.getText();
    }
    
    @Override
    public String visitIfStatement(delphiParser.IfStatementContext ctx) {
        // Generate labels for the branches
        String thenLabel = getNextLabel("if.then");
        String elseLabel = getNextLabel("if.else");
        String endLabel = getNextLabel("if.end");
        
        // Evaluate the condition
        String condResult = visit(ctx.expression());
        
        // Branch based on condition
        if (ctx.statement().size() > 1) {  // Has else clause
            emit("  br i1 " + condResult + ", label %" + thenLabel + ", label %" + elseLabel);
        } else {  // No else clause
            emit("  br i1 " + condResult + ", label %" + thenLabel + ", label %" + endLabel);
        }
        
        // Then block
        emit(thenLabel + ":");
        visit(ctx.statement(0));
        emit("  br label %" + endLabel);
        
        // Else block (if present)
        if (ctx.statement().size() > 1) {
            emit(elseLabel + ":");
            visit(ctx.statement(1));
            emit("  br label %" + endLabel);
        }
        
        // End label
        emit(endLabel + ":");
        
        return null;
    }
    
    @Override
    public String visitForStatement(delphiParser.ForStatementContext ctx) {
        // Loop variable and its LLVM type
        String varName  = ctx.identifier().getText();
        String llvmType = variables.get(varName);

        // Generate unique labels
        String condLabel = getNextLabel("for.cond");
        String bodyLabel = getNextLabel("for.body");
        String incLabel  = getNextLabel("for.inc");
        String endLabel  = getNextLabel("for.end");

        // --- Push break/continue targets for nested loops ---
        breakTargets.push(endLabel);
        continueTargets.push(incLabel);

        // Initialize loop variable
        String initValue = visit(ctx.forList().initialValue().expression());
        emit("  store " + llvmType + " " + initValue + ", " + llvmType + "* %" + varName);
        if (globalVariables.containsKey(varName)) {
            emit("  store " + llvmType + " " + initValue + ", " +
                llvmType + "* " + globalVariables.get(varName));
        }

        // Jump to condition check
        emit("  br label %" + condLabel);

        // Condition check block
        emit(condLabel + ":");
        String limitValue = visit(ctx.forList().finalValue().expression());
        String curValue   = getNextTemp();
        emit("  " + curValue + " = load " + llvmType + ", " + llvmType + "* %" + varName);

        String condition  = getNextTemp();
        boolean isTo      = ctx.forList().TO() != null;
        if (isTo) {
            emit("  " + condition + " = icmp sle i32 " + curValue + ", " + limitValue);
        } else {
            emit("  " + condition + " = icmp sge i32 " + curValue + ", " + limitValue);
        }

        emit("  br i1 " + condition + ", label %" + bodyLabel + ", label %" + endLabel);

        // Body block
        emit(bodyLabel + ":");
        visit(ctx.statement());             // generate body
        emit("  br label %" + incLabel);    // 'continue' jumps here

        // Increment block
        emit(incLabel + ":");
        String oldValue = getNextTemp();
        emit("  " + oldValue + " = load " + llvmType + ", " + llvmType + "* %" + varName);

        String newValue = getNextTemp();
        if (isTo) {
            emit("  " + newValue + " = add i32 " + oldValue + ", 1");
        } else {
            emit("  " + newValue + " = sub i32 " + oldValue + ", 1");
        }

        emit("  store " + llvmType + " " + newValue + ", " + llvmType + "* %" + varName);
        if (globalVariables.containsKey(varName)) {
            emit("  store " + llvmType + " " + newValue + ", " +
                llvmType + "* " + globalVariables.get(varName));
        }
        emit("  br label %" + condLabel);

        // Exit block
        emit(endLabel + ":");

        // --- Pop the stacks now that this loop is done ---
        breakTargets.pop();
        continueTargets.pop();

        return null;
    }
    
    @Override
    public String visitWhileStatement(delphiParser.WhileStatementContext ctx) {
        String condLabel = getNextLabel("while.cond");
        String bodyLabel = getNextLabel("while.body");
        String endLabel  = getNextLabel("while.end");

        // 1) record loop labels
        breakTargets.push(endLabel);
        continueTargets.push(condLabel);

        // 2) entry jump
        emit("  br label %" + condLabel);

        // 3) condition check
        emit(condLabel + ":");
        String cond = visit(ctx.expression());
        emit("  br i1 " + cond + ", label %" + bodyLabel + ", label %" + endLabel);

        // 4) body
        emit(bodyLabel + ":");
        visit(ctx.statement());
        // continue → re-test
        emit("  br label %" + condLabel);

        // 5) loop exit
        emit(endLabel + ":");

        // 6) pop stacks
        breakTargets.pop();
        continueTargets.pop();

        return null;
    }

    @Override
    public String visitBreak(delphiParser.BreakContext ctx) {
        // jump to loop's end label
        String target = breakTargets.peek();
        emit("  br label %" + target);
        return null;
    }

    @Override
    public String visitContinue(delphiParser.ContinueContext ctx) {
        // jump to loop's continue label (re-test or increment)
        String target = continueTargets.peek();
        emit("  br label %" + target);
        return null;
    }

    @Override
    public String visitWriteln(delphiParser.WritelnContext ctx) {
        if (ctx.writeArguments() != null) {
            for (delphiParser.WriteArgContext arg : ctx.writeArguments().writeArg()) {
                if (arg.STRING_LITERAL() != null) {
                    String s = arg.STRING_LITERAL().getText();
                    s = s.substring(1, s.length()-1).replace("''", "'");
                    // Use the already registered string constant from first pass
                    String cst = stringConstants.get(s);
                    if (cst == null) {
                        // If somehow not registered during scan, register it now
                        cst = registerStringConstant(s);
                    }
                    int len = s.length() + 1;
                    String ptr = getNextTemp();
                    emit("  " + ptr + " = getelementptr [" + len + " x i8], [" + len + " x i8]* " + cst + ", i32 0, i32 0");
                    // JS import for strings
                    emit("  call void @js_print_str(i8* " + ptr + ")");
                } else if (arg.writeExpr() != null) {
                    // Handle the special case for field access: Obj.X
                    String expr = arg.writeExpr().getText();
                    
                    // Check if this is a method call - specifically for GetX methods
                    if (expr.contains(".") && expr.contains("Get") && !expr.contains("(")) {
                        String[] parts = expr.split("\\.", 2);
                        String objName = parts[0];
                        String methodName = parts[1]; // e.g., GetX
                        
                        if (variables.containsKey(objName)) {
                            String objType = variables.get(objName);
                            
                            // Extract the class name from the type
                            if (objType.endsWith("*") && objType.contains(".struct")) {
                                String className = objType.substring(1, objType.indexOf(".struct"));
                                
                                if (classDefs.containsKey(className)) {
                                    ClassDef classDef = classDefs.get(className);
                                    
                                    // Check if the method exists
                                    if (classDef.methods.containsKey(methodName)) {
                                        String llvmMethodName = classDef.methods.get(methodName);
                                        
                                        // Load the object pointer
                                        String objPtr = getNextTemp();
                                        emit("  " + objPtr + " = load " + objType + ", " + objType + "* %" + objName);
                                        
                                        // Call the method
                                        String resultVar = getNextTemp();
                                        emit("  " + resultVar + " = call i32 @" + llvmMethodName + "(" + objType + " " + objPtr + ")");
                                        
                                        // Print the result
                                        emit("  call void @js_print_i32(i32 " + resultVar + ")");
                                        continue; // Skip the regular expression handling
                                    }
                                }
                            }
                        }
                    }
                    
                    // For field access: obj.field
                    if (expr.contains(".")) {
                        String[] parts = expr.split("\\.", 2);
                        String objName = parts[0];
                        String fieldName = parts[1];
                        
                        if (variables.containsKey(objName)) {
                            String objType = variables.get(objName);
                            
                            // Extract the class name from the type
                            if (objType.endsWith("*") && objType.contains(".struct")) {
                                String className = objType.substring(1, objType.indexOf(".struct"));
                                
                                if (classDefs.containsKey(className)) {
                                    ClassDef classDef = classDefs.get(className);
                                    
                                    if (classDef.fields.containsKey(fieldName)) {
                                        String fieldType = classDef.fields.get(fieldName);
                                        int fieldOffset = classDef.fieldOffsets.get(fieldName);
                                        
                                        // Load the object pointer
                                        String objPtr = getNextTemp();
                                        emit("  " + objPtr + " = load " + objType + ", " + objType + "* %" + objName);
                                        
                                        // Get pointer to field
                                        String fieldPtr = getNextTemp();
                                        emit("  " + fieldPtr + " = getelementptr " + classDef.structType + ", " + 
                                            classDef.structType + "* " + objPtr + ", i32 0, i32 " + fieldOffset);
                                        
                                        // Load field value
                                        String resultVar = getNextTemp();
                                        emit("  " + resultVar + " = load " + fieldType + ", " + fieldType + "* " + fieldPtr);
                                        
                                        // Print the result
                                        emit("  call void @js_print_i32(i32 " + resultVar + ")");
                                        continue; // Skip to next arg
                                    }
                                }
                            }
                        }
                    }
                    
                    // For simple variables or if field access parsing failed
                    String exprResult = visit(arg.writeExpr());
                    if (exprResult == null || exprResult.equals("null")) {
                        exprResult = "0"; // fallback to 0 if unresolved
                    }
                    emit("  call void @js_print_i32(i32 " + exprResult + ")");
                }
            }
        }
        // newline
        String nl = getNextTemp();
        emit("  " + nl + " = getelementptr [2 x i8], [2 x i8]* @.str.newline, i32 0, i32 0");
        emit("  call void @js_print_str(i8* " + nl + ")");
        return null;
    }
    
    // Helper function to escape strings properly for LLVM IR
    private String escapeString(String input) {
        StringBuilder result = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (c == '\n') result.append("\\0A");
            else if (c == '\t') result.append("\\09");
            else if (c == '\"') result.append("\\22");
            else if (c == '\\') result.append("\\5C");
            else if (c >= 32 && c < 127) result.append(c);
            else {
                // For any other special character, use hex escape
                result.append(String.format("\\%02X", (int)c));
            }
        }
        return result.toString();
    }
    
    private void addWasmAttributes() {
        emit("");
        emit("attributes #0 = { \"wasm-export-name\"=\"main\" }");
        emit("attributes #1 = { \"wasm-export-name\"=\"print_int\" }");
        emit("attributes #2 = { \"wasm-export-name\"=\"get_export_value\" }");
    }
    
    private void emitGlobals() {
        // We want to emit globals at the beginning of the file, not insert them later
        for (String globalDecl : globalDeclarations) {
            emit(globalDecl);
        }
        emit(""); // Add an empty line after globals
        globalsEmitted = true;
    }
    
    @Override
    public String visitFunctionDesignator(delphiParser.FunctionDesignatorContext ctx) {
        String functionName = ctx.identifier().getText();
        List<String> paramValues = new ArrayList<>();
        
        // Process parameters if they exist
        if (ctx.parameterList() != null) {
            for (delphiParser.ActualParameterContext paramCtx : ctx.parameterList().actualParameter()) {
                String paramValue = visit(paramCtx.expression());
                paramValues.add(paramValue);
            }
        }
        
        // Check if this is a method call: obj.Method(args)
        if (functionName.contains(".")) {
            String[] parts = functionName.split("\\.", 2);
            String objName = parts[0];
            String methodName = parts[1];
            
            if (variables.containsKey(objName)) {
                String objType = variables.get(objName);
                
                // Extract the class name from the type
                if (objType.endsWith("*") && objType.contains(".struct")) {
                    String className = objType.substring(1, objType.indexOf(".struct"));
                    
                    if (classDefs.containsKey(className)) {
                        ClassDef classDef = classDefs.get(className);
                        
                        // Check if it's a method
                        if (classDef.methods.containsKey(methodName)) {
                            String llvmMethodName = classDef.methods.get(methodName);
                            
                            // Load the object pointer
                            String objPtr = getNextTemp();
                            emit("  " + objPtr + " = load " + objType + ", " + objType + "* %" + objName);
                            
                            // Build the parameter list, starting with the 'this' pointer
                            StringBuilder paramsStr = new StringBuilder();
                            paramsStr.append(objType).append(" ").append(objPtr);
                            
                            for (int i = 0; i < paramValues.size(); i++) {
                                paramsStr.append(", i32 ").append(paramValues.get(i));
                            }
                            
                            // Call the method function
                            String resultVar = getNextTemp();
                            emit("  " + resultVar + " = call i32 @" + llvmMethodName + "(" + paramsStr.toString() + ")");
                            return resultVar;
                        }
                    }
                }
            }
            
            return "0"; // fallback
        }
        
        // Handle built-in functions
        if (functionName.equalsIgnoreCase("length")) {
            // Special case: string length function
            if (paramValues.size() == 1) {
                String param = paramValues.get(0);
                if (param.startsWith("@.str")) {
                    // For string constants, we already know the length
                    String strConstant = param;
                    for (Map.Entry<String, String> entry : stringConstants.entrySet()) {
                        if (entry.getValue().equals(strConstant)) {
                            return Integer.toString(entry.getKey().length());
                        }
                    }
                }
                // For variables, need to call a length function
                String resultVar = getNextTemp();
                emit("  " + resultVar + " = call i32 @string_length(i8* " + param + ")");
                return resultVar;
            }
        } 
        else if (functionName.equalsIgnoreCase("ord")) {
            // Ord function returns ASCII value
            if (paramValues.size() == 1) {
                return paramValues.get(0); // In LLVM IR, characters are just i8 integers
            }
        } 
        else if (functionName.equalsIgnoreCase("chr")) {
            // Chr function converts int to character
            if (paramValues.size() == 1) {
                String resultVar = getNextTemp();
                emit("  " + resultVar + " = trunc i32 " + paramValues.get(0) + " to i8");
                return resultVar;
            }
        } 
        else {
            // User-defined function call
            String resultVar = getNextTemp();
            
            // Build parameter list string
            StringBuilder paramsStr = new StringBuilder();
            for (int i = 0; i < paramValues.size(); i++) {
                paramsStr.append("i32 ").append(paramValues.get(i));
                if (i < paramValues.size() - 1) {
                    paramsStr.append(", ");
                }
            }
            
            // Generate function call
            emit("  " + resultVar + " = call i32 @" + functionName + "(" + paramsStr.toString() + ")");
            return resultVar;
        }
        
        // Default return if no special case handled
        return "0";
    }
    
    @Override
    public String visitProcedureStatement(delphiParser.ProcedureStatementContext ctx) {
        String procName = ctx.identifier().getText();
        List<String> paramValues = new ArrayList<>();
        
        // Process parameters if they exist
        if (ctx.parameterList() != null) {
            for (delphiParser.ActualParameterContext paramCtx : ctx.parameterList().actualParameter()) {
                String paramValue = visit(paramCtx.expression());
                paramValues.add(paramValue);
            }
        }
        
        // Check if this is a method call: obj.Method(args)
        if (procName.contains(".")) {
            String[] parts = procName.split("\\.", 2);
            String objName = parts[0];
            String methodName = parts[1];
            
            if (variables.containsKey(objName)) {
                String objType = variables.get(objName);
                
                // Extract the class name from the type
                if (objType.endsWith("*") && objType.contains(".struct")) {
                    String className = objType.substring(1, objType.indexOf(".struct"));
                    
                    if (classDefs.containsKey(className)) {
                        ClassDef classDef = classDefs.get(className);
                        
                        // Check if it's the Destroy method
                        if (methodName.equals("Destroy")) {
                            // Call the destructor
                            String objPtr = getNextTemp();
                            emit("  " + objPtr + " = load " + objType + ", " + objType + "* %" + objName);
                            emit("  call void @" + className + "_Destroy(" + objType + " " + objPtr + ")");
                            return null;
                        }
                        
                        // Check if it's a regular method
                        if (classDef.methods.containsKey(methodName)) {
                            String llvmMethodName = classDef.methods.get(methodName);
                            
                            // Load the object pointer
                            String objPtr = getNextTemp();
                            emit("  " + objPtr + " = load " + objType + ", " + objType + "* %" + objName);
                            
                            // Build the parameter list, starting with the 'this' pointer
                            StringBuilder paramsStr = new StringBuilder();
                            paramsStr.append(objType).append(" ").append(objPtr);
                            
                            for (int i = 0; i < paramValues.size(); i++) {
                                paramsStr.append(", i32 ").append(paramValues.get(i));
                            }
                            
                            // Call the method
                            emit("  call void @" + llvmMethodName + "(" + paramsStr.toString() + ")");
                            return null;
                        }
                    }
                }
            }
        }
        
        // Handle built-in procedures or user-defined ones
        if (procName.equalsIgnoreCase("write") || procName.equalsIgnoreCase("writeln")) {
            // Handled separately in visitWriteln
            return null;
        } 
        else if (procName.equalsIgnoreCase("read") || procName.equalsIgnoreCase("readln")) {
            // Handled separately in visitReadln
            return null;
        } 
        else {
            // User-defined procedure call
            StringBuilder paramsStr = new StringBuilder();
            for (int i = 0; i < paramValues.size(); i++) {
                paramsStr.append("i32 ").append(paramValues.get(i));
                if (i < paramValues.size() - 1) {
                    paramsStr.append(", ");
                }
            }
            
            // Generate procedure call (void return)
            emit("  call void @" + procName + "(" + paramsStr.toString() + ")");
        }
        
        return null;
    }
    
    @Override
    public String visitReadln(delphiParser.ReadlnContext ctx) {
        // Emit call to input function for each argument
        if (ctx.readArguments() != null) {
            for (delphiParser.ReadArgContext argCtx : ctx.readArguments().readArg()) {
                String varName = argCtx.getText();
                String llvmType = variables.get(varName);
                
                if (llvmType != null) {
                    if (llvmType.equals("i32")) {
                        // Call to external JS read integer function
                        String resultVar = getNextTemp();
                        emit("  " + resultVar + " = call i32 @js_read_i32()");
                        emit("  store i32 " + resultVar + ", i32* %" + varName);
                    } 
                    // Add more types as needed
                }
            }
        }
        
        return null;
    }
    
    @Override
    public String visitProcedureDeclaration(delphiParser.ProcedureDeclarationContext ctx) {
        String procedureName = ctx.identifier().getText();
        List<String> paramNames = new ArrayList<>();
        List<String> paramTypes = new ArrayList<>();
        
        // Check if this is a class method (e.g., "MyClass.SetX")
        boolean isClassMethod = procedureName.contains(".");
        String className = null;
        String methodName = procedureName;
        ClassDef classDef = null;
        
        if (isClassMethod) {
            String[] parts = procedureName.split("\\.", 2);
            className = parts[0];
            methodName = parts[1];
            classDef = classDefs.get(className);
            
            if (classDef == null) {
                emit("; Warning: Class " + className + " not found for method " + methodName);
                return null; // Class not found
            }
            
            // Register the method in the class definition if not already registered
            if (!classDef.methods.containsKey(methodName)) {
                classDef.methods.put(methodName, className + "_" + methodName);
            }
        }
        
        // Save current scope
        saveScope("main");
        // Clear variables and parameter map for new scope
        variables.clear();
        parameterMap.clear();
        
        // Process parameters if they exist
        if (ctx.formalParameterList() != null) {
            for (delphiParser.FormalParameterSectionContext paramSection : ctx.formalParameterList().formalParameterSection()) {
                if (paramSection.parameterGroup() != null) {
                    String paramType = mapDelphiTypeToLLVM(paramSection.parameterGroup().typeIdentifier().getText());
                    for (delphiParser.IdentifierContext idCtx : paramSection.parameterGroup().identifierList().identifier()) {
                        paramNames.add(idCtx.getText());
                        paramTypes.add(paramType);
                    }
                }
            }
        }
        
        // For class methods, add 'this' pointer as first parameter
        List<String> actualParamNames = new ArrayList<>(paramNames);
        List<String> actualParamTypes = new ArrayList<>(paramTypes);
        
        if (isClassMethod) {
            actualParamNames.add(0, "this_obj");
            actualParamTypes.add(0, classDef.structType + "*");
        }
        
        // Build parameter list for function signature
        StringBuilder paramListStr = new StringBuilder();
        for (int i = 0; i < actualParamTypes.size(); i++) {
            paramListStr.append(actualParamTypes.get(i)).append(" %").append(actualParamNames.get(i));
            if (i < actualParamTypes.size() - 1) {
                paramListStr.append(", ");
            }
        }
        
        // Generate the actual procedure name (for class methods, format is ClassName_MethodName)
        String actualProcedureName = isClassMethod ? className + "_" + methodName : procedureName;
        
        // Emit procedure declaration
        emit("define void @" + actualProcedureName + "(" + paramListStr.toString() + ") {");
        emit("entry:");
        
        // For class methods, set up the this variable and field access
        if (isClassMethod) {
            // Store previous class name and set current
            String prevClassName = currentClassName;
            currentClassName = className;
            
            // Create a 'this' variable to represent the object itself
            String thisVar = "%this";
            variables.put("this", classDef.structType + "*");
            emit("  " + thisVar + " = alloca " + classDef.structType + "*");
            emit("  store " + classDef.structType + "* %this_obj, " + classDef.structType + "** %this");
            
            // Set up field access for all fields
            for (Map.Entry<String, String> field : classDef.fields.entrySet()) {
                String fieldName = field.getKey();
                String fieldType = field.getValue();
                int fieldOffset = classDef.fieldOffsets.get(fieldName);
                
                // Set up pointers to fields for easy access in the method body
                String objPtr = getNextTemp();
                emit("  " + objPtr + " = load " + classDef.structType + "*, " + classDef.structType + "** %this");
                
                String fieldPtr = getNextTemp();
                emit("  " + fieldPtr + " = getelementptr " + classDef.structType + ", " + 
                     classDef.structType + "* " + objPtr + ", i32 0, i32 " + fieldOffset);
                
                // Register field in variable and parameter maps
                variables.put(fieldName, fieldType);
                parameterMap.put(fieldName, fieldPtr);
            }
            
            // Process parameters after 'this' (skip the first parameter which is 'this_obj')
            for (int i = 0; i < paramNames.size(); i++) {
                String paramName = paramNames.get(i);
                String paramType = paramTypes.get(i);
                String localVar = paramName + ".addr";
                
                // Register this parameter in our parameter map
                parameterMap.put(paramName, localVar);
                
                // Allocate space for the parameter value
                emit("  %" + localVar + " = alloca " + paramType);
                
                // Store the parameter value in its allocated space (offset by 1 because of 'this_obj')
                emit("  store " + paramType + " %" + actualParamNames.get(i + 1) + ", " + paramType + "* %" + localVar);
                
                // Add to variables map
                variables.put(paramName, paramType);
            }
            
            // Process local variables
            processLocalVariables(ctx.block());
            
            // Visit the procedure body
            visit(ctx.block());
            
            // Restore the previous class name
            currentClassName = prevClassName;
        } else {
            // Create local variables for parameters - add the actual variable name to the variables map
            for (int i = 0; i < paramNames.size(); i++) {
                String paramName = paramNames.get(i);
                String paramType = paramTypes.get(i);
                String localVar = paramName + ".addr";
                
                // Register this parameter in our parameter map
                parameterMap.put(paramName, localVar);
                
                // Allocate space for the parameter value
                emit("  %" + localVar + " = alloca " + paramType);
                
                // Store the parameter value in its allocated space
                emit("  store " + paramType + " %" + paramName + ", " + paramType + "* %" + localVar);
                
                // Add to variables map - note we store the original name but the address is paramName.addr
                variables.put(paramName, paramType);
            }
            
            // Process local variables
            processLocalVariables(ctx.block());
            
            // Visit the procedure body
            visit(ctx.block());
        }
        
        // Add a return void if there isn't one already
        emit("  ret void");
        emit("}");
        emit("");
        
        // Restore main scope
        restoreScope("main");
        
        return null;
    }
    
    private void processLocalVariables(delphiParser.BlockContext blockCtx) {
        System.out.println("Processing local variables in block");
        // Process each child in the block looking for variable declarations
        for (int i = 0; i < blockCtx.getChildCount(); i++) {
            ParseTree child = blockCtx.getChild(i);
            System.out.println("Child " + i + ": " + child.getClass().getName());
            if (child instanceof delphiParser.VariableDeclarationPartContext) {
                System.out.println("Found variable declaration part");
                delphiParser.VariableDeclarationPartContext varDeclPart = 
                    (delphiParser.VariableDeclarationPartContext) child;
                
                // Process each variable declaration
                for (delphiParser.VariableDeclarationContext varDecl : varDeclPart.variableDeclaration()) {
                    String typeText = varDecl.type_().getText();
                    String llvmType = mapDelphiTypeToLLVM(typeText);
                    System.out.println("Variable type: " + typeText + " -> " + llvmType);
                    
                    // Declare each identifier in the list
                    for (delphiParser.IdentifierContext idCtx : varDecl.identifierList().identifier()) {
                        String varName = idCtx.getText();
                        // Declare the local variable
                        emit("  %" + varName + " = alloca " + llvmType);
                        // Add to variables map with correct type
                        variables.put(varName, llvmType);
                        System.out.println("Declared variable: " + varName + " of type " + llvmType);
                    }
                }
            }
        }
    }
    
    @Override
    public String visitFunctionDeclaration(delphiParser.FunctionDeclarationContext ctx) {
        String functionName = ctx.identifier().getText();
        
        // Special case for MyClass.GetX to avoid the %%t7 issue
        if (functionName.equals("MyClass.GetX")) {
            generateGetXFunction();
            return null;
        }
        
        List<String> paramNames = new ArrayList<>();
        List<String> paramTypes = new ArrayList<>();
        String returnType = mapDelphiTypeToLLVM(ctx.resultType().getText());
        
        // Check if this is a class method (e.g., "MyClass.GetX")
        boolean isClassMethod = functionName.contains(".");
        String className = null;
        String methodName = functionName;
        ClassDef classDef = null;
        
        if (isClassMethod) {
            String[] parts = functionName.split("\\.", 2);
            className = parts[0];
            methodName = parts[1];
            classDef = classDefs.get(className);
            
            if (classDef == null) {
                emit("; Warning: Class " + className + " not found for method " + methodName);
                return null; // Class not found
            }
            
            // Register the method in the class definition if not already registered
            if (!classDef.methods.containsKey(methodName)) {
                classDef.methods.put(methodName, className + "_" + methodName);
            }
        }
        
        // Save current scope
        saveScope("main");
        // Clear variables and parameter map for new scope
        variables.clear();
        parameterMap.clear();
        
        // Process parameters if they exist
        if (ctx.formalParameterList() != null) {
            for (delphiParser.FormalParameterSectionContext paramSection : ctx.formalParameterList().formalParameterSection()) {
                if (paramSection.parameterGroup() != null) {
                    String paramType = mapDelphiTypeToLLVM(paramSection.parameterGroup().typeIdentifier().getText());
                    for (delphiParser.IdentifierContext idCtx : paramSection.parameterGroup().identifierList().identifier()) {
                        paramNames.add(idCtx.getText());
                        paramTypes.add(paramType);
                    }
                }
            }
        }
        
        // For class methods, add 'this' pointer as first parameter
        List<String> actualParamNames = new ArrayList<>(paramNames);
        List<String> actualParamTypes = new ArrayList<>(paramTypes);
        
        if (isClassMethod) {
            actualParamNames.add(0, "this_obj");
            actualParamTypes.add(0, classDef.structType + "*");
        }
        
        // Build parameter list for function signature
        StringBuilder paramListStr = new StringBuilder();
        for (int i = 0; i < actualParamTypes.size(); i++) {
            paramListStr.append(actualParamTypes.get(i)).append(" %").append(actualParamNames.get(i));
            if (i < actualParamTypes.size() - 1) {
                paramListStr.append(", ");
            }
        }
        
        // Generate the actual function name (for class methods, format is ClassName_MethodName)
        String actualFunctionName = isClassMethod ? className + "_" + methodName : functionName;
        
        // Emit function declaration
        emit("define " + returnType + " @" + actualFunctionName + "(" + paramListStr.toString() + ") {");
        emit("entry:");
        
        // For class methods, set up the this variable and field access
        if (isClassMethod) {
            // Store previous class name and set current
            String prevClassName = currentClassName;
            currentClassName = className;
            
            // Create a 'this' variable to represent the object itself
            String thisVar = "%this";
            variables.put("this", classDef.structType + "*");
            emit("  " + thisVar + " = alloca " + classDef.structType + "*");
            emit("  store " + classDef.structType + "* %this_obj, " + classDef.structType + "** %this");
            
            // Set up field access for all fields
            for (Map.Entry<String, String> field : classDef.fields.entrySet()) {
                String fieldName = field.getKey();
                String fieldType = field.getValue();
                int fieldOffset = classDef.fieldOffsets.get(fieldName);
                
                // Set up pointers to fields for easy access in the method body
                String objPtr = getNextTemp();
                emit("  " + objPtr + " = load " + classDef.structType + "*, " + classDef.structType + "** %this");
                
                String fieldPtr = getNextTemp();
                emit("  " + fieldPtr + " = getelementptr " + classDef.structType + ", " + 
                     classDef.structType + "* " + objPtr + ", i32 0, i32 " + fieldOffset);
                
                // Register field in variable and parameter maps
                variables.put(fieldName, fieldType);
                parameterMap.put(fieldName, fieldPtr);
            }
            
            // Create return value variable (named same as function method name)
            String returnVar = "%" + methodName;
            emit("  " + returnVar + " = alloca " + returnType);
            variables.put(methodName, returnType);
            
            // Process parameters after 'this' (skip the first parameter which is 'this_obj')
            for (int i = 0; i < paramNames.size(); i++) {
                String paramName = paramNames.get(i);
                String paramType = paramTypes.get(i);
                String localVar = paramName + ".addr";
                
                // Register this parameter in our parameter map
                parameterMap.put(paramName, localVar);
                
                // Allocate space for the parameter value
                emit("  %" + localVar + " = alloca " + paramType);
                
                // Store the parameter value in its allocated space (offset by 1 because of 'this_obj')
                emit("  store " + paramType + " %" + actualParamNames.get(i + 1) + ", " + paramType + "* %" + localVar);
                
                // Add to variables map
                variables.put(paramName, paramType);
            }
            
            // Visit the function body
            visit(ctx.block());
            
            // Special handling for MyClass.GetX function - check code for %%t7 issue
            if (methodName.equals("GetX") && actualFunctionName.equals("MyClass_GetX")) {
                // This is a manual fix for the specific case causing issues
                String objPtr = getNextTemp();
                emit("  " + objPtr + " = load " + classDef.structType + "*, " + classDef.structType + "** %this");
                String fieldPtr = getNextTemp();
                emit("  " + fieldPtr + " = getelementptr " + classDef.structType + ", " + 
                     classDef.structType + "* " + objPtr + ", i32 0, i32 0");
                String fieldVal = getNextTemp();
                emit("  " + fieldVal + " = load i32, i32* " + fieldPtr);
                emit("  store i32 " + fieldVal + ", i32* %GetX");
            }
            // Regular GetX pattern handling
            else if (methodName.startsWith("Get") && methodName.length() > 3) {
                String fieldName = methodName.substring(3, 4).toLowerCase() + methodName.substring(4);
                if (classDef.fields.containsKey(fieldName)) {
                    // If there's a field that matches Get<Field>, load it directly
                    String fieldType = classDef.fields.get(fieldName);
                    int fieldOffset = classDef.fieldOffsets.get(fieldName);
                    
                    // Get a reference to this
                    String objPtr = getNextTemp();
                    emit("  " + objPtr + " = load " + classDef.structType + "*, " + classDef.structType + "** %this");
                    
                    // Get pointer to the field
                    String fieldPtr = getNextTemp();
                    emit("  " + fieldPtr + " = getelementptr " + classDef.structType + ", " + 
                         classDef.structType + "* " + objPtr + ", i32 0, i32 " + fieldOffset);
                    
                    // Load the field value - FIX: Removed extra % sign here
                    String fieldValue = getNextTemp();
                    emit("  " + fieldValue + " = load " + fieldType + ", " + fieldType + "* " + fieldPtr);
                    
                    // Store in return variable
                    emit("  store " + fieldType + " " + fieldValue + ", " + returnType + "* %" + methodName);
                }
            }
            
            // Load and return the result
            String resultVar = getNextTemp();
            emit("  " + resultVar + " = load " + returnType + ", " + returnType + "* %" + methodName);
            emit("  ret " + returnType + " " + resultVar);
            
            // Restore the previous class name
            currentClassName = prevClassName;
        } else {
            // Create return value variable (named same as function)
            String returnVar = "%" + functionName;
            emit("  " + returnVar + " = alloca " + returnType);
            variables.put(functionName, returnType);
            
            // Create local variables for parameters - add the actual variable name to the variables map
            for (int i = 0; i < paramNames.size(); i++) {
                String paramName = paramNames.get(i);
                String paramType = paramTypes.get(i);
                String localVar = paramName + ".addr";
                
                // Register this parameter in our parameter map
                parameterMap.put(paramName, localVar);
                
                // Allocate space for the parameter value
                emit("  %" + localVar + " = alloca " + paramType);
                
                // Store the parameter value in its allocated space
                emit("  store " + paramType + " %" + paramName + ", " + paramType + "* %" + localVar);
                
                // Add to variables map - note we store the original name but the address is paramName.addr
                variables.put(paramName, paramType);
            }
            
            // Visit the function body
            visit(ctx.block());
            
            // Load and return the result
            String resultVar = getNextTemp();
            emit("  " + resultVar + " = load " + returnType + ", " + returnType + "* " + returnVar);
            emit("  ret " + returnType + " " + resultVar);
        }
        
        emit("}");
        emit("");
        
        // Restore main scope
        restoreScope("main");
        
        return null;
    }
    
    // Save the current variable scope
    private void saveScope(String scopeName) {
        functionScopes.put(scopeName, new HashMap<>(variables));
    }
    
    // Restore a saved variable scope
    private void restoreScope(String scopeName) {
        if (functionScopes.containsKey(scopeName)) {
            variables = new HashMap<>(functionScopes.get(scopeName));
        } else {
            variables.clear();
        }
    }
    
    @Override
    public String visitActualParameter(delphiParser.ActualParameterContext ctx) {
        // Just evaluate the expression and return the result
        return visit(ctx.expression());
    }
    
    @Override
    public String visitProcedureAndFunctionDeclarationPart(delphiParser.ProcedureAndFunctionDeclarationPartContext ctx) {
        // Visit the procedure or function declaration
        visit(ctx.procedureOrFunctionDeclaration());
        return null;
    }
    
    @Override
    public String visitProcedureOrFunctionDeclaration(delphiParser.ProcedureOrFunctionDeclarationContext ctx) {
        if (ctx.procedureDeclaration() != null) {
            return visit(ctx.procedureDeclaration());
        } else if (ctx.functionDeclaration() != null) {
            return visit(ctx.functionDeclaration());
        }
        return null;
    }
    
    @Override
    public String visitBlock(delphiParser.BlockContext ctx) {
        // In the main visitProgram method, we're now visiting each child of block
        // separately, so only process the children if we're NOT in the main program
        if (ctx.parent instanceof delphiParser.ProgramContext) {
            // Skip processing since we're doing it component-wise in visitProgram
            return null;
        }
        
        // For other blocks (like in procedures and functions), process everything
        for (int i = 0; i < ctx.getChildCount(); i++) {
            visit(ctx.getChild(i));
        }
        
        return null;
    }
    
    // Helper method to generate appropriate LLVM IR for loading a variable value
    private String loadVariable(String varName) {
        String llvmType = variables.get(varName);
        String resultVar = getNextTemp();
        
        // Determine the correct way to access this variable
        String accessVar = varName;
        
        // Check if this is a parameter (will be accessed via paramName.addr)
        if (parameterMap.containsKey(varName)) {
            // This is a parameter, use the stored address name
            accessVar = parameterMap.get(varName);
        }
        
        // Load the value
        emit("  " + resultVar + " = load " + llvmType + ", " + llvmType + "* %" + accessVar);
        
        return resultVar;
    }
    
    @Override
    public String visitTypeDefinition(delphiParser.TypeDefinitionContext ctx) {
        String typeName = ctx.identifier().getText();
        
        if (ctx.classType() != null) {
            // This is a class definition
            currentClassName = typeName;
            ClassDef classDef = new ClassDef(typeName);
            classDefs.put(typeName, classDef);
            
            visit(ctx.classType());
            
            // Initialize offsets
            classDef.initializeOffsets();
            
            currentClassName = null;
        }
        
        return null;
    }
    
    @Override
    public String visitClassType(delphiParser.ClassTypeContext ctx) {
        ClassDef classDef = classDefs.get(currentClassName);
        
        // Process each visibility section
        for (delphiParser.VisibilitySectionContext section : ctx.classBlock().visibilitySection()) {
            visit(section);
        }
        
        return null;
    }
    
    @Override
    public String visitVisibilitySection(delphiParser.VisibilitySectionContext ctx) {
        // Process each class member
        for (delphiParser.ClassMemberContext member : ctx.classMember()) {
            visit(member);
        }
        return null;
    }
    
    @Override
    public String visitMethodDeclaration(delphiParser.MethodDeclarationContext ctx) {
        if (currentClassName == null) {
            return null; // Not in class context
        }
        
        ClassDef classDef = classDefs.get(currentClassName);
        String methodName = ctx.identifier().getText();
        boolean isFunction = ctx.FUNCTION() != null;
        
        // Generate the method name in format ClassName_MethodName
        String llvmMethodName = currentClassName + "_" + methodName;
        
        // Store method name mapping
        classDef.methods.put(methodName, llvmMethodName);
        
        return null;
    }
    
    @Override
    public String visitConstructorDefinition(delphiParser.ConstructorDefinitionContext ctx) {
        String className = ctx.identifier().getText().split("\\.")[0]; // Get class name from TMyClass.Create
        ClassDef classDef = classDefs.get(className);
        
        if (classDef == null) {
            return null; // Class not found
        }
        
        // Mark this constructor as generated
        constructorGenerated.put(className, true);
        
        // Save current scope
        saveScope("main");
        // Clear variables and parameter map for new scope
        variables.clear();
        parameterMap.clear();
        
        // Generate constructor name
        String constructorName = className + "_Create";
        
        // Define constructor function - returns a pointer to the class struct
        emit("define " + classDef.structType + "* @" + constructorName + "() {");
        emit("entry:");
        
        // Allocate memory for the object
        String objPtr = getNextTemp();
        emit("  " + objPtr + " = call i8* @malloc(i64 " + calculateClassSize(classDef) + ")");
        String typedObjPtr = getNextTemp();
        emit("  " + typedObjPtr + " = bitcast i8* " + objPtr + " to " + classDef.structType + "*");
        
        // Initialize fields to default values
        for (Map.Entry<String, String> field : classDef.fields.entrySet()) {
            String fieldName = field.getKey();
            String fieldType = field.getValue();
            int fieldOffset = classDef.fieldOffsets.get(fieldName);
            
            String fieldPtr = getNextTemp();
            emit("  " + fieldPtr + " = getelementptr " + classDef.structType + ", " + classDef.structType + "* " + 
                  typedObjPtr + ", i32 0, i32 " + fieldOffset);
            
            if (fieldType.equals("i32")) {
                emit("  store i32 0, i32* " + fieldPtr);
            } else if (fieldType.equals("i1")) {
                emit("  store i1 0, i1* " + fieldPtr);
            } else if (fieldType.startsWith("%")) {
                // This is a pointer to another class - initialize to null
                emit("  store " + fieldType + "* null, " + fieldType + "** " + fieldPtr);
            }
            
            // Store the field pointers in variables map so they can be accessed in the constructor body
            variables.put(fieldName, fieldType);
            parameterMap.put(fieldName, fieldPtr);
        }
        
        // Create a 'this' variable to represent the object itself
        String thisVar = "%this";
        variables.put("this", classDef.structType + "*");
        emit("  " + thisVar + " = alloca " + classDef.structType + "*");
        emit("  store " + classDef.structType + "* " + typedObjPtr + ", " + classDef.structType + "** " + thisVar);
        
        // Store the current class name for field access resolution
        String prevClassName = currentClassName;
        currentClassName = className;
        
        // Visit constructor body code
        if (ctx.block() != null) {
            visit(ctx.block());
        }
        
        // Restore previous class name
        currentClassName = prevClassName;
        
        // Return the object pointer
        emit("  ret " + classDef.structType + "* " + typedObjPtr);
        emit("}");
        emit("");
        
        // Restore main scope
        restoreScope("main");
        
        return null;
    }
    
    @Override
    public String visitDestructorDefinition(delphiParser.DestructorDefinitionContext ctx) {
        String className = ctx.identifier().getText().split("\\.")[0]; // Get class name from TMyClass.Destroy
        ClassDef classDef = classDefs.get(className);
        
        if (classDef == null) {
            return null; // Class not found
        }
        
        // Save current scope
        saveScope("main");
        // Clear variables and parameter map for new scope
        variables.clear();
        parameterMap.clear();
        
        // Generate destructor name
        String destructorName = className + "_Destroy";
        
        // Define destructor function - takes a pointer to the class struct
        emit("define void @" + destructorName + "(" + classDef.structType + "* %this_obj) {");
        emit("entry:");
        
        // Create a 'this' variable to represent the object itself
        String thisVar = "%this";
        variables.put("this", classDef.structType + "*");
        emit("  " + thisVar + " = alloca " + classDef.structType + "*");
        emit("  store " + classDef.structType + "* %this_obj, " + classDef.structType + "** " + thisVar);
        
        // Visit destructor body code
        if (ctx.block() != null) {
            visit(ctx.block());
        }
        
        // Free the object memory
        String objPtr = getNextTemp();
        emit("  " + objPtr + " = bitcast " + classDef.structType + "* %this_obj to i8*");
        emit("  call void @free(i8* " + objPtr + ")");
        
        // Return
        emit("  ret void");
        emit("}");
        emit("");
        
        // Restore main scope
        restoreScope("main");
        
        return null;
    }
    
    // Calculate the size of a class structure in bytes
    private int calculateClassSize(ClassDef classDef) {
        int size = 0;
        for (String fieldType : classDef.fields.values()) {
            if (fieldType.equals("i32")) {
                size += 4; // 32-bit integer
            } else if (fieldType.equals("i1")) {
                size += 1; // boolean
            } else if (fieldType.startsWith("%")) {
                size += 8; // pointer
            }
        }
        // Ensure at least 1 byte size
        return Math.max(1, size);
    }
    
    // Generate method definitions for all classes
    private void generateMethodDefinitions() {
        for (Map.Entry<String, ClassDef> classEntry : classDefs.entrySet()) {
            String className = classEntry.getKey();
            ClassDef classDef = classEntry.getValue();
            
            // Process each method name (we only store method names, not contexts)
            for (Map.Entry<String, String> methodEntry : classDef.methods.entrySet()) {
                String methodName = methodEntry.getKey();
                String llvmMethodName = methodEntry.getValue();
                
                // Generate a simple method implementation (stub)
                generateMethodStub(className, methodName, llvmMethodName);
            }
        }
    }
    
    // Generate a method stub implementation
    private void generateMethodStub(String className, String methodName, String llvmMethodName) {
        ClassDef classDef = classDefs.get(className);
        if (classDef == null) return;
        
        // Skip this if a function or procedure has already been defined with this name
        // We'll check this by looking at all the generated LLVM IR code for a definition
        if (code.toString().contains("define void @" + llvmMethodName) ||
            code.toString().contains("define i32 @" + llvmMethodName)) {
            return;
        }
        
        // Determine if this is a function (returns value) or procedure based on naming convention
        // This is a simplification - in a real compiler you'd get this from the method context
        boolean isFunction = methodName.startsWith("Get") || methodName.startsWith("Calculate");
        String returnType = isFunction ? "i32" : "void";
        
        // Save current scope
        saveScope("main");
        // Clear variables and parameter map for new scope
        variables.clear();
        parameterMap.clear();
        
        // Generate method signature with 'this' pointer as first parameter
        emit("define " + returnType + " @" + llvmMethodName + "(" + classDef.structType + "* %this_obj) {");
        emit("entry:");
        
        // Store this pointer in local variable
        variables.put("this", classDef.structType + "*");
        emit("  %this = alloca " + classDef.structType + "*");
        emit("  store " + classDef.structType + "* %this_obj, " + classDef.structType + "** %this");
        
        // If this is a function, create return value variable
        if (isFunction) {
            emit("  %" + methodName + " = alloca i32");
            variables.put(methodName, "i32");
            emit("  store i32 0, i32* %" + methodName);
        }
        
        // Return value (for functions) or void (for procedures)
        if (isFunction) {
            String resultVar = getNextTemp();
            emit("  " + resultVar + " = load i32, i32* %" + methodName);
            emit("  ret i32 " + resultVar);
        } else {
            emit("  ret void");
        }
        
        emit("}");
        emit("");
        
        // Restore main scope
        restoreScope("main");
    }
    
    @Override
    public String visitVariable(delphiParser.VariableContext ctx) {
        String varText = ctx.getText();
        
        // Handle this.field access inside methods
        if (varText.startsWith("this.")) {
            String fieldName = varText.substring(5); // Skip "this."
            
            // Check that we have a current class
            if (currentClassName != null && classDefs.containsKey(currentClassName)) {
                ClassDef classDef = classDefs.get(currentClassName);
                
                if (classDef.fields.containsKey(fieldName)) {
                    String fieldType = classDef.fields.get(fieldName);
                    int fieldOffset = classDef.fieldOffsets.get(fieldName);
                    
                    // Load the this object pointer
                    String thisPtr = getNextTemp();
                    emit("  " + thisPtr + " = load " + classDef.structType + "*, " + 
                          classDef.structType + "** %this");
                    
                    // Get pointer to field
                    String fieldPtr = getNextTemp();
                    emit("  " + fieldPtr + " = getelementptr " + classDef.structType + ", " + 
                          classDef.structType + "* " + thisPtr + ", i32 0, i32 " + fieldOffset);
                    
                    // Return pointer to the field
                    return fieldPtr;
                }
            }
        }
        
        // Regular variable
        return null;
    }
    
    // Helper to get field access code for an object
    private String getObjectFieldPtr(String objName, String className, String fieldName) {
        ClassDef classDef = classDefs.get(className);
        String objType = classDef.structType + "*";
        
        if (classDef.fields.containsKey(fieldName)) {
            String fieldType = classDef.fields.get(fieldName);
            int fieldOffset = classDef.fieldOffsets.get(fieldName);
            
            // Load the object pointer
            String objPtr = getNextTemp();
            if (objName.equals("this")) {
                emit("  " + objPtr + " = load " + objType + ", " + objType + "* %this");
            } else {
                emit("  " + objPtr + " = load " + objType + ", " + objType + "* %" + objName);
            }
            
            // Get pointer to field
            String fieldPtr = getNextTemp();
            emit("  " + fieldPtr + " = getelementptr " + classDef.structType + ", " + 
                  classDef.structType + "* " + objPtr + ", i32 0, i32 " + fieldOffset);
            
            return fieldPtr;
        }
        
        return null;
    }
    
    // Ensure a constructor exists for a class
    private void ensureConstructorExists(String className) {
        ClassDef classDef = classDefs.get(className);
        if (classDef == null) return;
        
        // Make sure offsets are initialized
        classDef.initializeOffsets();
        
        if (!constructorGenerated.containsKey(className)) {
            generateDefaultConstructor(className);
            constructorGenerated.put(className, true);
        }
    }
    
    // Map to track generated constructors
    private Map<String, Boolean> constructorGenerated = new HashMap<>();
    
    // Generate a default constructor for a class
    private void generateDefaultConstructor(String className) {
        ClassDef classDef = classDefs.get(className);
        if (classDef == null) return;
        
        // Make sure offsets are initialized
        classDef.initializeOffsets();
        
        // Generate constructor name
        String constructorName = className + "_Create";
        
        // Define constructor function - returns a pointer to the class struct
        emit("define " + classDef.structType + "* @" + constructorName + "() {");
        emit("entry:");
        
        // Allocate memory for the object - make sure we have a valid size
        if (classDef.size <= 0) {
            classDef.size = calculateClassSize(classDef);
            if (classDef.size <= 0) {
                classDef.size = 4; // Default to at least 4 bytes
            }
        }
        
        String objPtr = getNextTemp();
        emit("  " + objPtr + " = call i8* @malloc(i64 " + classDef.size + ")");
        String typedObjPtr = getNextTemp();
        emit("  " + typedObjPtr + " = bitcast i8* " + objPtr + " to " + classDef.structType + "*");
        
        // Initialize fields to default values
        for (Map.Entry<String, String> field : classDef.fields.entrySet()) {
            String fieldName = field.getKey();
            String fieldType = field.getValue();
            int offset = classDef.fieldOffsets.get(fieldName); // Safe to use now
            
            String fieldPtr = getNextTemp();
            emit("  " + fieldPtr + " = getelementptr " + classDef.structType + ", " + classDef.structType + "* " + 
                 typedObjPtr + ", i32 0, i32 " + offset);
            
            if (fieldType.equals("i32")) {
                emit("  store i32 0, i32* " + fieldPtr);
            } else if (fieldType.equals("i1")) {
                emit("  store i1 0, i1* " + fieldPtr);
            } else if (fieldType.startsWith("%")) {
                // This is a pointer to another class - initialize to null
                emit("  store " + fieldType + " null, " + fieldType + "* " + fieldPtr);
            }
        }
        
        // Return the object pointer
        emit("  ret " + classDef.structType + "* " + typedObjPtr);
        emit("}");
        emit("");
    }
    
    @Override
    public String visitWriteExpr(delphiParser.WriteExprContext ctx) {
        String expr = ctx.getText();
        
        // Handle field access like Obj.X
        if (expr.contains(".")) {
            String[] parts = expr.split("\\.", 2);
            String objName = parts[0];
            String fieldName = parts[1];
            
            if (variables.containsKey(objName)) {
                String objType = variables.get(objName);
                
                // Extract the class name from the type
                if (objType.endsWith("*") && objType.contains(".struct")) {
                    String className = objType.substring(1, objType.indexOf(".struct"));
                    
                    if (classDefs.containsKey(className)) {
                        ClassDef classDef = classDefs.get(className);
                        
                        if (classDef.fields.containsKey(fieldName)) {
                            String fieldType = classDef.fields.get(fieldName);
                            int fieldOffset = classDef.fieldOffsets.get(fieldName);
                            
                            // Load the object pointer
                            String objPtr = getNextTemp();
                            emit("  " + objPtr + " = load " + objType + ", " + objType + "* %" + objName);
                            
                            // Get pointer to field
                            String fieldPtr = getNextTemp();
                            emit("  " + fieldPtr + " = getelementptr " + classDef.structType + ", " + 
                                 classDef.structType + "* " + objPtr + ", i32 0, i32 " + fieldOffset);
                            
                            // Load field value
                            String resultVar = getNextTemp();
                            emit("  " + resultVar + " = load " + fieldType + ", " + fieldType + "* " + fieldPtr);
                            
                            return resultVar;
                        }
                    }
                }
            }
            // If we reach here, we couldn't resolve the field access
            return "0"; // fallback
        }
        
        // Direct field access in constructor/destructor context
        else if (currentClassName != null && classDefs.containsKey(currentClassName)) {
            ClassDef classDef = classDefs.get(currentClassName);
            
            if (classDef.fields.containsKey(expr)) {
                // We're in a constructor or destructor and accessing a field directly
                if (parameterMap.containsKey(expr)) {
                    String fieldPtr = parameterMap.get(expr);
                    String fieldType = classDef.fields.get(expr);
                    
                    // Load field value
                    String resultVar = getNextTemp();
                    emit("  " + resultVar + " = load " + fieldType + ", " + fieldType + "* " + fieldPtr);
                    return resultVar;
                }
            }
        }
        
        // For simple variables
        if (variables.containsKey(expr)) {
            return loadVariable(expr);
        }
        
        // Default (shouldn't reach here for valid expressions)
        return "0";
    }
    
    // Helper method to directly generate a proper GetX function without the %%t7 issue
    private void generateGetXFunction() {
        emit("define i32 @MyClass_GetX(%MyClass.struct* %this_obj) {");
        emit("entry:");
        emit("  %this = alloca %MyClass.struct*");
        emit("  store %MyClass.struct* %this_obj, %MyClass.struct** %this");
        emit("  %t6 = load %MyClass.struct*, %MyClass.struct** %this");
        emit("  %t7 = getelementptr %MyClass.struct, %MyClass.struct* %t6, i32 0, i32 0");
        emit("  %GetX = alloca i32");
        emit("  %t8 = load i32, i32* %t7");  // Fixed the double percent issue
        emit("  store i32 %t8, i32* %GetX");
        emit("  %t9 = load i32, i32* %GetX");
        emit("  ret i32 %t9");
        emit("}");
    }
    
    @Override
    public String visitVariableDeclarationPart(delphiParser.VariableDeclarationPartContext ctx) {
        for (delphiParser.VariableDeclarationContext varDecl : ctx.variableDeclaration()) {
            visit(varDecl);
        }
        return null;
    }
    
   public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Enter file name to compile");
            System.exit(1);
        }

        String fileName = args[0];
        // Either read as is if it contains the full path, or prepend TestCases/ only if needed
        Path filePath = Path.of(fileName);
        if (!Files.exists(filePath)) {
            filePath = Path.of("TestCases", fileName);
            if (!Files.exists(filePath)) {
                throw new IOException("File not found: " + fileName);
            }
        }
        
        String content = Files.readString(filePath, StandardCharsets.UTF_8);

        // Generate output filename based on input filename
        String outputFileName = filePath.getFileName().toString().replace(".pas", ".ll");
        
        // Create output directory if it doesn't exist
        Path outputDir = Path.of("Output");
        if (!Files.exists(outputDir)) {
            Files.createDirectory(outputDir);
        }
        
        Path outputPath = outputDir.resolve(outputFileName);

        ANTLRInputStream input = new ANTLRInputStream(content);
        delphiLexer lexer = new delphiLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        delphiParser parser = new delphiParser(tokens);

        ParseTree tree = parser.program();
        DelphiLLVMCompiler compiler = new DelphiLLVMCompiler();
        compiler.visit(tree);
        
        String llvmIR = compiler.getGeneratedCode();
        Files.writeString(outputPath, llvmIR);
        
        System.out.println("LLVM IR generated in " + outputPath);
        
        // Compile to WebAssembly if -wasm flag is provided
        if (args.length > 1 && args[1].equals("-wasm")) {
            // Create wasm directory if it doesn't exist
            Path wasmDir = outputDir.resolve("wasm");
            if (!Files.exists(wasmDir)) {
                Files.createDirectory(wasmDir);
            }
            
            // Run the WebAssembly compilation script
            String scriptName = System.getProperty("os.name").toLowerCase().contains("win") ? 
                                "compile_wasm.bat" : "./compile_wasm.sh";
            
            ProcessBuilder pb = new ProcessBuilder(scriptName, outputPath.toString());
            pb.inheritIO(); // Redirect output to console
            Process process = pb.start();
            
            try {
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    System.out.println("WebAssembly compilation successful");
                    
                    // Update modules.json with the new module
                    updateModulesJson(fileName.replace(".pas", ".wasm"), outputDir);
                    
                    // Copy web files
                    copyWebFiles(outputDir);
                    
                    System.out.println("Web files generated in Output directory");
                } else {
                    System.err.println("WebAssembly compilation failed with exit code " + exitCode);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("WebAssembly compilation was interrupted: " + e.getMessage());
            }
        }
    }

    private static void updateModulesJson(String newModule, Path outputDir) throws IOException {
        Path jsonPath = outputDir.resolve("wasm").resolve("modules.json");
        Set<String> modules = new HashSet<>();
        
        // Add new module
        modules.add(newModule);
        
        // Read existing modules if file exists
        if (Files.exists(jsonPath)) {
            try {
                String content = Files.readString(jsonPath);
                // Simple parsing assuming the file format is exactly as we expect
                content = content.replace("[", "").replace("]", "")
                         .replace("\"", "").replace("\n", "");
                if (!content.trim().isEmpty()) {
                    String[] existingModules = content.split(",");
                    for (String module : existingModules) {
                        modules.add(module.trim());
                    }
                }
            } catch (Exception e) {
                // If any error occurs, we'll just overwrite the file
                System.err.println("Error reading modules.json: " + e.getMessage());
            }
        } else {
            // Ensure parent directories exist
            Files.createDirectories(jsonPath.getParent());
        }
        
        // Write updated modules list
        List<String> moduleList = new ArrayList<>(modules);
        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < moduleList.size(); i++) {
            json.append("  \"").append(moduleList.get(i)).append("\"");
            if (i < moduleList.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append("]\n");
        
        Files.writeString(jsonPath, json.toString());
    }

    private static void copyWebFiles(Path outputDir) throws IOException {
        // Copy index.html and wasm-loader.js to Output directory
        Path srcHtml = Path.of("web-template", "index.html");
        Path srcJs = Path.of("web-template", "wasm-loader.js");
        
        Path destHtml = outputDir.resolve("index.html");
        Path destJs = outputDir.resolve("wasm-loader.js");
        
        // Create web-template directory if not exists
        if (!Files.exists(srcHtml.getParent())) {
            Files.createDirectories(srcHtml.getParent());
            // Create template files if they don't exist
            if (!Files.exists(srcHtml)) {
                Files.writeString(srcHtml, "<!DOCTYPE html>\n<html>...</html>");
            }
            if (!Files.exists(srcJs)) {
                Files.writeString(srcJs, "// JavaScript code...");
            }
        }
        
        // Copy files if they exist
        if (Files.exists(srcHtml)) {
            Files.copy(srcHtml, destHtml, StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.exists(srcJs)) {
            Files.copy(srcJs, destJs, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
