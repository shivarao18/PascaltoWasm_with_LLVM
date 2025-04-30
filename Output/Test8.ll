; ModuleID = 'delphi_program'
target triple = "wasm32-unknown-unknown"

@.str.newline = private constant [2 x i8] c"\0A\00"

declare void @js_print_str(i8*)
declare void @js_print_i32(i32)
declare i32 @string_length(i8*)
declare i32 @js_read_i32()

define void @Add(i32 %a, i32 %b) {
entry:
  %a.addr = alloca i32
  store i32 %a, i32* %a.addr
  %b.addr = alloca i32
  store i32 %b, i32* %b.addr
  %Z = alloca i32
  store i32 0, i32* %Z
  %t0 = load i32, i32* %a.addr
  %t1 = load i32, i32* %b.addr
  %t2 = add i32 %t0, %t1
  store i32 %t2, i32* %Z
  %t3 = load i32, i32* %Z
  call void @js_print_i32(i32 %t3)
  %t4 = getelementptr [2 x i8], [2 x i8]* @.str.newline, i32 0, i32 0
  call void @js_print_str(i8* %t4)
  ret void
}

define i32 @main() {
entry:
  %X = alloca i32
  store i32 0, i32* %X
  %Y = alloca i32
  store i32 0, i32* %Y
  store i32 5, i32* %X
  store i32 10, i32* %Y
  %t5 = load i32, i32* %X
  %t6 = load i32, i32* %Y
  call void @Add(i32 %t5, i32 %t6)
  ret i32 0
}

attributes #0 = { "wasm-export-name"="main" }
attributes #1 = { "wasm-export-name"="print_int" }
attributes #2 = { "wasm-export-name"="get_export_value" }
