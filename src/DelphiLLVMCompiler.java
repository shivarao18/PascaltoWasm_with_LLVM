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
    private int tempVarCounter = 0;
    private Map<String, String> stringConstants = new HashMap<>();
    private int stringCounter = 0;
    private int globalCounter = 0;
    private Map<String, String> globalVariables = new HashMap<>();
    private List<String> globalDeclarations = new ArrayList<>();
    private boolean globalsEmitted = false;
    private int labelCounter = 0; // For generating unique labels
    
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
        
        // Add to global declarations to be emitted at the beginning
        int length = value.length() + 1; // +1 for null terminator
        globalDeclarations.add(constName + " = private constant [" + length + " x i8] c\"" + escapeString(value) + "\\00\"");
        
        return constName;
    }
    
    // This method emits all string constants before the main function
    private void emitStringConstants() {
        // We'll handle this in emitGlobals()
    }
    
    @Override
    public String visitProgram(delphiParser.ProgramContext ctx) {
        // Initialize module with imports & newline constant
        initializeModule();
        
        // First pass: scan for all string literals
        scanForStringLiterals(ctx);
        
        // Emit all string constants
        for (Map.Entry<String, String> entry : stringConstants.entrySet()) {
            String strValue = entry.getKey();
            String constName = entry.getValue();
            int length = strValue.length() + 1; // +1 for null terminator
            emit(constName + " = private constant [" + length + " x i8] c\"" + escapeString(strValue) + "\\00\"");
        }
        
        // Register the newline constant
        emit("@.str.newline = private constant [2 x i8] c\"\\0A\\00\"");
        emit("");
        
        // Emit function declarations
        emit("declare void @js_print_str(i8*)");
        emit("declare void @js_print_i32(i32)");
        emit("");
        
        // Emit the main function header
        emit("define i32 @main() {");
        emit("entry:");
        
        // Visit the block
        visit(ctx.block());
        
        // Return
        emit("  ret i32 0");
        emit("}");
        
        // Add Wasm attributes
        addWasmAttributes();
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
        
        // Recursively scan all children
        for (int i = 0; i < tree.getChildCount(); i++) {
            scanForStringLiterals(tree.getChild(i));
        }
    }
    
    @Override
    public String visitVariableDeclaration(delphiParser.VariableDeclarationContext ctx) {
        List<delphiParser.IdentifierContext> ids = ctx.identifierList().identifier();
        String llvmType = mapDelphiTypeToLLVM(ctx.type_().getText());
        for (delphiParser.IdentifierContext idCtx : ids) {
            String varName = idCtx.getText();
            String varPtr = "%" + varName;
            variables.put(varName, llvmType);
            // Allocate on stack
            emit("  " + varPtr + " = alloca " + llvmType);
            // Initialize to 0
            if (llvmType.equals("i32")) {
                emit("  store i32 0, i32* " + varPtr);
            }
        }
        return null;
    }
    
    private String mapDelphiTypeToLLVM(String delphiType) {
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
        String varName = ctx.variable().getText();
        String exprResult = visit(ctx.expression());
        String llvmType = variables.get(varName);
        String varPtr = "%" + varName;
        // Store to local alloca
        emit("  store " + llvmType + " " + exprResult + ", " + llvmType + "* " + varPtr);
        return null;
    }
    
    @Override
    public String visitFactor(delphiParser.FactorContext ctx) {
        if (ctx.variable() != null) {
            String varName = ctx.variable().getText();
            String llvmType = variables.get(varName);
            String resultVar = getNextTemp();
            
            emit("  " + resultVar + " = load " + llvmType + ", " + llvmType + "* %" + varName);
            return resultVar;
        } else if (ctx.unsignedConstant() != null) {
            return visit(ctx.unsignedConstant());
        } else if (ctx.expression() != null) {
            return visit(ctx.expression());
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
        emit("  br label %" + incLabel);    // ‘continue’ jumps here

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
    // @Override
    // public String visitForStatement(delphiParser.ForStatementContext ctx) {
    //     String varName = ctx.identifier().getText();
    //     String llvmType = variables.get(varName);
        
    //     // Generate labels
    //     String condLabel = getNextLabel("for.cond");
    //     String bodyLabel = getNextLabel("for.body");
    //     String incLabel = getNextLabel("for.inc");
    //     String endLabel = getNextLabel("for.end");
        
    //     // Initialize loop variable
    //     String initValue = visit(ctx.forList().initialValue().expression());
    //     emit("  store " + llvmType + " " + initValue + ", " + llvmType + "* %" + varName);
        
    //     // Also update global version
    //     if (globalVariables.containsKey(varName)) {
    //         emit("  store " + llvmType + " " + initValue + ", " + llvmType + "* " + globalVariables.get(varName));
    //     }
        
    //     // Jump to condition check
    //     emit("  br label %" + condLabel);
        
    //     // Condition check
    //     emit(condLabel + ":");
    //     String limitValue = visit(ctx.forList().finalValue().expression());
    //     String curValue = getNextTemp();
    //     emit("  " + curValue + " = load " + llvmType + ", " + llvmType + "* %" + varName);
        
    //     // Compare for the condition
    //     String condition = getNextTemp();
    //     boolean isToDirection = ctx.forList().TO() != null;
    //     if (isToDirection) {
    //         // TO: check if variable <= final
    //         emit("  " + condition + " = icmp sle i32 " + curValue + ", " + limitValue);
    //     } else {
    //         // DOWNTO: check if variable >= final
    //         emit("  " + condition + " = icmp sge i32 " + curValue + ", " + limitValue);
    //     }
        
    //     // Branch based on condition
    //     emit("  br i1 " + condition + ", label %" + bodyLabel + ", label %" + endLabel);
        
    //     // Loop body
    //     emit(bodyLabel + ":");
    //     visit(ctx.statement());
    //     emit("  br label %" + incLabel);
        
    //     // Increment/decrement
    //     emit(incLabel + ":");
    //     String oldValue = getNextTemp();
    //     emit("  " + oldValue + " = load " + llvmType + ", " + llvmType + "* %" + varName);
    //     String newValue = getNextTemp();
    //     if (isToDirection) {
    //         emit("  " + newValue + " = add i32 " + oldValue + ", 1");
    //     } else {
    //         emit("  " + newValue + " = sub i32 " + oldValue + ", 1");
    //     }
    //     emit("  store " + llvmType + " " + newValue + ", " + llvmType + "* %" + varName);
        
    //     // Also update global version
    //     if (globalVariables.containsKey(varName)) {
    //         emit("  store " + llvmType + " " + newValue + ", " + llvmType + "* " + globalVariables.get(varName));
    //     }
        
    //     // Jump back to condition
    //     emit("  br label %" + condLabel);
        
    //     // End of loop
    //     emit(endLabel + ":");
        
    //     return null;
    // }
    
    // @Override
    // public String visitForStatement(delphiParser.ForStatementContext ctx) {
    //     // … your existing for-loop code up to computing labels …
    //     String condLabel = getNextLabel("for.cond");
    //     String incLabel  = getNextLabel("for.inc");
    //     String endLabel  = getNextLabel("for.end");
    //     String bodyLabel = getNextLabel("for.body");

    //     breakTargets.push(endLabel);
    //     continueTargets.push(incLabel);

    //     // initialize, jump to cond, etc.
    //     // …
    //     // in the “increment” block you already emit a jump back to condLabel
    //     // …

    //     emit(endLabel + ":");

    //     breakTargets.pop();
    //     continueTargets.pop();
    //     return null;
    // }

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
        // jump to loop’s end label
        String target = breakTargets.peek();
        emit("  br label %" + target);
        return null;
    }

    @Override
    public String visitContinue(delphiParser.ContinueContext ctx) {
        // jump to loop’s continue label (re-test or increment)
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
                    String cst = registerStringConstant(s);
                    int len = s.length() + 1;
                    String ptr = getNextTemp();
                    emit("  " + ptr + " = getelementptr [" + len + " x i8], [" + len + " x i8]* " + cst + ", i32 0, i32 0");
                    // JS import for strings
                    emit("  call void @js_print_str(i8* " + ptr + ")");
                } else if (arg.writeExpr() != null) {
                    String varName = arg.writeExpr().IDENT(0).getText();
                    String llvmType = variables.get(varName);
                    if (llvmType.equals("i32")) {
                        String varPtr = "%" + varName;
                        // load from alloca
                        String tmp = getNextTemp();
                        emit("  " + tmp + " = load i32, i32* " + varPtr);
                        // JS print
                        emit("  call void @js_print_i32(i32 " + tmp + ")");
                    }
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
    
    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Enter file name to compile");
            System.exit(1);
        }

        String filePath = "TestCases/";
        String fileName = args[0];
        String content = Files.readString(Path.of(filePath + fileName), StandardCharsets.UTF_8);

        // Create output directory if it doesn't exist
        Path outputDir = Path.of("Output");
        if (!Files.exists(outputDir)) {
            Files.createDirectory(outputDir);
        }

        // Generate output filename based on input filename
        String outputFileName = fileName.replace(".pas", ".ll");
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
