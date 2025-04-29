; ModuleID = 'delphi_program'
target triple = "wasm32-unknown-unknown"

@.str.2 = private constant [11 x i8] c"Global e: \00"
@.str.0 = private constant [11 x i8] c"Global c: \00"
@.str.1 = private constant [11 x i8] c"Global d: \00"
@.str.newline = private constant [2 x i8] c"\0A\00"

declare void @js_print_str(i8*)
declare void @js_print_i32(i32)

define i32 @main() {
entry:
  %c = alloca i32
  store i32 0, i32* %c
  %d = alloca i32
  store i32 0, i32* %d
  %e = alloca i32
  store i32 0, i32* %e
  store i32 15, i32* %c
  store i32 10, i32* %d
  %t0 = load i32, i32* %c
  %t1 = load i32, i32* %d
  %t2 = add i32 %t0, %t1
  store i32 %t2, i32* %e
  %t3 = getelementptr [11 x i8], [11 x i8]* @.str.0, i32 0, i32 0
  call void @js_print_str(i8* %t3)
  %t4 = load i32, i32* %c
  call void @js_print_i32(i32 %t4)
  %t5 = getelementptr [2 x i8], [2 x i8]* @.str.newline, i32 0, i32 0
  call void @js_print_str(i8* %t5)
  %t6 = getelementptr [11 x i8], [11 x i8]* @.str.1, i32 0, i32 0
  call void @js_print_str(i8* %t6)
  %t7 = load i32, i32* %d
  call void @js_print_i32(i32 %t7)
  %t8 = getelementptr [2 x i8], [2 x i8]* @.str.newline, i32 0, i32 0
  call void @js_print_str(i8* %t8)
  %t9 = getelementptr [11 x i8], [11 x i8]* @.str.2, i32 0, i32 0
  call void @js_print_str(i8* %t9)
  %t10 = load i32, i32* %e
  call void @js_print_i32(i32 %t10)
  %t11 = getelementptr [2 x i8], [2 x i8]* @.str.newline, i32 0, i32 0
  call void @js_print_str(i8* %t11)
  ret i32 0
}

attributes #0 = { "wasm-export-name"="main" }
attributes #1 = { "wasm-export-name"="print_int" }
attributes #2 = { "wasm-export-name"="get_export_value" }
