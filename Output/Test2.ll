; ModuleID = 'delphi_program'
target triple = "wasm32-unknown-unknown"

@.str.newline = private constant [2 x i8] c"\0A\00"

declare void @js_print_str(i8*)
declare void @js_print_i32(i32)

define i32 @main() {
entry:
  %I = alloca i32
  store i32 0, i32* %I
  store i32 10, i32* %I
  br label %for.cond.0
for.cond.0:
  %t0 = load i32, i32* %I
  %t1 = icmp sge i32 %t0, 1
  br i1 %t1, label %for.body.1, label %for.end.3
for.body.1:
  %t2 = load i32, i32* %I
  call void @js_print_i32(i32 %t2)
  %t3 = getelementptr [2 x i8], [2 x i8]* @.str.newline, i32 0, i32 0
  call void @js_print_str(i8* %t3)
  br label %for.inc.2
for.inc.2:
  %t4 = load i32, i32* %I
  %t5 = sub i32 %t4, 1
  store i32 %t5, i32* %I
  br label %for.cond.0
for.end.3:
  ret i32 0
}

attributes #0 = { "wasm-export-name"="main" }
attributes #1 = { "wasm-export-name"="print_int" }
attributes #2 = { "wasm-export-name"="get_export_value" }
