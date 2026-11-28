package com.jpyrust.spring;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jpyrust")
public class JPyRustProperties {

    private String instanceId = "default";
    private String workDir;
    private Path requirements;
    private List<Path> moduleDirs = new ArrayList<>();
    private List<String> extras = new ArrayList<>();
    private Path python;
    private String pythonVersion = "3.11";
    private String torch = "default";
    private List<String> devices = new ArrayList<>();
    private int workers = 1;
    private Duration startupTimeout = Duration.ofSeconds(600);
    private Duration taskTimeout = Duration.ofSeconds(300);
    private String model = "yolov8n.pt";
    private float confidence = 0.5f;
    private String whisperModel = "";

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getWorkDir() {
        return workDir;
    }

    public void setWorkDir(String workDir) {
        this.workDir = workDir;
    }

    public Path getRequirements() {
        return requirements;
    }

    public void setRequirements(Path requirements) {
        this.requirements = requirements;
    }

    public List<Path> getModuleDirs() {
        return moduleDirs;
    }

    public void setModuleDirs(List<Path> moduleDirs) {
        this.moduleDirs = moduleDirs;
    }

    public List<String> getExtras() {
        return extras;
    }

    public void setExtras(List<String> extras) {
        this.extras = extras;
    }

    public Path getPython() {
        return python;
    }

    public void setPython(Path python) {
        this.python = python;
    }

    public String getPythonVersion() {
        return pythonVersion;
    }

    public void setPythonVersion(String pythonVersion) {
        this.pythonVersion = pythonVersion;
    }

    public String getTorch() {
        return torch;
    }

    public void setTorch(String torch) {
        this.torch = torch;
    }

    public List<String> getDevices() {
        return devices;
    }

    public void setDevices(List<String> devices) {
        this.devices = devices;
    }

    public int getWorkers() {
        return workers;
    }

    public void setWorkers(int workers) {
        this.workers = workers;
    }

    public Duration getStartupTimeout() {
        return startupTimeout;
    }

    public void setStartupTimeout(Duration startupTimeout) {
        this.startupTimeout = startupTimeout;
    }

    public Duration getTaskTimeout() {
        return taskTimeout;
    }

    public void setTaskTimeout(Duration taskTimeout) {
        this.taskTimeout = taskTimeout;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public float getConfidence() {
        return confidence;
    }

    public void setConfidence(float confidence) {
        this.confidence = confidence;
    }

    public String getWhisperModel() {
        return whisperModel;
    }

    public void setWhisperModel(String whisperModel) {
        this.whisperModel = whisperModel;
    }
}
