use jni::JNIEnv;
use jni::JavaVM;
use jni::objects::{JByteBuffer, JString, JObject};
use jni::sys::{jint, jbyteArray, jlong};
use std::fs::File;
use std::io::{Read, Write, BufRead, BufReader, BufWriter};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::process::{Command, Child, Stdio, ChildStdin};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError};
use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, Instant};


const OUTPUT_SHM_SIZE: usize = 1024 * 1024;
const READY_TIMEOUT: Duration = Duration::from_secs(600);
const TASK_TIMEOUT: Duration = Duration::from_secs(300);

struct PythonDaemon {
    child: Child,
    stdin: BufWriter<ChildStdin>,
    lines: Receiver<String>,
}

impl PythonDaemon {
    fn wait_for_line<F: Fn(&str) -> bool>(&self, timeout: Duration, accept: F) -> Result<String, String> {
        let deadline = Instant::now() + timeout;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            match self.lines.recv_timeout(remaining) {
                Ok(line) if accept(&line) => return Ok(line),
                Ok(_) => {}
                Err(RecvTimeoutError::Timeout) => {
                    return Err(format!("Timed out after {}s waiting for Python daemon", timeout.as_secs()))
                }
                Err(RecvTimeoutError::Disconnected) => return Err("Python daemon closed".to_string()),
            }
        }
    }
}

impl Drop for PythonDaemon {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

struct BridgeState {
    daemon: Mutex<Option<PythonDaemon>>,
    work_dir: String,
    session_key: String,
    instance_id: String,
    java_vm: JavaVM,
    bridge_obj: jni::objects::GlobalRef,
    model_path: String,
    confidence: f32,
    whisper_model_path: String,
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

        if status.is_err() {
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_describe();
                let _ = env.exception_clear();
            }
            eprintln!("[Rust-Fallback] (Java Threw) [{}] {}", level, msg);
        }
    }

    #[cfg(target_os = "windows")]
    fn find_python_executable(&self) -> String {
        let embedded_path = format!("{}/python_dist/python.exe", self.work_dir);
        if std::path::Path::new(&embedded_path).exists() {
            return embedded_path;
        }
        self.log_to_java("WARN", "[Rust] Embedded Python not found. Falling back to 'python'.");
        "python".to_string()
    }

    #[cfg(not(target_os = "windows"))]
    fn find_python_executable(&self) -> String {
        let venv_path = format!("{}/venv/bin/python3", self.work_dir);
        if std::path::Path::new(&venv_path).exists() {
            return venv_path;
        }

        for cmd in ["python3", "python"] {
            if let Ok(output) = Command::new("which").arg(cmd).output() {
                if output.status.success() {
                    return cmd.to_string();
                }
            }
        }

        self.log_to_java("WARN", "[Rust] venv Python not found. Falling back to 'python3'.");
        "python3".to_string()
    }

    fn spawn_python_daemon(&self) -> Result<PythonDaemon, String> {
        let python_exe = self.find_python_executable();
        let script_path = format!("{}/ai_worker.py", self.work_dir);

        let mut child_cmd = Command::new(&python_exe);
        child_cmd.arg(&script_path).arg("--daemon");
        child_cmd.arg("--mem-key").arg(&self.session_key);
        child_cmd.arg("--instance-id").arg(&self.instance_id);
        // 버그 수정: 이전에는 initNative가 받은 model_path/confidence가 BridgeState에
        // 저장조차 되지 않아 여기서 Python에 전달되지 않았다(항상 argparse 기본값만 사용됨).
        child_cmd.arg("--model").arg(&self.model_path);
        child_cmd.arg("--conf").arg(self.confidence.to_string());
        if !self.whisper_model_path.is_empty() {
            child_cmd.arg("--whisper-model").arg(&self.whisper_model_path);
        }
        child_cmd.arg("--work-dir").arg(&self.work_dir);
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

        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            let mut reader = BufReader::new(stdout);
            let mut line = String::new();
            loop {
                line.clear();
                match reader.read_line(&mut line) {
                    Ok(0) | Err(_) => break,
                    Ok(_) => {
                        if tx.send(line.trim().to_string()).is_err() {
                            break;
                        }
                    }
                }
            }
        });

        let daemon = PythonDaemon {
            child,
            stdin: BufWriter::new(stdin),
            lines: rx,
        };

        daemon
            .wait_for_line(READY_TIMEOUT, |line| line == "READY")
            .map_err(|e| format!("Python daemon did not become READY: {}", e))?;

        Ok(daemon)
    }

    fn lock_daemon(&self) -> MutexGuard<'_, Option<PythonDaemon>> {
        self.daemon.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn ensure_daemon(&self, guard: &mut Option<PythonDaemon>) -> Result<(), String> {
        if let Some(daemon) = guard.as_mut() {
            if let Ok(None) = daemon.child.try_wait() {
                return Ok(());
            }
        }
        *guard = Some(self.spawn_python_daemon()?);
        Ok(())
    }

    fn start_daemon(&self) -> Result<(), String> {
        let mut guard = self.lock_daemon();
        self.ensure_daemon(&mut guard)
    }

    fn execute(&self, task_type: &str, request_id: &str, metadata: &str) -> Result<String, String> {
        let mut guard = self.lock_daemon();
        self.ensure_daemon(&mut guard)?;

        let result = Self::send_execute_command(guard.as_mut().unwrap(), task_type, request_id, metadata);
        if let Err(DaemonError::Broken(_)) = &result {
            *guard = None;
        }
        result.map_err(|e| match e {
            DaemonError::Task(msg) | DaemonError::Broken(msg) => msg,
        })
    }

    fn send_execute_command(
        daemon: &mut PythonDaemon,
        task_type: &str,
        request_id: &str,
        metadata: &str,
    ) -> Result<String, DaemonError> {
        let command = format!("EXECUTE {} {} {}\n", task_type, request_id, metadata);
        daemon.stdin.write_all(command.as_bytes()).map_err(|e| DaemonError::Broken(e.to_string()))?;
        daemon.stdin.flush().map_err(|e| DaemonError::Broken(e.to_string()))?;

        let response = daemon
            .wait_for_line(TASK_TIMEOUT, |line| line.starts_with("DONE") || line.starts_with("ERROR"))
            .map_err(|e| DaemonError::Broken(format!("{} (task {}, restarting daemon)", e, task_type)))?;

        if response.starts_with("ERROR") {
            return Err(DaemonError::Task(response));
        }
        Ok(response)
    }
}

enum DaemonError {
    Task(String),
    Broken(String),
}

fn jerr(e: jni::errors::Error) -> String {
    e.to_string()
}

fn panic_message(payload: Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        format!("Native panic: {}", s)
    } else if let Some(s) = payload.downcast_ref::<String>() {
        format!("Native panic: {}", s)
    } else {
        "Native panic".to_string()
    }
}

fn run_jni<'local, T, F>(env: &mut JNIEnv<'local>, default: T, f: F) -> T
where
    F: FnOnce(&mut JNIEnv<'local>) -> Result<T, String>,
{
    let message = match catch_unwind(AssertUnwindSafe(|| f(env))) {
        Ok(Ok(value)) => return value,
        Ok(Err(msg)) => msg,
        Err(payload) => panic_message(payload),
    };
    if !env.exception_check().unwrap_or(false) {
        let _ = env.throw_new("java/lang/RuntimeException", message);
    }
    default
}

fn get_state<'a>(env: &mut JNIEnv, obj: &JObject) -> Result<&'a BridgeState, String> {
    let ptr = env.get_field(obj, "nativePtr", "J").map_err(jerr)?.j().map_err(jerr)?;
    if ptr == 0 {
        return Err("JPyRustBridge is not initialized or already closed".to_string());
    }
    Ok(unsafe { &*(ptr as *const BridgeState) })
}

fn get_rust_string(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    Ok(env.get_string(s).map_err(jerr)?.into())
}

#[no_mangle]
pub extern "system" fn Java_com_jpyrust_JPyRustBridge_initNative<'local>(
    mut env: JNIEnv<'local>,
    obj: JObject<'local>,
    work_dir: JString<'local>,
    _source_script_dir: JString<'local>,
    model_path: JString<'local>,
    confidence: jni::sys::jfloat,
    memory_key: JString<'local>,
    whisper_model: JString<'local>,
) {
    run_jni(&mut env, (), |env| {
        let work_dir_str = get_rust_string(env, &work_dir)?;
        let model_path_str = get_rust_string(env, &model_path)?;
        let memory_key_str = get_rust_string(env, &memory_key)?;
        let whisper_model_str = get_rust_string(env, &whisper_model)?;
        let instance_id: JString = env
            .get_field(&obj, "instanceId", "Ljava/lang/String;")
            .map_err(jerr)?
            .l()
            .map_err(jerr)?
            .into();
        let instance_id_str = get_rust_string(env, &instance_id)?;

        let vm = env.get_java_vm().map_err(jerr)?;
        let global_obj = env.new_global_ref(&obj).map_err(jerr)?;

        let state = Box::new(BridgeState {
            daemon: Mutex::new(None),
            work_dir: work_dir_str,
            session_key: memory_key_str,
            instance_id: instance_id_str,
            java_vm: vm,
            bridge_obj: global_obj,
            model_path: model_path_str,
            confidence,
            whisper_model_path: whisper_model_str,
        });

        if let Err(e) = state.start_daemon() {
            return Err(format!("Daemon start failed: {}", e));
        }

        let state_ptr = Box::into_raw(state) as jlong;
        if let Err(e) = env.set_field(&obj, "nativePtr", "J", jni::objects::JValue::Long(state_ptr)) {
            drop(unsafe { Box::from_raw(state_ptr as *mut BridgeState) });
            return Err(jerr(e));
        }
        Ok(())
    })
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
    run_jni(&mut env, std::ptr::null_mut(), |env| {
        let state = get_state(env, &obj)?;

        let task_type_str = get_rust_string(env, &task_type)?;
        let request_id_str = get_rust_string(env, &request_id)?;
        let metadata_str = get_rust_string(env, &metadata)?;

        let capacity = env
            .get_direct_buffer_capacity(&input_data)
            .map_err(|e| format!("Input must be a direct ByteBuffer ({})", e))?;
        if input_length < 0 || input_length as usize > capacity {
            return Err(format!("Invalid input length {} for buffer capacity {}", input_length, capacity));
        }
        let length = input_length as usize;
        let buffer_ptr = env.get_direct_buffer_address(&input_data).map_err(jerr)?;
        let data = unsafe { std::slice::from_raw_parts(buffer_ptr, length) };

        let output = execute_task(state, &task_type_str, &request_id_str, &metadata_str, data)?;
        let array = env.byte_array_from_slice(&output).map_err(jerr)?;
        Ok(array.into_raw())
    })
}

fn execute_task(
    state: &BridgeState,
    task_type: &str,
    request_id: &str,
    metadata: &str,
    data: &[u8],
) -> Result<Vec<u8>, String> {
    if !["YOLO", "EDGE_DETECT"].contains(&task_type) {
        return execute_with_file_fallback(state, task_type, request_id, metadata, data);
    }

    let short_id = &request_id[..6.min(request_id.len())];
    let length = data.len();

    let shm_name_in = format!("{}_{}", state.session_key, short_id);
    let mut shm_in = match create_shmem_permissive(&shm_name_in, length) {
        Ok(m) => m,
        Err(e) => {
            eprintln!("[JPyRust-Native] SHMEM-IN creation failed: {}", e);
            return execute_with_file_fallback(state, task_type, request_id, metadata, data);
        }
    };

    unsafe { shm_in.as_slice_mut()[..length].copy_from_slice(data); }

    let shm_name_out = format!("{}_out_{}", state.session_key, short_id);
    let shm_out = match create_shmem_permissive(&shm_name_out, OUTPUT_SHM_SIZE) {
        Ok(m) => m,
        Err(e) => {
            eprintln!("[JPyRust-Native] SHMEM-OUT creation failed: {}", e);
            return execute_with_file_fallback(state, task_type, request_id, metadata, data);
        }
    };

    eprintln!("[JPyRust-Native] [IPC] Mode: SHMEM | Task: {} | ReqID: {}", task_type, request_id);
    state.log_to_java("INFO", &format!("[IPC] Mode: SHMEM | Task: {} | ReqID: {}", task_type, short_id));
    let shmem_metadata = format!("SHMEM {} {} {} {} {}", shm_name_in, length, shm_name_out, OUTPUT_SHM_SIZE, metadata);

    let response = state.execute(task_type, request_id, &shmem_metadata)?;
    let result_len: usize = response
        .strip_prefix("DONE ")
        .and_then(|len| len.trim().parse().ok())
        .ok_or_else(|| format!("Malformed response from Python daemon: {}", response))?;

    if result_len > OUTPUT_SHM_SIZE {
        return Err(format!(
            "Output size {} bytes exceeds shared memory capacity {} bytes",
            result_len, OUTPUT_SHM_SIZE
        ));
    }

    Ok(unsafe { shm_out.as_slice()[..result_len].to_vec() })
}

fn execute_with_file_fallback(
    state: &BridgeState,
    task_type: &str,
    request_id: &str,
    metadata: &str,
    data: &[u8],
) -> Result<Vec<u8>, String> {
    eprintln!("[JPyRust-Native] [IPC] Mode: FILE-FALLBACK | Task: {} | ReqID: {}", task_type, request_id);
    state.log_to_java("WARN", &format!("[IPC] Mode: FILE-FALLBACK | Task: {} | ReqID: {}", task_type, &request_id[..8.min(request_id.len())]));
    let input_file = format!("{}/input_{}.dat", state.work_dir, request_id);
    let output_file = format!("{}/output_{}.dat", state.work_dir, request_id);

    let result = write_data_file(&input_file, data)
        .map_err(|e| format!("Failed to write input file {}: {}", input_file, e))
        .and_then(|_| state.execute(task_type, request_id, metadata))
        .and_then(|_| {
            read_data_file(&output_file).map_err(|e| format!("Failed to read output file {}: {}", output_file, e))
        });

    cleanup_files(&input_file, &output_file);
    result
}

#[no_mangle]
pub extern "system" fn Java_com_jpyrust_JPyRustBridge_closeNative<'local>(
    mut env: JNIEnv<'local>,
    obj: JObject<'local>,
) {
    run_jni(&mut env, (), |env| {
        let state_ptr_value = env.get_field(&obj, "nativePtr", "J").map_err(jerr)?.j().map_err(jerr)?;
        if state_ptr_value == 0 {
            return Ok(());
        }

        env.set_field(&obj, "nativePtr", "J", jni::objects::JValue::Long(0)).map_err(jerr)?;
        drop(unsafe { Box::from_raw(state_ptr_value as *mut BridgeState) });
        Ok(())
    })
}

#[cfg(target_os = "windows")]
struct CustomShmem {
    handle: isize,
    map_ptr: *mut u8,
    size: usize,
}

#[cfg(target_os = "windows")]
unsafe impl Send for CustomShmem {}
#[cfg(target_os = "windows")]
unsafe impl Sync for CustomShmem {}

#[cfg(target_os = "windows")]
impl CustomShmem {
    unsafe fn as_slice(&self) -> &[u8] {
        std::slice::from_raw_parts(self.map_ptr, self.size)
    }
    unsafe fn as_slice_mut(&mut self) -> &mut [u8] {
        std::slice::from_raw_parts_mut(self.map_ptr, self.size)
    }
}

#[cfg(target_os = "windows")]
impl Drop for CustomShmem {
    fn drop(&mut self) {
        use windows_sys::Win32::Foundation::CloseHandle;
        use windows_sys::Win32::System::Memory::{UnmapViewOfFile, MEMORY_MAPPED_VIEW_ADDRESS};
        unsafe {
            if !self.map_ptr.is_null() {
                let addr = MEMORY_MAPPED_VIEW_ADDRESS { Value: self.map_ptr as *mut std::ffi::c_void };
                UnmapViewOfFile(addr);
            }
            if self.handle != 0 {
                CloseHandle(self.handle);
            }
        }
    }
}

#[cfg(target_os = "windows")]
fn create_shmem_permissive(name: &str, size: usize) -> Result<CustomShmem, String> {
    use windows_sys::Win32::Foundation::{INVALID_HANDLE_VALUE, GetLastError, LocalFree};
    use windows_sys::Win32::Security::Authorization::ConvertStringSecurityDescriptorToSecurityDescriptorW;
    use windows_sys::Win32::Security::SECURITY_ATTRIBUTES;
    use windows_sys::Win32::System::Memory::{CreateFileMappingW, MapViewOfFile, PAGE_READWRITE, FILE_MAP_ALL_ACCESS};
    use std::ptr;
    use std::ffi::c_void;

    unsafe {
        let mut sd: *mut c_void = ptr::null_mut();
        let sddl = "D:P(A;;GA;;;OW)(A;;GA;;;SY)\0".encode_utf16().collect::<Vec<u16>>();
        if ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.as_ptr(), 1, &mut sd, ptr::null_mut()) == 0 {
            let err = GetLastError();
            eprintln!("[JPyRust-Native] SDDL conversion failed: error {}", err);
            return Err(format!("SDDL failed: {}", err));
        }
        let mut sa = SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: sd,
            bInheritHandle: 0,
        };
        let name_wide = name.encode_utf16().chain(std::iter::once(0)).collect::<Vec<u16>>();
        let h_map = CreateFileMappingW(INVALID_HANDLE_VALUE, &mut sa, PAGE_READWRITE, 0, size as u32, name_wide.as_ptr());
        LocalFree(sd);

        if h_map == 0 {
            let err = GetLastError();
            eprintln!("[JPyRust-Native] CreateFileMappingW failed: error {}", err);
            return Err(format!("Mapping failed: {}", err));
        }

        let view = MapViewOfFile(h_map, FILE_MAP_ALL_ACCESS, 0, 0, size);
        let map_ptr = view.Value as *mut u8;
        if map_ptr.is_null() {
            let err = GetLastError();
            use windows_sys::Win32::Foundation::CloseHandle;
            CloseHandle(h_map);
            eprintln!("[JPyRust-Native] MapViewOfFile failed: error {}", err);
            return Err(format!("MapViewOfFile failed: {}", err));
        }

        Ok(CustomShmem {
            handle: h_map,
            map_ptr,
            size,
        })
    }
}

#[cfg(not(target_os = "windows"))]
fn create_shmem_permissive(name: &str, size: usize) -> Result<shared_memory::Shmem, String> {
    let os_id = format!("/{}", name);
    shared_memory::ShmemConf::new().size(size).os_id(os_id).create().map_err(|e| e.to_string())
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

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_path(name: &str) -> String {
        std::env::temp_dir()
            .join(format!("jpyrust_test_{}_{}", std::process::id(), name))
            .to_string_lossy()
            .into_owned()
    }

    #[test]
    fn data_file_round_trip() {
        let path = temp_path("roundtrip.dat");
        let payload = vec![0u8, 1, 2, 255, 128, 64];
        write_data_file(&path, &payload).unwrap();
        assert_eq!(read_data_file(&path).unwrap(), payload);
        std::fs::remove_file(&path).unwrap();
    }

    #[test]
    fn data_file_header_is_big_endian_length() {
        let path = temp_path("header.dat");
        write_data_file(&path, &vec![7u8; 300]).unwrap();
        let raw = std::fs::read(&path).unwrap();
        assert_eq!(&raw[..4], &[0, 0, 1, 44]);
        std::fs::remove_file(&path).unwrap();
    }

    #[test]
    fn truncated_data_file_is_an_error() {
        let path = temp_path("truncated.dat");
        std::fs::write(&path, [0, 0, 0, 10, 1, 2]).unwrap();
        assert!(read_data_file(&path).is_err());
        std::fs::remove_file(&path).unwrap();
    }

    #[cfg(unix)]
    fn spawn_fake_daemon(script: &str) -> PythonDaemon {
        let mut child = Command::new("sh")
            .arg("-c")
            .arg(script)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        let stdin = child.stdin.take().unwrap();
        let stdout = child.stdout.take().unwrap();
        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            for line in BufReader::new(stdout).lines() {
                match line {
                    Ok(l) => {
                        if tx.send(l.trim().to_string()).is_err() {
                            break;
                        }
                    }
                    Err(_) => break,
                }
            }
        });
        PythonDaemon { child, stdin: BufWriter::new(stdin), lines: rx }
    }

    #[cfg(unix)]
    #[test]
    fn wait_for_line_skips_noise_until_match() {
        let daemon = spawn_fake_daemon("echo noise; echo READY; sleep 5");
        let line = daemon.wait_for_line(Duration::from_secs(5), |l| l == "READY").unwrap();
        assert_eq!(line, "READY");
    }

    #[cfg(unix)]
    #[test]
    fn wait_for_line_times_out_on_silent_daemon() {
        let daemon = spawn_fake_daemon("sleep 5");
        let started = Instant::now();
        let err = daemon.wait_for_line(Duration::from_millis(200), |_| true).unwrap_err();
        assert!(err.contains("Timed out"));
        assert!(started.elapsed() < Duration::from_secs(2));
    }

    #[cfg(unix)]
    #[test]
    fn wait_for_line_reports_closed_daemon() {
        let daemon = spawn_fake_daemon("exit 0");
        let err = daemon.wait_for_line(Duration::from_secs(5), |_| true).unwrap_err();
        assert_eq!(err, "Python daemon closed");
    }

    #[cfg(unix)]
    #[test]
    fn python_error_is_a_task_error() {
        let mut daemon = spawn_fake_daemon("read _; echo 'ERROR boom'; sleep 5");
        match BridgeState::send_execute_command(&mut daemon, "YOLO", "req", "meta") {
            Err(DaemonError::Task(msg)) => assert_eq!(msg, "ERROR boom"),
            _ => panic!("expected task error"),
        }
    }

    #[cfg(unix)]
    #[test]
    fn closed_daemon_is_a_broken_error() {
        let mut daemon = spawn_fake_daemon("read _; exit 0");
        match BridgeState::send_execute_command(&mut daemon, "YOLO", "req", "meta") {
            Err(DaemonError::Broken(msg)) => assert!(msg.contains("Python daemon closed")),
            _ => panic!("expected broken daemon"),
        }
    }

    #[cfg(unix)]
    #[test]
    fn done_response_is_returned() {
        let mut daemon = spawn_fake_daemon("read _; echo 'progress'; echo 'DONE 42'; sleep 5");
        match BridgeState::send_execute_command(&mut daemon, "YOLO", "req", "meta") {
            Ok(line) => assert_eq!(line, "DONE 42"),
            _ => panic!("expected DONE"),
        }
    }
}
