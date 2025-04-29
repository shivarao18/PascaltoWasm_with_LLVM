@echo off
REM compile_wasm.bat - compile LLVM IR to Wasm via llc+wasm-ld with imports

REM Input LLVM IR (.ll) file
set input=%1
set filename=%~n1
set output_dir=Output\wasm

REM Create output directory if it doesn't exist
if not exist "%output_dir%" mkdir "%output_dir%"

echo Compiling %input% to Wasm object...
llc -march=wasm32 -filetype=obj "%input%" -o "%output_dir%\%filename%.o"

echo Linking Wasm module (importing memory, allowing undefined)...
wasm-ld --no-entry --import-memory --allow-undefined --export=main --export=memory --export=print_int --export=get_export_value --export=export_value --export=global_a --export=global_b "%output_dir%\%filename%.o" -o "%output_dir%\%filename%.wasm"

echo WebAssembly module created at %output_dir%\%filename%.wasm
