use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use pyo3::prelude::*;
use pyo3::types::{PyTuple, PyString};

#[no_mangle]
pub extern "system" fn Java_com_jpyrust_JPyRustBridge_hello<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    let output = "Hello from Rust Bridge! (Success)";
    env.new_string(output).expect("Couldn't create java string!")
}

#[no_mangle]
pub extern "system" fn Java_com_jpyrust_JPyRustBridge_initPython<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    python_home: JString<'local>,
) {
    let home: String = env
        .get_string(&python_home)
        .expect("Couldn't get java string!")
        .into();
    
// Configure Python Environment for Embedded Runtime
    std::env::set_var("PYTHONHOME", &home);
    
    // Add home to PYTHONPATH so ai_worker.py (which is in home) can be imported
    let python_path = format!("{};{}/Lib;{}/DLLs", home, home, home); 
    std::env::set_var("PYTHONPATH", &python_path);

    // [Patch 1] Signal Conflict Prevention
    // Use unsafe ffi to call Py_InitializeEx(0) which skips signal handler registration.
    // This is crucial for Java interoperability to prevent JVM signal chains from breaking.
    unsafe {
        if pyo3::ffi::Py_IsInitialized() == 0 {
            pyo3::ffi::Py_InitializeEx(0);
        }
    }
    
    // [Patch 2] Windows DLL Path Fix
    // Ensure Python can find DLLs in the temp directory (critical for numpy/ctypes on Windows)
    Python::with_gil(|py| {
        let sys = py.import("sys").ok();
        let os = py.import("os").ok();
        
        if let (Some(_sys), Some(os)) = (sys, os) {
             let platform = os.getattr("name").unwrap_or_else(|_| PyString::new(py, "").into());
             if platform.to_string() == "nt" {
                 // os.add_dll_directory(home)
                 let _ = os.call_method1("add_dll_directory", (&home,));
                 println!("[Rust] Added DLL directory: {}", home);
             }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_jpyrust_JPyRustBridge_runPythonAI<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input_str: JString<'local>,
    input_int: jint,
) -> JString<'local> {
    let input: String = env
        .get_string(&input_str)
        .expect("Couldn't get java string!")
        .into();

    // [Patch 3] GIL Management Pattern
    // For heavy computations, use allow_threads to release GIL:
    // Python::with_gil(|py| {
    //     py.allow_threads(|| {
    //         // Heavy Rust calculation here...
    //     });
    // });

    let output = Python::with_gil(|py| -> PyResult<String> {
        // Debug: Check sys.path
        let sys = py.import("sys")?;
        let path = sys.getattr("path")?;
        let path_str: String = path.to_string();
        println!("[Rust] Current sys.path: {}", path_str);

        // Force append PYTHONHOME just in case env var didn't propagate to path
        if let Ok(home) = std::env::var("PYTHONHOME") {
             let _ = path.call_method1("append", (home,));
        }

        let worker = py.import("ai_worker")?;
        let args = (input, input_int);
        
        // Execute Python function
        let result: String = worker.call_method1("process_data", args)?.extract()?;
        Ok(result)
    }).unwrap_or_else(|e| format!("Python Error: {}", e));

    env.new_string(output).expect("Couldn't create java string!")
}
