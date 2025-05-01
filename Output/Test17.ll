; ModuleID = 'delphi_program'
target triple = "wasm32-unknown-unknown"

declare i8* @malloc(i64)
declare void @free(i8*)

%MyClass.struct = type { i8 }
@.str.newline = private constant [2 x i8] c"\0A\00"

declare void @js_print_str(i8*)
declare void @js_print_i32(i32)
declare i32 @string_length(i8*)
declare i32 @js_read_i32()


define %MyClass.struct* @MyClass_Create() {
entry:
  %t0 = call i8* @malloc(i64 1)
  %t1 = bitcast i8* %t0 to %MyClass.struct*
  ret %MyClass.struct* %t1
}

define void @MyClass_PrintWhile(%MyClass.struct* %this_obj) {
entry:
  %this = alloca %MyClass.struct*
  store %MyClass.struct* %this_obj, %MyClass.struct** %this
  %I = alloca i32
  store i32 0, i32* %I
  br label %while.cond.0
while.cond.0:
  %t2 = load i32, i32* %I
  %t3 = icmp slt i32 %t2, 10
  br i1 %t3, label %while.body.1, label %while.end.2
while.body.1:
  %t4 = load i32, i32* %I
  %t5 = add i32 %t4, 1
  store i32 %t5, i32* %I
  %t6 = load i32, i32* %I
  call void @js_print_i32(i32 %t6)
  %t7 = getelementptr [2 x i8], [2 x i8]* @.str.newline, i32 0, i32 0
  call void @js_print_str(i8* %t7)
  br label %while.cond.0
while.end.2:
  ret void
}

define i32 @main() {
entry:
  %Obj = alloca %MyClass.struct*
  store %MyClass.struct* null, %MyClass.struct** %Obj
  %t8 = call %MyClass.struct* @MyClass_Create()
  store %MyClass.struct* %t8, %MyClass.struct** %Obj
  %t9 = load %MyClass.struct*, %MyClass.struct** %Obj
  call void @MyClass_PrintWhile(%MyClass.struct* %t9)
  ret i32 0
}

attributes #0 = { "wasm-export-name"="main" }
attributes #1 = { "wasm-export-name"="print_int" }
attributes #2 = { "wasm-export-name"="get_export_value" }
