; ModuleID = 'delphi_program'
target triple = "wasm32-unknown-unknown"

declare i8* @malloc(i64)
declare void @free(i8*)

%MyClass.struct = type { i32 }
@.str.newline = private constant [2 x i8] c"\0A\00"

declare void @js_print_str(i8*)
declare void @js_print_i32(i32)
declare i32 @string_length(i8*)
declare i32 @js_read_i32()


define %MyClass.struct* @MyClass_Create() {
entry:
  %t0 = call i8* @malloc(i64 4)
  %t1 = bitcast i8* %t0 to %MyClass.struct*
  %t2 = getelementptr %MyClass.struct, %MyClass.struct* %t1, i32 0, i32 0
  store i32 0, i32* %t2
  ret %MyClass.struct* %t1
}

define void @MyClass_SetX(%MyClass.struct* %this_obj, i32 %Value) {
entry:
  %this = alloca %MyClass.struct*
  store %MyClass.struct* %this_obj, %MyClass.struct** %this
  %t3 = load %MyClass.struct*, %MyClass.struct** %this
  %t4 = getelementptr %MyClass.struct, %MyClass.struct* %t3, i32 0, i32 0
  %Value.addr = alloca i32
  store i32 %Value, i32* %Value.addr
  %t5 = load i32, i32* %Value.addr
  store i32 %t5, i32* %t4
  ret void
}

define i32 @MyClass_GetX(%MyClass.struct* %this_obj) {
entry:
  %this = alloca %MyClass.struct*
  store %MyClass.struct* %this_obj, %MyClass.struct** %this
  %t6 = load %MyClass.struct*, %MyClass.struct** %this
  %t7 = getelementptr %MyClass.struct, %MyClass.struct* %t6, i32 0, i32 0
  %GetX = alloca i32
  %t8 = load i32, i32* %t7
  store i32 %t8, i32* %GetX
  %t9 = load i32, i32* %GetX
  ret i32 %t9
}
define i32 @main() {
entry:
  %Obj = alloca %MyClass.struct*
  store %MyClass.struct* null, %MyClass.struct** %Obj
  %t6 = call %MyClass.struct* @MyClass_Create()
  store %MyClass.struct* %t6, %MyClass.struct** %Obj
  %t7 = load %MyClass.struct*, %MyClass.struct** %Obj
  call void @MyClass_SetX(%MyClass.struct* %t7, i32 27)
  %t8 = load %MyClass.struct*, %MyClass.struct** %Obj
  %t9 = call i32 @MyClass_GetX(%MyClass.struct* %t8)
  call void @js_print_i32(i32 %t9)
  %t10 = getelementptr [2 x i8], [2 x i8]* @.str.newline, i32 0, i32 0
  call void @js_print_str(i8* %t10)
  %t11 = load %MyClass.struct*, %MyClass.struct** %Obj
  %t12 = getelementptr %MyClass.struct, %MyClass.struct* %t11, i32 0, i32 0
  %t13 = load i32, i32* %t12
  call void @js_print_i32(i32 %t13)
  %t14 = getelementptr [2 x i8], [2 x i8]* @.str.newline, i32 0, i32 0
  call void @js_print_str(i8* %t14)
  ret i32 0
}

attributes #0 = { "wasm-export-name"="main" }
attributes #1 = { "wasm-export-name"="print_int" }
attributes #2 = { "wasm-export-name"="get_export_value" }
