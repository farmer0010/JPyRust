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

const INITIAL_SHM_SIZE: usize = 1024 * 1024;
const MAX_SHM_SIZE: usize = 256 * 1024 * 1024;
const INPUT_SHM_UNAVAILABLE: &str = "Input SHMEM read failed";

const BASE_EXCEPTION: &str = "com/jpyrust/JPyRustException";
const TASK_FAILED: &str = "com/jpyrust/TaskFailedException";
const WORKER_TIMEOUT: &str = "com/jpyrust/WorkerTimeoutException";
const WORKER_CRASHED: &str = "com/jpyrust/WorkerCrashedException";

#[cfg(target_os = "windows")]
type Shm = CustomShmem;
#[cfg(not(target_os = "windows"))]
type Shm = shared_memory::Shmem;

struct Failure {
    class: &'static str,
    message: String,
}

impl From<String> for Failure {
    fn from(message: String) -> Self {
        Failure { class: BASE_EXCEPTION, message }
    }
}

impl From<&str> for Failure {
    fn from(message: &str) -> Self {
        Failure::from(message.to_string())
    }
}

#[derive(Debug)]
enum DaemonError {
    Task(String),
    Timeout(String),
    Broken(String),
}

impl DaemonError {
    fn into_failure(self) -> Failure {
        match self {
            DaemonError::Task(message) => Failure { class: TASK_FAILED, message },
            DaemonError::Timeout(message) => Failure { class: WORKER_TIMEOUT, message },
            DaemonError::Broken(message) => Failure { class: WORKER_CRASHED, message },
        }
    }
}

struct PythonDaemon {
    child: Child,
    stdin: BufWriter<ChildStdin>,
    lines: Receiver<String>,
}

impl PythonDaemon {
    fn wait_for_line<F: Fn(&str) -> bool>(&self, timeout: Duration, accept: F) -> Result<String, DaemonError> {
        let deadline = Instant::now() + timeout;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            match self.lines.recv_timeout(remaining) {
                Ok(line) if accept(&line) => return Ok(line),
                Ok(_) => {}
                Err(RecvTimeoutError::Timeout) => {
                    return Err(DaemonError::Timeout(format!(
                        "Timed out after {}s waiting for Python daemon",
                        timeout.as_secs_f64()
                    )))
                }
                Err(RecvTimeoutError::Disconnected) => return Err(DaemonError::Broken("Python daemon closed".to_string())),
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

struct SharedBuffer {
    name: String,
    shm: Shm,
    capacity: usize,
}

#[derive(Default)]
struct Session {
    daemon: Option<PythonDaemon>,
    input: Option<SharedBuffer>,
    output: Option<SharedBuffer>,
    wanted_output: usize,
    generation: u32,
    shm_disabled: bool,
}

struct BridgeState {
    session: Mutex<Session>,
    work_dir: String,
    session_key: String,
    instance_id: String,
    java_vm: JavaVM,
    bridge_obj: jni::objects::GlobalRef,
    model_path: String,
    confidence: f32,
    whisper_model_path: String,
    startup_timeout: Duration,
    task_timeout: Duration,
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
        for candidate in ["python_dist/python.exe", "venv/Scripts/python.exe"] {
            let path = format!("{}/{}", self.work_dir, candidate);
            if std::path::Path::new(&path).exists() {
                return path;
            }
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

    fn spawn_python_daemon(&self) -> Result<PythonDaemon, Failure> {
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
            .wait_for_line(self.startup_timeout, |line| line == "READY")
            .map_err(|e| match e {
                DaemonError::Timeout(msg) => Failure { class: WORKER_TIMEOUT, message: format!("Python daemon did not become READY: {}", msg) },
                DaemonError::Task(msg) | DaemonError::Broken(msg) => Failure { class: WORKER_CRASHED, message: format!("Python daemon did not become READY: {}", msg) },
            })?;

        Ok(daemon)
    }

    fn lock_session(&self) -> MutexGuard<'_, Session> {
        self.session.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn ensure_daemon(&self, session: &mut Session) -> Result<(), Failure> {
        if let Some(daemon) = session.daemon.as_mut() {
            if let Ok(None) = daemon.child.try_wait() {
                return Ok(());
            }
        }
        session.daemon = Some(self.spawn_python_daemon()?);
        Ok(())
    }

    fn start_daemon(&self) -> Result<(), Failure> {
        let mut session = self.lock_session();
        self.ensure_daemon(&mut session)
    }

    fn send(&self, session: &mut Session, task_type: &str, request_id: &str, metadata: &str) -> Result<String, DaemonError> {
        self.ensure_daemon(session).map_err(|f| DaemonError::Broken(f.message))?;
        let result = Self::send_execute_command(session.daemon.as_mut().unwrap(), task_type, request_id, metadata, self.task_timeout);
        if matches!(result, Err(DaemonError::Broken(_)) | Err(DaemonError::Timeout(_))) {
            session.daemon = None;
        }
        result
    }

    fn send_execute_command(
        daemon: &mut PythonDaemon,
        task_type: &str,
        request_id: &str,
        metadata: &str,
        timeout: Duration,
    ) -> Result<String, DaemonError> {
        let command = format!("EXECUTE {} {} {}\n", task_type, request_id, metadata);
        daemon.stdin.write_all(command.as_bytes()).map_err(|e| DaemonError::Broken(e.to_string()))?;
        daemon.stdin.flush().map_err(|e| DaemonError::Broken(e.to_string()))?;

        let response = daemon
            .wait_for_line(timeout, |line| line.starts_with("DONE") || line.starts_with("ERROR"))
            .map_err(|e| match e {
                DaemonError::Timeout(msg) => DaemonError::Timeout(format!("{} (task {}, restarting daemon)", msg, task_type)),
                DaemonError::Broken(msg) => DaemonError::Broken(format!("{} (task {}, restarting daemon)", msg, task_type)),
                other => other,
            })?;

        if response.starts_with("ERROR") {
            return Err(DaemonError::Task(response));
        }
        Ok(response)
    }

    fn execute_task(&self, task_type: &str, request_id: &str, metadata: &str, data: &[u8]) -> Result<Vec<u8>, Failure> {
        let mut session = self.lock_session();
        if !session.shm_disabled {
            match self.execute_shm(&mut session, task_type, request_id, metadata, data) {
                Ok(output) => return Ok(output),
                Err(ShmFailure::Unavailable(reason)) => {
                    self.log_to_java("WARN", &format!("[IPC] Shared memory unavailable ({}), using files", reason));
                    session.shm_disabled = true;
                }
                Err(ShmFailure::Daemon(e)) => return Err(e.into_failure()),
                Err(ShmFailure::Other(msg)) => return Err(msg.into()),
            }
        }
        self.execute_file(&mut session, task_type, request_id, metadata, data)
    }

    fn reserve(&self, session: &mut Session, input: bool, needed: usize) -> Result<(), String> {
        let current = if input { &session.input } else { &session.output };
        if current.as_ref().is_some_and(|b| b.capacity >= needed) {
            return Ok(());
        }
        let capacity = needed.max(INITIAL_SHM_SIZE).next_power_of_two();
        session.generation += 1;
        let name = format!("{}_{}{}", self.session_key, if input { "i" } else { "o" }, session.generation);
        let shm = create_shmem_permissive(&name, capacity)?;
        let buffer = Some(SharedBuffer { name, shm, capacity });
        if input {
            session.input = buffer;
        } else {
            session.output = buffer;
        }
        Ok(())
    }

    fn execute_shm(
        &self,
        session: &mut Session,
        task_type: &str,
        request_id: &str,
        metadata: &str,
        data: &[u8],
    ) -> Result<Vec<u8>, ShmFailure> {
        if data.len() > MAX_SHM_SIZE {
            return Err(ShmFailure::Unavailable(format!("input of {} bytes exceeds {} bytes", data.len(), MAX_SHM_SIZE)));
        }
        self.reserve(session, true, data.len().max(1)).map_err(ShmFailure::Unavailable)?;
        let wanted = session.wanted_output.max(INITIAL_SHM_SIZE);
        self.reserve(session, false, wanted).map_err(ShmFailure::Unavailable)?;

        let input = session.input.as_mut().unwrap();
        unsafe { input.shm.as_slice_mut()[..data.len()].copy_from_slice(data) };
        let output = session.output.as_ref().unwrap();
        let shm_metadata = format!(
            "SHMEM {} {} {} {} {}",
            input.name,
            data.len(),
            output.name,
            output.capacity,
            metadata
        );
        let output_capacity = output.capacity;

        let response = match self.send(session, task_type, request_id, &shm_metadata) {
            Ok(response) => response,
            Err(DaemonError::Task(msg)) if msg.contains(INPUT_SHM_UNAVAILABLE) => return Err(ShmFailure::Unavailable(msg)),
            Err(e) => return Err(ShmFailure::Daemon(e)),
        };

        let (length, via_file) = parse_done(&response).ok_or_else(|| {
            ShmFailure::Other(format!("Malformed response from Python daemon: {}", response))
        })?;
        if via_file {
            if length > output_capacity && length <= MAX_SHM_SIZE {
                session.wanted_output = length;
            }
            let output_file = format!("{}/output_{}.dat", self.work_dir, request_id);
            let result = read_data_file(&output_file)
                .map_err(|e| ShmFailure::Other(format!("Failed to read output file {}: {}", output_file, e)));
            let _ = std::fs::remove_file(&output_file);
            return result;
        }
        if length > output_capacity {
            return Err(ShmFailure::Other(format!(
                "Output size {} bytes exceeds shared memory capacity {} bytes",
                length, output_capacity
            )));
        }
        Ok(unsafe { session.output.as_ref().unwrap().shm.as_slice()[..length].to_vec() })
    }

    fn execute_file(
        &self,
        session: &mut Session,
        task_type: &str,
        request_id: &str,
        metadata: &str,
        data: &[u8],
    ) -> Result<Vec<u8>, Failure> {
        let input_file = format!("{}/input_{}.dat", self.work_dir, request_id);
        let output_file = format!("{}/output_{}.dat", self.work_dir, request_id);

        let result = write_data_file(&input_file, data)
            .map_err(|e| Failure::from(format!("Failed to write input file {}: {}", input_file, e)))
            .and_then(|_| self.send(session, task_type, request_id, metadata).map_err(DaemonError::into_failure))
            .and_then(|_| {
                read_data_file(&output_file)
                    .map_err(|e| Failure::from(format!("Failed to read output file {}: {}", output_file, e)))
            });

        cleanup_files(&input_file, &output_file);
        result
    }
}

enum ShmFailure {
    Unavailable(String),
    Daemon(DaemonError),
    Other(String),
}

fn parse_done(response: &str) -> Option<(usize, bool)> {
    let mut parts = response.strip_prefix("DONE ")?.split_whitespace();
    let length = parts.next()?.parse().ok()?;
    Some((length, parts.next() == Some("FILE")))
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
    F: FnOnce(&mut JNIEnv<'local>) -> Result<T, Failure>,
{
    let failure = match catch_unwind(AssertUnwindSafe(|| f(env))) {
        Ok(Ok(value)) => return value,
        Ok(Err(failure)) => failure,
        Err(payload) => Failure::from(panic_message(payload)),
    };
    if !env.exception_check().unwrap_or(false) && env.throw_new(failure.class, &failure.message).is_err() {
        let _ = env.exception_clear();
        let _ = env.throw_new("java/lang/RuntimeException", failure.message);
    }
    default
}

fn get_state<'a>(env: &mut JNIEnv, obj: &JObject) -> Result<&'a BridgeState, Failure> {
    let ptr = env.get_field(obj, "nativePtr", "J").map_err(jerr)?.j().map_err(jerr)?;
    if ptr == 0 {
        return Err("JPyRustBridge is not initialized or already closed".into());
    }
    Ok(unsafe { &*(ptr as *const BridgeState) })
}

fn get_rust_string(env: &mut JNIEnv, s: &JString) -> Result<String, Failure> {
    Ok(env.get_string(s).map_err(jerr)?.into())
}

fn millis(value: jlong) -> Duration {
    Duration::from_millis(value.max(1) as u64)
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_jpyrust_JPyRustBridge_initNative<'local>(
    mut env: JNIEnv<'local>,
    obj: JObject<'local>,
    work_dir: JString<'local>,
    _source_script_dir: JString<'local>,
    model_path: JString<'local>,
    confidence: jni::sys::jfloat,
    memory_key: JString<'local>,
    whisper_model: JString<'local>,
    startup_timeout_ms: jlong,
    task_timeout_ms: jlong,
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
            session: Mutex::new(Session::default()),
            work_dir: work_dir_str,
            session_key: memory_key_str,
            instance_id: instance_id_str,
            java_vm: vm,
            bridge_obj: global_obj,
            model_path: model_path_str,
            confidence,
            whisper_model_path: whisper_model_str,
            startup_timeout: millis(startup_timeout_ms),
            task_timeout: millis(task_timeout_ms),
        });

        if let Err(e) = state.start_daemon() {
            return Err(Failure { class: e.class, message: format!("Daemon start failed: {}", e.message) });
        }

        let state_ptr = Box::into_raw(state) as jlong;
        if let Err(e) = env.set_field(&obj, "nativePtr", "J", jni::objects::JValue::Long(state_ptr)) {
            drop(unsafe { Box::from_raw(state_ptr as *mut BridgeState) });
            return Err(jerr(e).into());
        }
        Ok(())
    })
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
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
            return Err(format!("Invalid input length {} for buffer capacity {}", input_length, capacity).into());
        }
        let length = input_length as usize;
        let data = if length == 0 {
            &[][..]
        } else {
            let buffer_ptr = env.get_direct_buffer_address(&input_data).map_err(jerr)?;
            unsafe { std::slice::from_raw_parts(buffer_ptr, length) }
        };

        let output = state.execute_task(&task_type_str, &request_id_str, &metadata_str, data)?;
        let array = env.byte_array_from_slice(&output).map_err(jerr)?;
        Ok(array.into_raw())
    })
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
        match daemon.wait_for_line(Duration::from_millis(200), |_| true) {
            Err(DaemonError::Timeout(msg)) => assert!(msg.contains("Timed out")),
            other => panic!("expected timeout, got {:?}", other),
        }
        assert!(started.elapsed() < Duration::from_secs(2));
    }

    #[cfg(unix)]
    #[test]
    fn wait_for_line_reports_closed_daemon() {
        let daemon = spawn_fake_daemon("exit 0");
        match daemon.wait_for_line(Duration::from_secs(5), |_| true) {
            Err(DaemonError::Broken(msg)) => assert_eq!(msg, "Python daemon closed"),
            other => panic!("expected closed daemon, got {:?}", other),
        }
    }

    #[cfg(unix)]
    #[test]
    fn python_error_is_a_task_error() {
        let mut daemon = spawn_fake_daemon("read _; echo 'ERROR boom'; sleep 5");
        match BridgeState::send_execute_command(&mut daemon, "YOLO", "req", "meta", Duration::from_secs(5)) {
            Err(DaemonError::Task(msg)) => assert_eq!(msg, "ERROR boom"),
            _ => panic!("expected task error"),
        }
    }

    #[cfg(unix)]
    #[test]
    fn closed_daemon_is_a_broken_error() {
        let mut daemon = spawn_fake_daemon("read _; exit 0");
        match BridgeState::send_execute_command(&mut daemon, "YOLO", "req", "meta", Duration::from_secs(5)) {
            Err(DaemonError::Broken(msg)) => assert!(msg.contains("Python daemon closed")),
            _ => panic!("expected broken daemon"),
        }
    }

    #[test]
    fn done_response_reports_length_and_file_fallback() {
        assert_eq!(parse_done("DONE 42"), Some((42, false)));
        assert_eq!(parse_done("DONE 2000000 FILE"), Some((2000000, true)));
        assert_eq!(parse_done("DONE x"), None);
        assert_eq!(parse_done("ERROR boom"), None);
    }

    #[cfg(unix)]
    #[test]
    fn silent_task_is_a_timeout_error() {
        let mut daemon = spawn_fake_daemon("read _; sleep 5");
        match BridgeState::send_execute_command(&mut daemon, "YOLO", "req", "meta", Duration::from_millis(200)) {
            Err(DaemonError::Timeout(msg)) => assert!(msg.contains("restarting daemon")),
            other => panic!("expected timeout, got {:?}", other),
        }
    }

    #[cfg(unix)]
    #[test]
    fn done_response_is_returned() {
        let mut daemon = spawn_fake_daemon("read _; echo 'progress'; echo 'DONE 42'; sleep 5");
        match BridgeState::send_execute_command(&mut daemon, "YOLO", "req", "meta", Duration::from_secs(5)) {
            Ok(line) => assert_eq!(line, "DONE 42"),
            _ => panic!("expected DONE"),
        }
    }
}
