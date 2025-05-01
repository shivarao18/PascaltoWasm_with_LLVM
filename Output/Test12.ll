; ModuleID = 'delphi_program'
target triple = "wasm32-unknown-unknown"

declare i8* @malloc(i64)
declare void @free(i8*)

@.str.0 = private constant [10 x i8] c"class end\00"
@.str.newline = private constant [2 x i8] c"\0A\00"

declare void @js_print_str(i8*)
declare void @js_print_i32(i32)
declare i32 @string_length(i8*)
declare i32 @js_read_i32()

%MyClass.struct = type { i32 }

define %MyClass.struct* @MyClass_Create() {
entry:
  %t0 = call i8* @malloc(i64 4)
  %t1 = bitcast i8* %t0 to %MyClass.struct*
  %t2 = getelementptr %MyClass.struct, %MyClass.struct* %t1, i32 0, i32 0
  store i32 0, i32* %t2
  ret %MyClass.struct* %t1
}

define i32 @main() {
entry:
  %Obj = alloca %MyClass.struct*
  store %MyClass.struct* null, %MyClass.struct** %Obj
  %t3 = call %MyClass.struct* @MyClass_Create()
  store %MyClass.struct* %t3, %MyClass.struct** %Obj
  %t4 = load %MyClass.struct*, %MyClass.struct** %Obj
  %t5 = getelementptr %MyClass.struct, %MyClass.struct* %t4, i32 0, i32 0
  store i32 10, i32* %t5
  %t6 = load %MyClass.struct*, %MyClass.struct** %Obj
  %t7 = getelementptr %MyClass.struct, %MyClass.struct* %t6, i32 0, i32 0
  %t8 = load i32, i32* %t7
  call void @js_print_i32(i32 %t8)
  %t9 = getelementptr [2 x i8], [2 x i8]* @.str.newline, i32 0, i32 0
  call void @js_print_str(i8* %t9)
  %t10 = getelementptr [10 x i8], [10 x i8]* @.str.0, i32 0, i32 0
  call void @js_print_str(i8* %t10)
  %t11 = getelementptr [2 x i8], [2 x i8]* @.str.newline, i32 0, i32 0
  call void @js_print_str(i8* %t11)
  ret i32 0
}

attributes #0 = { "wasm-export-name"="main" }
attributes #1 = { "wasm-export-name"="print_int" }
attributes #2 = { "wasm-export-name"="get_export_value" }
