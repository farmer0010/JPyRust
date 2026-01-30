use jni::JNIEnv;
use jni::JavaVM;
use jni::objects::{JClass, JByteBuffer, JString, JValue, GlobalRef};
use jni::sys::{jint, jbyteArray};
use std::fs::File;
use std::io::{Read, Write, BufRead, BufReader, BufWriter};
use std::process::{Command, Child, Stdio, ChildStdin, ChildStdout};
use std::sync::Mutex;
use shared_memory::ShmemConf;

#[macro_use]
extern crate lazy_static;

const HEADER_SIZE: usize = 4;
const OUTPUT_SHM_SIZE: usize = 1024 * 1024; 

struct PythonDaemon {
    child: Child,
    stdin: BufWriter<ChildStdin>,
    stdout: BufReader<ChildStdout>,
}

lazy_static! {
    static ref PYTHON_DAEMON: Mutex<Option<PythonDaemon>> = Mutex::new(None);
    static ref WORK_DIR: Mutex<Option<String>> = Mutex::new(None);
    static ref MODEL_CONFIG: Mutex<Option<(String, f32)>> = Mutex::new(None);
}

static mut JAVA_VM: Option<JavaVM> = None;
static mut BRIDGE_CLASS: Option<GlobalRef> = None;

fn log_to_java(level: &str, msg: &str) {
    let vm = unsafe {
        match JAVA_VM.as_ref() {
            Some(v) => v,
            None => {
                eprintln!("[Rust-Fallback] [{}] {}", level, msg);
                return;
            }
        }
    };

    let mut env = match vm.attach_current_thread_as_daemon() {
        Ok(e) => e,
        Err(_) => {
            eprintln!("[Rust-Fallback] (Attach Failed) [{}] {}", level, msg);
            return;
        }
    };

    let status: jni::errors::Result<()> = (|| {
        let class_ref = unsafe {
            match BRIDGE_CLASS.as_ref() {
                Some(c) => c,
                None => return Err(jni::errors::Error::JavaException),
            }
        };

        let l_str = env.new_string(level)?;
        let m_str = env.new_string(msg)?;
        
        let l_obj: jni::objects::JObject = l_str.into();
        let m_obj: jni::objects::JObject = m_str.into();

        env.call_static_method(
            class_ref,
            "log",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            &[JValue::Object(&l_obj), JValue::Object(&m_obj)],
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

fn spawn_python_daemon(work_dir: &str) -> Result<PythonDaemon, String> {
    log_to_java("INFO", &format!("[Rust] Spawning Python daemon in {}", work_dir));

    let python_exe = format!("{}/python.exe", work_dir);
    let script_path = format!("{}/ai_worker.py", work_dir);

    let mut child_cmd = Command::new(&python_exe);
    child_cmd.arg(&script_path).arg("--daemon");
    {
        let config_guard = MODEL_CONFIG.lock().unwrap();
        if let Some((ref path, conf)) = *config_guard {
             child_cmd.arg("--model").arg(path).arg("--conf").arg(conf.to_string());
             log_to_java("INFO", &format!("[Rust] Configured with Model: {}, Conf: {}", path, conf));
        }
    }

    let mut child = child_cmd
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .map_err(|e| format!("Failed to spawn Python daemon: {}", e))?;

    log_to_java("INFO", &format!("[Rust] Python process spawned (PID: {:?})", child.id()));

    let stdin = child.stdin.take().ok_or("Failed to capture stdin")?;
    let stdout = child.stdout.take().ok_or("Failed to capture stdout")?;

    let stdin_writer = BufWriter::new(stdin);
    let mut stdout_reader = BufReader::new(stdout);
    log_to_java("INFO", "[Rust] Waiting for READY signal...");

    let mut ready_line = String::new();
    let timeout_start = std::time::Instant::now();
    let timeout_duration = std::time::Duration::from_secs(60);

    loop {
        if timeout_start.elapsed() > timeout_duration {
            child.kill().ok();
            return Err("Timeout waiting for Python READY signal".to_string());
        }

        match stdout_reader.read_line(&mut ready_line) {
            Ok(0) => {
                child.kill().ok();
                return Err("Python process exited before sending READY".to_string());
            }
            Ok(_) => {
                let trimmed = ready_line.trim();
                log_to_java("INFO", &format!("[Python Init] {}", trimmed));
                
                if trimmed == "READY" {
                    log_to_java("INFO", "[Rust] Handshake complete!");
                    break;
                }
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

fn get_or_spawn_daemon(work_dir: &str) -> Result<(), String> {
    let mut daemon_guard = PYTHON_DAEMON.lock().unwrap();
    
    if let Some(ref mut daemon) = *daemon_guard {
        match daemon.child.try_wait() {
            Ok(Some(status)) => {
                log_to_java("WARN", &format!("[Rust] Python daemon exited: {:?}. Respawning...", status));
            }
            Ok(None) => return Ok(()),
            Err(e) => {
                log_to_java("ERROR", &format!("[Rust] Error checking daemon: {}. Respawning...", e));
            }
        }
    }

    let daemon = spawn_python_daemon(work_dir)?;
    *daemon_guard = Some(daemon);
    
    let mut work_dir_guard = WORK_DIR.lock().unwrap();
    *work_dir_guard = Some(work_dir.to_string());
    
    Ok(())
}

fn send_execute_command(task_type: &str, request_id: &str, metadata: &str) -> Result<String, String> {
    let mut daemon_guard = PYTHON_DAEMON.lock().unwrap();
    
    let daemon = daemon_guard.as_mut()
        .ok_or("Python daemon not initialized")?;

    let command = format!("EXECUTE {} {} {}\n", task_type, request_id, metadata);
    log_to_java("DEBUG", &format!("[Rust] Sending: EXECUTE {} {} ...", task_type, &request_id[..8.min(request_id.len())]));

    daemon.stdin.write_all(command.as_bytes())
        .map_err(|e| format!("Failed to write to Python: {}", e))?;
    daemon.stdin.flush()
        .map_err(|e| format!("Failed to flush: {}", e))?;

    let mut response = String::new();
    loop {
        response.clear();
        match daemon.stdout.read_line(&mut response) {
            Ok(0) => return Err("Python daemon closed unexpectedly".to_string()),
            Ok(_) => {
                let trimmed = response.trim();
                log_to_java("INFO", &format!("[Python] {}", trimmed));

                if trimmed.starts_with("DONE") {
                    return Ok(trimmed.to_string());
                } else if trimmed.starts_with("ERROR") {
                    return Err(trimmed.to_string());
                }
            }
            Err(e) => return Err(format!("Read error: {}", e)),
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_jpyrust_JPyRustBridge_initNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    work_dir: JString<'local>,
    _source_script_dir: JString<'local>,
    model_path: JString<'local>,
    confidence: jni::sys::jfloat,
) {
    if let Ok(vm) = env.get_java_vm() {
        unsafe { JAVA_VM = Some(vm); }
    } else {
        eprintln!("[Rust] Failed to get JavaVM");
    }

    match env.find_class("com/jpyrust/JPyRustBridge") {
        Ok(cls) => {
            match env.new_global_ref(cls) {
                Ok(global_cls) => unsafe { BRIDGE_CLASS = Some(global_cls); },
                Err(e) => eprintln!("[Rust] Failed to create global class ref: {}", e),
            }
        },
        Err(e) => eprintln!("[Rust] Failed to find class com/jpyrust/JPyRustBridge: {}", e),
    }

    log_to_java("INFO", "=== JPyRust Universal Bridge Initialization ===");
    log_to_java("INFO", "=== Level 2: Full Shared Memory Pipeline (Input/Output) ===");

    let work_dir_str: String = env.get_string(&work_dir)
        .expect("Failed to get work_dir")
        .into();
    
    let model_path_str: String = env.get_string(&model_path)
        .expect("Failed to get model_path")
        .into();

    log_to_java("INFO", &format!("[Rust Init] Work Directory: {}", work_dir_str));
    log_to_java("INFO", &format!("[Rust Init] Model Path: {}", model_path_str));
    log_to_java("INFO", &format!("[Rust Init] Confidence: {}", confidence));

    {
        let mut config_guard = MODEL_CONFIG.lock().unwrap();
        *config_guard = Some((model_path_str, confidence));
    }

    match get_or_spawn_daemon(&work_dir_str) {
        Ok(_) => log_to_java("INFO", "[Rust Init] Python daemon ready!"),
        Err(e) => log_to_java("ERROR", &format!("[Rust Init] Daemon start failed: {}", e)),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_jpyrust_JPyRustBridge_executeTask<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    work_dir: JString<'local>,
    task_type: JString<'local>,
    request_id: JString<'local>,
    metadata: JString<'local>,
    input_data: JByteBuffer<'local>,
    input_length: jint,
) -> jbyteArray {
    let start_time = std::time::Instant::now();
    
    let work_dir_str: String = match env.get_string(&work_dir) {
        Ok(s) => s.into(),
        Err(e) => {
            log_to_java("ERROR", &format!("[Rust] Invalid work_dir: {}", e));
            return std::ptr::null_mut();
        }
    };
    let task_type_str: String = env.get_string(&task_type).expect("task_type").into();
    let request_id_str: String = env.get_string(&request_id).expect("request_id").into();
    let metadata_str: String = env.get_string(&metadata).expect("metadata").into();

    log_to_java("INFO", &format!("[Rust] Task: {} | ID: {} | Mode: FULL-SHMEM", task_type_str, &request_id_str[..8.min(request_id_str.len())]));

    let length = input_length as usize;

    let buffer_ptr = match env.get_direct_buffer_address(&input_data) {
        Ok(ptr) => ptr,
        Err(e) => {
            log_to_java("ERROR", &format!("[Rust] Buffer error: {}", e));
            return std::ptr::null_mut();
        }
    };

    let data = unsafe { std::slice::from_raw_parts(buffer_ptr, length) };

    let shm_name_in = format!("jpyrust_{}", &request_id_str[..8.min(request_id_str.len())]);
    
    let mut shm_in = match ShmemConf::new()
        .size(length)
        .os_id(&shm_name_in)
        .create() 
    {
        Ok(m) => m,
        Err(e) => {
            log_to_java("WARN", &format!("[Rust] Input SHM creation failed: {}. Falling back to FILE.", e));
            return execute_with_file_fallback(
                env, &work_dir_str, &task_type_str, &request_id_str, 
                &metadata_str, data, start_time
            );
        }
    };

    {
        let shm_slice = unsafe { shm_in.as_slice_mut() };
        shm_slice[..length].copy_from_slice(data);
    }

    let shm_name_out = format!("jpyrust_out_{}", &request_id_str[..8.min(request_id_str.len())]);
    
    let shm_out = match ShmemConf::new()
        .size(OUTPUT_SHM_SIZE)
        .os_id(&shm_name_out)
        .create() 
    {
        Ok(m) => m,
        Err(e) => {
            log_to_java("WARN", &format!("[Rust] Output SHM creation failed: {}. Falling back to FILE.", e));
            drop(shm_in);
            return execute_with_file_fallback(
                env, &work_dir_str, &task_type_str, &request_id_str, 
                &metadata_str, data, start_time
            );
        }
    };

    let shmem_metadata = format!("SHMEM {} {} {} {} {}", shm_name_in, length, shm_name_out, OUTPUT_SHM_SIZE, metadata_str);

    let mut retry = 0;
    let mut final_result_len = 0;

    loop {
        if let Err(e) = get_or_spawn_daemon(&work_dir_str) {
            log_to_java("ERROR", &format!("[Rust] Daemon error: {}", e));
            drop(shm_in);
            drop(shm_out);
            return std::ptr::null_mut();
        }

        match send_execute_command(&task_type_str, &request_id_str, &shmem_metadata) {
            Ok(result) => {
                if let Some(len_str) = result.strip_prefix("DONE ") {
                    final_result_len = len_str.trim().parse().unwrap_or(0);
                }
                break;
            }
            Err(e) => {
                log_to_java("ERROR", &format!("[Rust] Execute error: {}", e));
                if retry < 1 {
                    retry += 1;
                    let mut guard = PYTHON_DAEMON.lock().unwrap();
                    if let Some(mut d) = guard.take() { d.child.kill().ok(); }
                    continue;
                }
                drop(shm_in);
                drop(shm_out);
                return std::ptr::null_mut();
            }
        }
    }

    let output_data = if final_result_len > 0 && final_result_len <= OUTPUT_SHM_SIZE {
        let shm_slice = unsafe { shm_out.as_slice() };
        shm_slice[..final_result_len].to_vec()
    } else {
        Vec::new()
    };

    drop(shm_in);
    drop(shm_out);

    log_to_java("INFO", &format!("[Rust] Completed in {:?} (FULL-SHMEM, {} bytes)", start_time.elapsed(), output_data.len()));

    match env.new_byte_array(output_data.len() as i32) {
        Ok(arr) => {
            let signed: Vec<i8> = output_data.iter().map(|&b| b as i8).collect();
            if env.set_byte_array_region(&arr, 0, &signed).is_err() {
                return std::ptr::null_mut();
            }
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut()
    }
}

fn execute_with_file_fallback(
    mut env: JNIEnv,
    work_dir_str: &str,
    task_type_str: &str,
    request_id_str: &str,
    metadata_str: &str,
    data: &[u8],
    start_time: std::time::Instant,
) -> jbyteArray {
    log_to_java("INFO", "[Rust] Falling back to file-based IPC");

    let input_file = format!("{}/input_{}.dat", work_dir_str, request_id_str);
    let output_file = format!("{}/output_{}.dat", work_dir_str, request_id_str);
    
    if let Err(e) = write_data_file(&input_file, data) {
        log_to_java("ERROR", &format!("[Rust] Write error: {}", e));
        return std::ptr::null_mut();
    }

    let mut retry = 0;
    loop {
        if let Err(e) = get_or_spawn_daemon(work_dir_str) {
            log_to_java("ERROR", &format!("[Rust] Daemon error: {}", e));
            cleanup_files(&input_file, &output_file);
            return std::ptr::null_mut();
        }

        let _ = std::fs::remove_file(&output_file);

        match send_execute_command(task_type_str, request_id_str, metadata_str) {
            Ok(result) => {
                log_to_java("DEBUG", &format!("[Rust] Result: {}", result));
                break;
            }
            Err(e) => {
                log_to_java("ERROR", &format!("[Rust] Execute error: {}", e));
                if retry < 1 {
                    retry += 1;
                    let mut guard = PYTHON_DAEMON.lock().unwrap();
                    if let Some(mut d) = guard.take() { d.child.kill().ok(); }
                    continue;
                }
                cleanup_files(&input_file, &output_file);
                return std::ptr::null_mut();
            }
        }
    }

    let output_data = match read_data_file(&output_file) {
        Ok(data) => data,
        Err(e) => {
            log_to_java("ERROR", &format!("[Rust] Read error: {}", e));
            cleanup_files(&input_file, &output_file);
            return std::ptr::null_mut();
        }
    };

    cleanup_files(&input_file, &output_file);

    log_to_java("INFO", &format!("[Rust] Completed in {:?} (FILE mode)", start_time.elapsed()));

    match env.new_byte_array(output_data.len() as i32) {
        Ok(arr) => {
            let signed: Vec<i8> = output_data.iter().map(|&b| b as i8).collect();
            if env.set_byte_array_region(&arr, 0, &signed).is_err() {
                return std::ptr::null_mut();
            }
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut()
    }
}

#[no_mangle]
pub extern "system" fn Java_com_jpyrust_JPyRustBridge_runPythonProcess<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    work_dir: JString<'local>,
    data: JByteBuffer<'local>,
    length: jint,
    width: jint,
    height: jint,
    channels: jint,
    request_id: JString<'local>,
) -> jbyteArray {
    let metadata = format!("{} {} {}", width, height, channels);
    
    let metadata_jstring = match env.new_string(&metadata) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    
    let task_type = match env.new_string("YOLO") {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    Java_com_jpyrust_JPyRustBridge_executeTask(
        env,
        _class,
        work_dir,
        task_type,
        request_id,
        metadata_jstring,
        data,
        length,
    )
}

fn write_data_file(path: &str, data: &[u8]) -> std::io::Result<()> {
    let mut file = File::create(path)?;
    let len = data.len();
    let header = [
        ((len >> 24) & 0xFF) as u8,
        ((len >> 16) & 0xFF) as u8,
        ((len >> 8) & 0xFF) as u8,
        (len & 0xFF) as u8,
    ];
    file.write_all(&header)?;
    file.write_all(data)?;
    file.flush()?;
    Ok(())
}

fn read_data_file(path: &str) -> std::io::Result<Vec<u8>> {
    let mut file = File::open(path)?;
    let mut header = [0u8; HEADER_SIZE];
    file.read_exact(&mut header)?;
    let len = ((header[0] as usize) << 24)
        | ((header[1] as usize) << 16)
        | ((header[2] as usize) << 8)
        | (header[3] as usize);
    let mut data = vec![0u8; len];
    file.read_exact(&mut data)?;
    Ok(data)
}

fn cleanup_files(input: &str, output: &str) {
    let _ = std::fs::remove_file(input);
    let _ = std::fs::remove_file(output);
}
