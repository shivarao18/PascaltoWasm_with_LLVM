; ModuleID = 'delphi_program'
target triple = "wasm32-unknown-unknown"

declare void @js_print_str(i8*)
declare void @js_print_i32(i32)

@.str.newline = private constant [2 x i8] c"\0A\00"
@.str.0 = private constant [11 x i8] c"Global a: \00"
@.str.1 = private constant [11 x i8] c"Global b: \00"

define i32 @main() {
entry:
  %t0 = getelementptr [20 x i8], [20 x i8]* @.str.2, i32 0, i32 0
  call void @js_print_str(i8* %t0)
  %t1 = getelementptr [2 x i8], [2 x i8]* @.str.newline, i32 0, i32 0
  call void @js_print_str(i8* %t1)
  ret i32 0
}

attributes #0 = { "wasm-export-name"="main" }
attributes #1 = { "wasm-export-name"="print_int" }
attributes #2 = { "wasm-export-name"="get_export_value" }
