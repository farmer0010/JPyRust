use jni::JNIEnv;
use jni::JavaVM;
use jni::objects::{JClass, JByteBuffer, JString, JObject};
use jni::sys::{jint, jbyteArray, jlong};
use std::fs::File;
use std::io::{Read, Write, BufRead, BufReader, BufWriter};
use std::process::{Command, Child, Stdio, ChildStdin, ChildStdout};
use std::sync::Mutex;
use shared_memory::ShmemConf;

const OUTPUT_SHM_SIZE: usize = 1024 * 1024;

struct PythonDaemon {
    child: Child,
    stdin: BufWriter<ChildStdin>,
    stdout: BufReader<ChildStdout>,
}

struct BridgeState {
    daemon: Mutex<Option<PythonDaemon>>,
    work_dir: String,
    session_key: String,
    instance_id: String,
    java_vm: JavaVM,
    bridge_obj: jni::objects::GlobalRef,
}

impl BridgeState {
    fn log_to_java(&self, level: &str, msg: &str) {
        let mut env = match self.java_vm.attach_current_thread_as_daemon() {
            Ok(e) => e,
            Err(_) => {
                eprintln!("[Rust-Fallback] [{}] {}", level, msg);
                return;
            }
        };

        let status: jni::errors::Result<()> = (|| {
            let l_str = env.new_string(level)?;
            let m_str = env.new_string(msg)?;
            env.call_method(
                &self.bridge_obj,
                "log",
                "(Ljava/lang/String;Ljava/lang/String;)V",
                &[jni::objects::JValue::Object(&l_str.into()), jni::objects::JValue::Object(&m_str.into())],
            )?;
            Ok(())
        })();

        if let Err(_) = status {
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_describe();
                let _ = env.exception_clear();
            }
            eprintln!("[Rust-Fallback] (Java Threw) [{}] {}", level, msg);
        }
    }

    fn find_python_executable(&self) -> String {
        let embedded_path = format!("{}/python_dist/python.exe", self.work_dir);
        if std::path::Path::new(&embedded_path).exists() {
            return embedded_path;
        }
        self.log_to_java("WARN", "[Rust] Embedded Python not found. Falling back to 'python'.");
        "python".to_string()
    }

    fn spawn_python_daemon(&self) -> Result<PythonDaemon, String> {
        let python_exe = self.find_python_executable();
        let script_path = format!("{}/ai_worker.py", self.work_dir);

        let mut child_cmd = Command::new(&python_exe);
        child_cmd.arg(&script_path).arg("--daemon");
        child_cmd.arg("--mem-key").arg(&self.session_key);
        child_cmd.arg("--instance-id").arg(&self.instance_id);
        child_cmd.env("PYTHONIOENCODING", "utf-8");
        child_cmd.env("PYTHONPATH", &self.work_dir);

        let mut child = child_cmd
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .map_err(|e| format!("Failed to spawn Python daemon: {}", e))?;

        let stdin = child.stdin.take().ok_or("Failed to capture stdin")?;
        let stdout = child.stdout.take().ok_or("Failed to capture stdout")?;

        let stdin_writer = BufWriter::new(stdin);
        let mut stdout_reader = BufReader::new(stdout);

        let mut ready_line = String::new();
        let timeout_start = std::time::Instant::now();
        let timeout_duration = std::time::Duration::from_secs(600);

        loop {
            if timeout_start.elapsed() > timeout_duration {
                child.kill().ok();
                return Err("Timeout waiting for Python READY".to_string());
            }

            match stdout_reader.read_line(&mut ready_line) {
                Ok(0) => {
                    child.kill().ok();
                    return Err("Python process exited early".to_string());
                }
                Ok(_) => {
                    let trimmed = ready_line.trim();
                    if trimmed == "READY" { break; }
                    ready_line.clear();
                }
                Err(e) => {
                    child.kill().ok();
                    return Err(format!("Error reading from Python: {}", e));
                }
            }
        }

        Ok(PythonDaemon {
            child,
            stdin: stdin_writer,
            stdout: stdout_reader,
        })
    }

    fn get_or_spawn_daemon(&self) -> Result<(), String> {
        let mut daemon_guard = self.daemon.lock().unwrap();
        if let Some(ref mut daemon) = *daemon_guard {
            match daemon.child.try_wait() {
                Ok(Some(_)) => {}
                Ok(None) => return Ok(()),
                Err(_) => {}
            }
        }
        let daemon = self.spawn_python_daemon()?;
        *daemon_guard = Some(daemon);
        Ok(())
    }

    fn send_execute_command(&self, task_type: &str, request_id: &str, metadata: &str) -> Result<String, String> {
        let mut daemon_guard = self.daemon.lock().unwrap();
        let daemon = daemon_guard.as_mut().ok_or("Python daemon not initialized")?;

        let command = format!("EXECUTE {} {} {}\n", task_type, request_id, metadata);
        daemon.stdin.write_all(command.as_bytes()).map_err(|e| e.to_string())?;
        daemon.stdin.flush().map_err(|e| e.to_string())?;

        let mut response = String::new();
        loop {
            response.clear();
            match daemon.stdout.read_line(&mut response) {
                Ok(0) => return Err("Python daemon closed".to_string()),
                Ok(_) => {
                    let trimmed = response.trim();
                    if trimmed.starts_with("DONE") { return Ok(trimmed.to_string()); }
                    else if trimmed.starts_with("ERROR") { return Err(trimmed.to_string()); }
                }
                Err(e) => return Err(e.to_string()),
            }
        }
    }
}

unsafe fn get_state<'a>(env: &mut JNIEnv<'a>, obj: &JObject<'a>) -> &'a BridgeState {
    let ptr = env.get_field(obj, "nativePtr", "J").unwrap().j().unwrap();
    &*(ptr as *const BridgeState)
}

#[no_mangle]
pub extern "system" fn Java_com_jpyrust_JPyRustBridge_initNative<'local>(
    mut env: JNIEnv<'local>,
    obj: JObject<'local>,
    work_dir: JString<'local>,
    _source_script_dir: JString<'local>,
    _model_path: JString<'local>,
    _confidence: jni::sys::jfloat,
    memory_key: JString<'local>,
) {
    let work_dir_str: String = env.get_string(&work_dir).unwrap().into();
    let memory_key_str: String = env.get_string(&memory_key).unwrap().into();
    let instance_id: String = env.get_field(&obj, "instanceId", "Ljava/lang/String;").unwrap().l().unwrap().into();
    let instance_id_str: String = env.get_string(&instance_id.into()).unwrap().into();

    let vm = env.get_java_vm().unwrap();
    let global_obj = env.new_global_ref(&obj).unwrap();

    let state = Box::new(BridgeState {
        daemon: Mutex::new(None),
        work_dir: work_dir_str,
        session_key: memory_key_str,
        instance_id: instance_id_str,
        java_vm: vm,
        bridge_obj: global_obj,
    });

    let state_ptr = Box::into_raw(state) as jlong;
    env.set_field(&obj, "nativePtr", "J", jni::objects::JValue::Long(state_ptr)).unwrap();

    let state = unsafe { &*(state_ptr as *const BridgeState) };
    if let Err(e) = state.get_or_spawn_daemon() {
        state.log_to_java("ERROR", &format!("Daemon start failed: {}", e));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_jpyrust_JPyRustBridge_executeTask<'local>(
    mut env: JNIEnv<'local>,
    obj: JObject<'local>,
    _work_dir: JString<'local>,
    task_type: JString<'local>,
    request_id: JString<'local>,
    metadata: JString<'local>,
    input_data: JByteBuffer<'local>,
    input_length: jint,
) -> jbyteArray {
    let state = unsafe { get_state(&mut env, &obj) };
    
    let task_type_str: String = env.get_string(&task_type).unwrap().into();
    let request_id_str: String = env.get_string(&request_id).unwrap().into();
    let metadata_str: String = env.get_string(&metadata).unwrap().into();

    let length = input_length as usize;
    let buffer_ptr = env.get_direct_buffer_address(&input_data).unwrap();
    let data = unsafe { std::slice::from_raw_parts(buffer_ptr, length) };

    if !["YOLO", "EDGE_DETECT"].contains(&task_type_str.as_str()) {
        return execute_with_file_fallback(&mut env, state, &task_type_str, &request_id_str, &metadata_str, data);
    }

    let shm_name_in = format!("{}_{}", state.session_key, &request_id_str[..8.min(request_id_str.len())]);
    let mut shm_in = match create_shmem_permissive(&shm_name_in, length) {
        Ok(m) => m,
        Err(_) => return execute_with_file_fallback(&mut env, state, &task_type_str, &request_id_str, &metadata_str, data),
    };

    unsafe { shm_in.as_slice_mut()[..length].copy_from_slice(data); }

    let shm_name_out = format!("{}_out_{}", state.session_key, &request_id_str[..8.min(request_id_str.len())]);
    let shm_out = match create_shmem_permissive(&shm_name_out, OUTPUT_SHM_SIZE) {
        Ok(m) => m,
        Err(_) => return execute_with_file_fallback(&mut env, state, &task_type_str, &request_id_str, &metadata_str, data),
    };

    let shmem_metadata = format!("SHMEM {} {} {} {} {}", shm_name_in, length, shm_name_out, OUTPUT_SHM_SIZE, metadata_str);

    let mut final_result_len = 0;
    if let Err(_) = state.get_or_spawn_daemon() { return std::ptr::null_mut(); }

    match state.send_execute_command(&task_type_str, &request_id_str, &shmem_metadata) {
        Ok(result) => {
            if let Some(len_str) = result.strip_prefix("DONE ") {
                final_result_len = len_str.trim().parse().unwrap_or(0);
            }
        }
        Err(_) => return std::ptr::null_mut(),
    }

    let output_data = if final_result_len > 0 && final_result_len <= OUTPUT_SHM_SIZE {
        unsafe { shm_out.as_slice()[..final_result_len].to_vec() }
    } else {
        Vec::new()
    };

    match env.new_byte_array(output_data.len() as i32) {
        Ok(arr) => {
            let signed: Vec<i8> = output_data.iter().map(|&b| b as i8).collect();
            env.set_byte_array_region(&arr, 0, &signed).unwrap();
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut()
    }
}

fn execute_with_file_fallback(
    env: &mut JNIEnv,
    state: &BridgeState,
    task_type: &str,
    request_id: &str,
    metadata: &str,
    data: &[u8],
) -> jbyteArray {
    let input_file = format!("{}/input_{}.dat", state.work_dir, request_id);
    let output_file = format!("{}/output_{}.dat", state.work_dir, request_id);
    
    if let Err(_) = write_data_file(&input_file, data) { return std::ptr::null_mut(); }

    if let Err(_) = state.get_or_spawn_daemon() { return std::ptr::null_mut(); }

    match state.send_execute_command(task_type, request_id, metadata) {
        Ok(_) => {}
        Err(_) => {
            cleanup_files(&input_file, &output_file);
            return std::ptr::null_mut();
        }
    }

    let output_data = match read_data_file(&output_file) {
        Ok(data) => data,
        Err(_) => {
            cleanup_files(&input_file, &output_file);
            return std::ptr::null_mut();
        }
    };

    cleanup_files(&input_file, &output_file);

    match env.new_byte_array(output_data.len() as i32) {
        Ok(arr) => {
            let signed: Vec<i8> = output_data.iter().map(|&b| b as i8).collect();
            env.set_byte_array_region(&arr, 0, &signed).unwrap();
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut()
    }
}

#[cfg(target_os = "windows")]
fn create_shmem_permissive(name: &str, size: usize) -> Result<shared_memory::Shmem, String> {
    use windows_sys::Win32::Foundation::{CloseHandle, INVALID_HANDLE_VALUE, GetLastError, LocalFree};
    use windows_sys::Win32::Security::Authorization::ConvertStringSecurityDescriptorToSecurityDescriptorW;
    use windows_sys::Win32::Security::SECURITY_ATTRIBUTES;
    use windows_sys::Win32::System::Memory::{CreateFileMappingW, PAGE_READWRITE};
    use std::ptr;
    use std::ffi::c_void;

    unsafe {
        let mut sd: *mut c_void = ptr::null_mut();
        let sddl = "D:(A;;GA;;;WD)\0".encode_utf16().collect::<Vec<u16>>();
        if ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.as_ptr(), 1, &mut sd, ptr::null_mut()) == 0 {
             return Err(format!("SDDL failed: {}", GetLastError()));
        }
        let mut sa = SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: sd,
            bInheritHandle: 0,
        };
        let name_wide = name.encode_utf16().chain(std::iter::once(0)).collect::<Vec<u16>>();
        let h_map = CreateFileMappingW(INVALID_HANDLE_VALUE, &mut sa, PAGE_READWRITE, 0, size as u32, name_wide.as_ptr());
        let res = if h_map == 0 { Err(format!("Mapping failed: {}", GetLastError())) } 
        else { shared_memory::ShmemConf::new().os_id(name).open().map_err(|e| e.to_string()) };
        if h_map != 0 { CloseHandle(h_map); }
        LocalFree(sd);
        res
    }
}

#[cfg(not(target_os = "windows"))]
fn create_shmem_permissive(name: &str, size: usize) -> Result<shared_memory::Shmem, String> {
    shared_memory::ShmemConf::new().size(size).os_id(name).create().map_err(|e| e.to_string())
}

fn write_data_file(path: &str, data: &[u8]) -> std::io::Result<()> {
    let mut file = File::create(path)?;
    let len = data.len();
    let header = [(len >> 24) as u8, (len >> 16) as u8, (len >> 8) as u8, len as u8];
    file.write_all(&header)?;
    file.write_all(data)?;
    file.flush()?;
    Ok(())
}

fn read_data_file(path: &str) -> std::io::Result<Vec<u8>> {
    let mut file = File::open(path)?;
    let mut header = [0u8; 4];
    file.read_exact(&mut header)?;
    let len = ((header[0] as usize) << 24) | ((header[1] as usize) << 16) | ((header[2] as usize) << 8) | (header[3] as usize);
    let mut data = vec![0u8; len];
    file.read_exact(&mut data)?;
    Ok(data)
}

fn cleanup_files(input: &str, output: &str) {
    let _ = std::fs::remove_file(input);
    let _ = std::fs::remove_file(output);
}
