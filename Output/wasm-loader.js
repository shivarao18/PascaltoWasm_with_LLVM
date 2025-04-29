// Keep track of loaded WASM modules
let loadedModules = {};
const outputElement = document.getElementById('output');
const programSelect = document.getElementById('program-select');
const runButton = document.getElementById('run-button');

// Function to log output
function log(message) {
    outputElement.innerHTML += `<div>${message}</div>`;
}

// Clear output
function clearOutput() {
    outputElement.innerHTML = '';
}

// Function to scan for available WASM modules in wasm directory
async function scanWasmDirectory() {
    try {
        // Try to directly scan for all possible files
        const knownFiles = await checkKnownFiles();
        console.log("Found files:", knownFiles);

        // If we have files, return them
        if (knownFiles.length > 0) {
            return knownFiles;
        }

        // If direct scan didn't work, try modules.json as fallback
        const response = await fetch('wasm/modules.json');
        if (response.ok) {
            const modules = await response.json();
            console.log("Found modules from JSON:", modules);
            return modules;
        }

        // If all else fails, return hardcoded defaults
        console.log("Using hardcoded defaults");
        return ["Test1.wasm", "Simple.wasm"];
    } catch (error) {
        console.log("Error scanning directory:", error);
        // Return hardcoded defaults when all else fails
        return ["Test1.wasm", "Simple.wasm"];
    }
}

// Check for known wasm files that might exist
async function checkKnownFiles() {
    // Define all possible file patterns to check
    const possibleFiles = [];

    // Test# pattern
    for (let i = 1; i <= 10; i++) {
        possibleFiles.push(`Test${i}.wasm`);
    }

    // Add other known files
    possibleFiles.push("Simple.wasm");

    // Try to directly detect files in wasm directory
    const availableFiles = [];

    for (const file of possibleFiles) {
        try {
            // Try to fetch the file (HEAD is more efficient than GET)
            console.log("Checking if file exists:", file);
            const response = await fetch(`wasm/${file}`, { method: 'HEAD' });
            if (response.ok) {
                console.log(`File ${file} found!`);
                availableFiles.push(file);
            }
        } catch (e) {
            // File doesn't exist or error occurred, skip it
            console.log(`File ${file} not found or error:`, e.message);
        }
    }

    // Always include hardcoded files to ensure we have something
    if (!availableFiles.includes("Test1.wasm") && possibleFiles.includes("Test1.wasm")) {
        console.log("Adding Test1.wasm as fallback");
        availableFiles.push("Test1.wasm");
    }

    if (!availableFiles.includes("Simple.wasm") && possibleFiles.includes("Simple.wasm")) {
        console.log("Adding Simple.wasm as fallback");
        availableFiles.push("Simple.wasm");
    }

    return availableFiles;
}

// Function to load available WASM modules from the /wasm directory
async function loadAvailableModules() {
    try {
        // Scan for all available modules
        console.log("Scanning for WASM modules...");
        const modules = await scanWasmDirectory();
        console.log("Modules found:", modules);

        // Clear existing options
        programSelect.innerHTML = '';

        if (!modules || modules.length === 0) {
            console.log("No modules found!");
            log("No WebAssembly modules found. Make sure you've compiled your Pascal programs.");

            // Add fallback options anyway
            const fallbacks = ["Test1.wasm", "Simple.wasm"];
            fallbacks.forEach(module => {
                const option = document.createElement('option');
                option.value = module;
                option.textContent = module.replace('.wasm', '') + " (not verified)";
                programSelect.appendChild(option);
            });
            return;
        }

        // Add options for each module
        modules.forEach(module => {
            const option = document.createElement('option');
            option.value = module;
            option.textContent = module.replace('.wasm', '');
            programSelect.appendChild(option);
        });

        // Select Simple.wasm if it exists
        if (modules.includes("Simple.wasm")) {
            programSelect.value = "Simple.wasm";
        }
    } catch (error) {
        console.error("Error loading modules:", error);
        log("Error loading available programs: " + error.message);

        // Add fallback options anyway
        const fallbacks = ["Test1.wasm", "Simple.wasm"];
        fallbacks.forEach(module => {
            const option = document.createElement('option');
            option.value = module;
            option.textContent = module.replace('.wasm', '') + " (fallback)";
            programSelect.appendChild(option);
        });
    }
}

// Helper to read string from memory
function readString(memory, ptr) {
    console.log("Reading string from memory at address:", ptr);

    // Check if memory and buffer are valid
    if (!memory || !memory.buffer) {
        console.error("Memory or buffer is invalid");
        return "[Memory error]";
    }

    // Create byte view of memory
    const view = new Uint8Array(memory.buffer);
    let str = '';
    let bytes = [];

    try {
        // Read bytes until null terminator
        let i = ptr;
        const maxLength = 200; // Safety limit to prevent infinite loops
        let count = 0;

        while (view[i] !== 0 && count < maxLength) {
            str += String.fromCharCode(view[i]);
            bytes.push(view[i]);
            i++;
            count++;
        }

        console.log("Read bytes:", bytes);
        console.log("Read string:", str);

        return str;
    } catch (e) {
        console.error("Error reading string from memory:", e);
        return "[Read error]";
    }
}

// Function to load and instantiate a WASM module
async function loadWasmModule(moduleName) {
    if (loadedModules[moduleName]) {
        return loadedModules[moduleName];
    }

    try {
        log(`Fetching wasm/${moduleName}...`);
        const response = await fetch(`wasm/${moduleName}`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const bytes = await response.arrayBuffer();
        log("Compiling WebAssembly module...");

        // Memory for Wasm
        const memory = new WebAssembly.Memory({ initial: 256 });

        // Import object with js_print_str and js_print_i32
        const importObject = {
            env: {
                memory: memory,
                js_print_str: function (ptr) {
                    const s = readString(memory, ptr);
                    log(s);
                },
                js_print_i32: function (val) {
                    log(val.toString());
                }
            }
        };

        const { instance } = await WebAssembly.instantiate(bytes, importObject);
        loadedModules[moduleName] = instance;
        log("WebAssembly module loaded successfully");
        return instance;
    } catch (error) {
        console.error("Failed to load WASM module:", error);
        log(`Error: ${error.message}`);
        return null;
    }
}

// Run the selected program
async function runProgram() {
    const selectedModule = programSelect.value;
    if (!selectedModule) {
        log("Please select a program to run");
        return;
    }

    clearOutput();
    log(`Running ${selectedModule}...`);

    const moduleInstance = await loadWasmModule(selectedModule);
    if (moduleInstance) {
        // Find the main function
        const mainFunction = moduleInstance.exports.main ||
            moduleInstance.exports.pascal_main ||
            moduleInstance.exports._main;

        if (mainFunction) {
            try {
                // Run the program
                const startTime = performance.now();
                const result = mainFunction();
                const endTime = performance.now();

                log(`Program executed in ${(endTime - startTime).toFixed(2)}ms. Return value: ${result}`);

                // Display any global values (variables exported from the program)
                const globals = Object.keys(moduleInstance.exports)
                    .filter(name => name.startsWith('global_'));

                if (globals.length > 0) {
                    log("Final variable values:");

                    globals.forEach(name => {
                        const varName = name.replace('global_', '');

                        // Get the global object
                        const globalObj = moduleInstance.exports[name];
                        console.log("Global:", name, globalObj);

                        // Try to get the value in different ways
                        let value;
                        if (typeof globalObj === 'object' && 'value' in globalObj) {
                            value = globalObj.value;
                        } else if (typeof globalObj === 'number') {
                            value = globalObj;
                        } else {
                            value = "[Unknown format]";
                        }

                        log(`${varName} = ${value}`);
                    });
                }
            } catch (error) {
                console.error("Error executing WASM program:", error);
                log(`Error executing program: ${error.message}`);
            }
        } else {
            log("Module loaded but no 'main' function found. Available exports: " +
                Object.keys(moduleInstance.exports).join(", "));
        }
    } else {
        log("Failed to load module");
    }
}

// Initialize the page
window.addEventListener('DOMContentLoaded', () => {
    log("Initializing WebAssembly loader...");
    loadAvailableModules();

    // Add click handler for run button
    runButton.addEventListener('click', runProgram);

    log("Ready! Select a program and click 'Run Program'");
});