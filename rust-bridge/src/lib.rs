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
        let sys = py.import("sys")?;
        let path = sys.getattr("path")?;
        path.call_method1("append", ("../python-core",))?; // 개발 환경용 경로 해킹
        
        let worker = py.import("ai_worker")?;
        let args = (input, input_int);
        let result: String = worker.call_method1("process_data", args)?.extract()?;
        Ok(result)
    }).unwrap_or_else(|e| format!("Python Error: {}", e));

    env.new_string(output).expect("Couldn't create java string!")
}
