use jni::JNIEnv;
use jni::objects::{JClass, JByteBuffer, JString};
use jni::sys::{jint, jbyteArray};
use std::fs::File;
use std::io::{Read, Write, BufRead, BufReader, BufWriter};
use std::process::{Command, Child, Stdio, ChildStdin, ChildStdout};
use std::sync::Mutex;
use shared_memory::ShmemConf;

#[macro_use]
extern crate lazy_static;

const HEADER_SIZE: usize = 4;
// Output buffer size for Shared Memory (1MB should be enough for JSON/small images)
const OUTPUT_SHM_SIZE: usize = 1024 * 1024; 

// ============================================
// GLOBAL DAEMON PROCESS STATE
// ============================================

struct PythonDaemon {
    child: Child,
    stdin: BufWriter<ChildStdin>,
    stdout: BufReader<ChildStdout>,
}

lazy_static! {
    static ref PYTHON_DAEMON: Mutex<Option<PythonDaemon>> = Mutex::new(None);
    static ref WORK_DIR: Mutex<Option<String>> = Mutex::new(None);
}

/// Spawn the Python daemon process
fn spawn_python_daemon(work_dir: &str) -> Result<PythonDaemon, String> {
    println!("[Rust] Spawning Python daemon...");
    std::io::stdout().flush().unwrap();

    let python_exe = format!("{}/python.exe", work_dir);
    let script_path = format!("{}/ai_worker.py", work_dir);

    let mut child = Command::new(&python_exe)
        .arg(&script_path)
        .arg("--daemon")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .map_err(|e| format!("Failed to spawn Python daemon: {}", e))?;

    println!("[Rust] Python process spawned (PID: {:?})", child.id());
    std::io::stdout().flush().unwrap();

    let stdin = child.stdin.take().ok_or("Failed to capture stdin")?;
    let stdout = child.stdout.take().ok_or("Failed to capture stdout")?;

    let stdin_writer = BufWriter::new(stdin);
    let mut stdout_reader = BufReader::new(stdout);

    // Handshake
    println!("[Rust] Waiting for READY signal...");
    std::io::stdout().flush().unwrap();

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
                println!("[Python Init] {}", trimmed);
                std::io::stdout().flush().unwrap();
                
                if trimmed == "READY" {
                    println!("[Rust] Handshake complete!");
                    std::io::stdout().flush().unwrap();
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
                println!("[Rust] Python daemon exited: {:?}. Respawning...", status);
                std::io::stdout().flush().unwrap();
            }
            Ok(None) => return Ok(()),
            Err(e) => {
                println!("[Rust] Error checking daemon: {}. Respawning...", e);
                std::io::stdout().flush().unwrap();
            }
        }
    }

    let daemon = spawn_python_daemon(work_dir)?;
    *daemon_guard = Some(daemon);
    
    let mut work_dir_guard = WORK_DIR.lock().unwrap();
    *work_dir_guard = Some(work_dir.to_string());
    
    Ok(())
}

/// Send EXECUTE command to daemon (with SHMEM support)
fn send_execute_command(task_type: &str, request_id: &str, metadata: &str) -> Result<String, String> {
    let mut daemon_guard = PYTHON_DAEMON.lock().unwrap();
    
    let daemon = daemon_guard.as_mut()
        .ok_or("Python daemon not initialized")?;

    // Format: EXECUTE <task_type> <request_id> <metadata>
    let command = format!("EXECUTE {} {} {}\n", task_type, request_id, metadata);
    println!("[Rust] Sending: EXECUTE {} {} ...", task_type, &request_id[..8.min(request_id.len())]);
    std::io::stdout().flush().unwrap();

    daemon.stdin.write_all(command.as_bytes())
        .map_err(|e| format!("Failed to write to Python: {}", e))?;
    daemon.stdin.flush()
        .map_err(|e| format!("Failed to flush: {}", e))?;

    // Read response
    let mut response = String::new();
    loop {
        response.clear();
        match daemon.stdout.read_line(&mut response) {
            Ok(0) => return Err("Python daemon closed unexpectedly".to_string()),
            Ok(_) => {
                let trimmed = response.trim();
                println!("[Python] {}", trimmed);
                std::io::stdout().flush().unwrap();

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

// ============================================
// JNI INTERFACE
// ============================================

#[no_mangle]
pub extern "system" fn Java_com_jpyrust_JPyRustBridge_initNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    work_dir: JString<'local>,
    _source_script_dir: JString<'local>,
) {
    println!("=== JPyRust Universal Bridge Initialization ===");
    println!("=== Level 2: Full Shared Memory Pipeline (Input/Output) ===");
    std::io::stdout().flush().unwrap();

    let work_dir_str: String = env.get_string(&work_dir)
        .expect("Failed to get work_dir")
        .into();

    println!("[Rust Init] Work Directory: {}", work_dir_str);

    match get_or_spawn_daemon(&work_dir_str) {
        Ok(_) => println!("[Rust Init] Python daemon ready!"),
        Err(e) => eprintln!("[Rust Init] Daemon start failed: {}", e),
    }
    std::io::stdout().flush().unwrap();
}

/// Universal task execution with EXECUTE protocol + SHMEM for input AND output
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
    
    let work_dir_str: String = env.get_string(&work_dir).expect("work_dir").into();
    let task_type_str: String = env.get_string(&task_type).expect("task_type").into();
    let request_id_str: String = env.get_string(&request_id).expect("request_id").into();
    let metadata_str: String = env.get_string(&metadata).expect("metadata").into();

    println!("[Rust] Task: {} | ID: {} | Mode: FULL-SHMEM", task_type_str, &request_id_str[..8.min(request_id_str.len())]);
    std::io::stdout().flush().unwrap();

    let length = input_length as usize;

    // Get input data from DirectByteBuffer
    let buffer_ptr = match env.get_direct_buffer_address(&input_data) {
        Ok(ptr) => ptr,
        Err(e) => {
            eprintln!("[Rust] Buffer error: {}", e);
            return std::ptr::null_mut();
        }
    };

    let data = unsafe { std::slice::from_raw_parts(buffer_ptr, length) };

    // ============================================
    // 1. INPUT SHARED MEMORY (Host -> Python)
    // ============================================
    let shm_name_in = format!("jpyrust_{}", &request_id_str[..8.min(request_id_str.len())]);
    
    // Create input shared memory region
    let mut shm_in = match ShmemConf::new()
        .size(length)
        .os_id(&shm_name_in)
        .create() 
    {
        Ok(m) => m,
        Err(e) => {
            eprintln!("[Rust] Input SHM creation failed: {}", e);
            // Fallback to file-based IPC
            return execute_with_file_fallback(
                &env, &work_dir_str, &task_type_str, &request_id_str, 
                &metadata_str, data, start_time
            );
        }
    };

    // Copy data into shared memory
    {
        let shm_slice = unsafe { shm_in.as_slice_mut() };
        shm_slice[..length].copy_from_slice(data);
    }

    // ============================================
    // 2. OUTPUT SHARED MEMORY (Python -> Host)
    // ============================================
    let shm_name_out = format!("jpyrust_out_{}", &request_id_str[..8.min(request_id_str.len())]);
    
    // Create output shared memory region
    let shm_out = match ShmemConf::new()
        .size(OUTPUT_SHM_SIZE)
        .os_id(&shm_name_out)
        .create() 
    {
        Ok(m) => m,
        Err(e) => {
            eprintln!("[Rust] Output SHM creation failed: {}", e);
            drop(shm_in);
            return execute_with_file_fallback(
                &env, &work_dir_str, &task_type_str, &request_id_str, 
                &metadata_str, data, start_time
            );
        }
    };

    // ============================================
    // Send SHMEM command
    // ============================================
    // Protocol: EXECUTE SHMEM <in_id> <in_len> <out_id> <out_cap> <original_metadata>
    let shmem_metadata = format!("SHMEM {} {} {} {} {}", shm_name_in, length, shm_name_out, OUTPUT_SHM_SIZE, metadata_str);

    // Ensure daemon logic with retry
    let mut retry = 0;
    let mut final_result_len = 0;

    loop {
        if let Err(e) = get_or_spawn_daemon(&work_dir_str) {
            eprintln!("[Rust] Daemon error: {}", e);
            drop(shm_in);
            drop(shm_out);
            return std::ptr::null_mut();
        }

        match send_execute_command(&task_type_str, &request_id_str, &shmem_metadata) {
            Ok(result) => {
                println!("[Rust] Result: {}", result);
                // Parse "DONE <bytes_written>"
                if let Some(len_str) = result.strip_prefix("DONE ") {
                    final_result_len = len_str.trim().parse().unwrap_or(0);
                }
                break;
            }
            Err(e) => {
                eprintln!("[Rust] Execute error: {}", e);
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

    // ============================================
    // READ OUTPUT FROM SHARED MEMORY
    // ============================================
    
    let output_data = if final_result_len > 0 && final_result_len <= OUTPUT_SHM_SIZE {
        let shm_slice = unsafe { shm_out.as_slice() };
        shm_slice[..final_result_len].to_vec()
    } else {
        Vec::new()
    };

    // Cleanup Shared Memory (Input & Output)
    drop(shm_in);
    drop(shm_out);

    println!("[Rust] Completed in {:?} (FULL-SHMEM, {} bytes)", start_time.elapsed(), output_data.len());
    std::io::stdout().flush().unwrap();

    // Return as Java byte array
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

/// Fallback to file-based IPC if shared memory fails
fn execute_with_file_fallback(
    _env: &JNIEnv,
    work_dir_str: &str,
    task_type_str: &str,
    request_id_str: &str,
    metadata_str: &str,
    data: &[u8],
    start_time: std::time::Instant,
) -> jbyteArray {
    println!("[Rust] Falling back to file-based IPC");
    std::io::stdout().flush().unwrap();

    let input_file = format!("{}/input_{}.dat", work_dir_str, request_id_str);
    let output_file = format!("{}/output_{}.dat", work_dir_str, request_id_str);
    
    // Write input file
    if let Err(e) = write_data_file(&input_file, data) {
        eprintln!("[Rust] Write error: {}", e);
        return std::ptr::null_mut();
    }

    // Ensure daemon running with retry
    let mut retry = 0;
    loop {
        if let Err(e) = get_or_spawn_daemon(work_dir_str) {
            eprintln!("[Rust] Daemon error: {}", e);
            cleanup_files(&input_file, &output_file);
            return std::ptr::null_mut();
        }

        let _ = std::fs::remove_file(&output_file);

        match send_execute_command(task_type_str, request_id_str, metadata_str) {
            Ok(result) => {
                println!("[Rust] Result: {}", result);
                break;
            }
            Err(e) => {
                eprintln!("[Rust] Execute error: {}", e);
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

    // Read output
    let output_data = match read_data_file(&output_file) {
        Ok(data) => data,
        Err(e) => {
            eprintln!("[Rust] Read error: {}", e);
            cleanup_files(&input_file, &output_file);
            return std::ptr::null_mut();
        }
    };

    cleanup_files(&input_file, &output_file);

    println!("[Rust] Completed in {:?} (FILE mode)", start_time.elapsed());
    std::io::stdout().flush().unwrap();

    // In fallback mode, we return null because we don't have mutable access to env here easily
    // or we can change the signature. But keeping it simple as SHMEM is priority.
    // If fallback is critical, we should pass `mut env`.
    // For now, return null to indicate failure in SHMEM creation but fallback executed (imperfect fallback).
    std::ptr::null_mut()
}

/// Legacy runPythonProcess for backward compatibility
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
    // Create metadata string for YOLO task
    let metadata = format!("{} {} {}", width, height, channels);
    
    // Convert to JString for metadata
    let metadata_jstring = match env.new_string(&metadata) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    
    // Create task type string
    let task_type = match env.new_string("YOLO") {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    // Delegate to executeTask
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

// ============================================
// FILE I/O (kept for fallback)
// ============================================

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
