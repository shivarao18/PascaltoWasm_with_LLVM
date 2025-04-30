; ModuleID = 'delphi_program'
target triple = "wasm32-unknown-unknown"

@.str.0 = private constant [17 x i8] c"Inside function!\00"
@.str.newline = private constant [2 x i8] c"\0A\00"

declare void @js_print_str(i8*)
declare void @js_print_i32(i32)
declare i32 @string_length(i8*)
declare i32 @js_read_i32()

define i32 @GetNumber() {
entry:
  %GetNumber = alloca i32
  %t0 = getelementptr [17 x i8], [17 x i8]* @.str.0, i32 0, i32 0
  call void @js_print_str(i8* %t0)
  %t1 = getelementptr [2 x i8], [2 x i8]* @.str.newline, i32 0, i32 0
  call void @js_print_str(i8* %t1)
  store i32 27, i32* %GetNumber
  %t2 = load i32, i32* %GetNumber
  ret i32 %t2
}

define i32 @main() {
entry:
  %Result = alloca i32
  store i32 0, i32* %Result
  %t3 = call i32 @GetNumber()
  store i32 %t3, i32* %Result
  %t4 = load i32, i32* %Result
  call void @js_print_i32(i32 %t4)
  %t5 = getelementptr [2 x i8], [2 x i8]* @.str.newline, i32 0, i32 0
  call void @js_print_str(i8* %t5)
  ret i32 0
}

attributes #0 = { "wasm-export-name"="main" }
attributes #1 = { "wasm-export-name"="print_int" }
attributes #2 = { "wasm-export-name"="get_export_value" }
