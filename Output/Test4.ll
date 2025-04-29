; ModuleID = 'delphi_program'
target triple = "wasm32-unknown-unknown"

@.str.newline = private constant [2 x i8] c"\0A\00"

declare void @js_print_str(i8*)
declare void @js_print_i32(i32)

define i32 @main() {
entry:
  %I = alloca i32
  store i32 0, i32* %I
  store i32 0, i32* %I
  br label %while.cond.0
while.cond.0:
  %t0 = load i32, i32* %I
  %t1 = icmp slt i32 %t0, 10
  br i1 %t1, label %while.body.1, label %while.end.2
while.body.1:
  %t2 = load i32, i32* %I
  %t3 = add i32 %t2, 1
  store i32 %t3, i32* %I
  %t4 = load i32, i32* %I
  %t5 = icmp eq i32 %t4, 3
  br i1 %t5, label %if.then.3, label %if.end.5
if.then.3:
  br label %while.cond.0
  br label %if.end.5
if.end.5:
  %t6 = load i32, i32* %I
  %t7 = icmp eq i32 %t6, 8
  br i1 %t7, label %if.then.6, label %if.end.8
if.then.6:
  br label %while.end.2
  br label %if.end.8
if.end.8:
  %t8 = load i32, i32* %I
  call void @js_print_i32(i32 %t8)
  %t9 = getelementptr [2 x i8], [2 x i8]* @.str.newline, i32 0, i32 0
  call void @js_print_str(i8* %t9)
  br label %while.cond.0
while.end.2:
  ret i32 0
}

attributes #0 = { "wasm-export-name"="main" }
attributes #1 = { "wasm-export-name"="print_int" }
attributes #2 = { "wasm-export-name"="get_export_value" }
