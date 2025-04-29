# Delphi-Inspired Pascal Interpreter

## Overview

This project is a custom interpreter for a Pascal-like language, extended with key object-oriented features inspired by Delphi. Built using **ANTLR4**, the interpreter parses and executes source code directly in **Java**.

## Features

- ✅ **Pascal-like Syntax**
- 🔁 **Control Structures**: `if`, `while`, `for`, `break`, `continue`
- 🧠 **Static Scoping** with block-level scope management
- 🧩 **User-Defined Procedures and Functions** with parameter passing
- 🧱 **Object-Oriented Support**:
  - Class declarations
  - Object instantiation (`TMyClass.Create`)
  - Field access (`Obj.X`)
  - Method calls (`Obj.Method()`)
  - Constructors and destructors

## Implementation Details

- The interpreter is written in Java and uses **ANTLR4** for parsing.
- A **stack-based scope system** is used to manage variable visibility, including support for nested blocks and function/procedure scopes.
- Classes are represented by custom Java objects that store their own fields and methods.
- Each object has its own copy of fields, and method calls automatically bind `this` to the current object.
- Function and procedure parameters are handled through scoped variable binding.
- Constructors and destructors are recognized and invoked using the class name and special method names (`Create`, `Destroy`).

## Goals

This project demonstrates how to extend a procedural language grammar (Pascal) with object-oriented capabilities, how to manage scoping correctly, and how to build an interpreter capable of executing rich language constructs.

---

> Built as part of a Programming Languages Principles course project.

## Requirements
- Java 21 or higher
- ANTLR 4.13.2 (already included as `antlr-4.13.2-complete.jar`)

## How to Compile

Unzip the folder

run the following inside the root directory:

### On macOS / Linux:
```bash
javac -cp src/antlr-4.13.2-complete.jar src/*.java
```

### On Windows:
```bash
javac -cp src\antlr-4.13.2-complete.jar src\*.java
```

## How to Run

Still inside root folder, run:

### On Mac:
```bash
java -cp src:src/antlr-4.13.2-complete.jar DelphiTestCasesRunner
```

### On Windows:
```bash
java -cp src;src\antlr-4.13.2-complete.jar DelphiTestCasesRunner
```

- Pass optional argument (`true` or `false`). It enables debug mode to allow printing of variables and classes, along with scope.

- Output will be generated in a file called `output.pas` in the root folder

### Example runs:
### Mac example
```bash
java -cp src:src/antlr-4.13.2-complete.jar DelphiTestCasesRunner
java -cp src:src/antlr-4.13.2-complete.jar DelphiTestCasesRunner true
```

### Windows example
```bash
java -cp src;src\antlr-4.13.2-complete.jar DelphiTestCasesRunner
java -cp src;src\antlr-4.13.2-complete.jar DelphiTestCasesRunner true
```

## Folder Structure

```
DelphiInterpreterSubmission/
├── src/
│   ├── antlr-4.13.2-complete.jar
│   ├── *.java
│   ├── delphi.g4
│   ├── *.tokens / *.interp
├── TestCases/
│   ├── Test1.pas
│   ├── Test2.pas
├── README.md
├── output.pas
```

## PROJECT GROUP : 36
**Shiva Kumar Thummanapalli** \
UFID -  95344477

**Harsh Gupta** \
UFID - 21828165



// Final project

Windows compile

javac -cp src\antlr-4.13.2-complete.jar src\*.java

Windows run

java -cp src;src\antlr-4.13.2-complete.jar DelphiLLVMCompiler Test1.pas wasm

compile_wasm.bat Output\Test1.ll

cd Output

python -m http.server