Objective:

This project extends your work from Project 1 & 2. YOU ARE ONLY REQUIRED TO IMPLEMENT AROUND 70% OF LANGUAGE FEATURES OF YOUR OWN CHOOSING. 

Instead of directly evaluating/interpreting the Pascal / Delphi code using your Abstract Syntax Tree (AST) or parse tree visitor, you will now build a compiler. Your compiler will translate the Pascal / Delphi subset defined in Project 2 into LLVM Intermediate Representation (IR). Subsequently, for EC you will use the LLVM toolchain to compile this IR into WebAssembly (WASM), allowing your Pascal / Delphi programs to run within a web browser environment.

Building on previous projects:

This project directly utilizes the parser and the symbolic representation (AST or visited parse tree) you developed in Project 2. You should reuse your existing front-end code (lexer, parser, AST builder/visitor setup). The core new task is to replace the evaluation/interpretation logic with code generation logic targeting LLVM IR.

Key Tasks:

LLVM IR Generation (AST/Tree Traversal):

Modify your AST traversal logic (e.g., your visitor or tree walker). Instead of performing calculations or executing control flow directly, your traversal should now generate corresponding LLVM IR instructions as text.
Your code generator should output a standard LLVM IR file (e.g., output.ll).
EXTRA CREDIT (20%): LLVM IR to WASM Compilation:

Use LLVM tools to compile this .ll file into a WebAssembly module (.wasm) using llc
Browser Runtime Environment:

Create a simple HTML file (.html) and associated JavaScript (.js) file.
The JavaScript code needs to:
Load and Instantiate: Fetch and instantiate the compiled .wasm module.
Execute: Call an exported function from your WASM module