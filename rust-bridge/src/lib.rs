use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use pyo3::prelude::*;
use pyo3::types::PyTuple;

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
    // Also include Lib/site-packages if needed, but for now simple setup:
    let python_path = format!("{};{}/Lib;{}/DLLs", home, home, home); 
    std::env::set_var("PYTHONPATH", &python_path);

    // Initialize Python Interpreter (Optional here, but good for verification)
    // pyo3::prepare_freethreaded_python(); 
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

    let output = Python::with_gil(|py| -> PyResult<String> {
        // Debug: Check sys.path
        let sys = py.import("sys")?;
        let path = sys.getattr("path")?;
        let path_str: String = path.to_string();
        println!("[Rust] Current sys.path: {}", path_str);

        // Force append PYTHONHOME just in case env var didn't propagate to path
        if let Ok(home) = std::env::var("PYTHONHOME") {
             path.call_method1("append", (home,))?;
        }

        let worker = py.import("ai_worker")?;
        let args = (input, input_int);
        let result: String = worker.call_method1("process_data", args)?.extract()?;
        Ok(result)
    }).unwrap_or_else(|e| format!("Python Error: {}", e));

    env.new_string(output).expect("Couldn't create java string!")
}
