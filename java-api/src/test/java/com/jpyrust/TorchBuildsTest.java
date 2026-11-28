package com.jpyrust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class TorchBuildsTest {

    private static final String NVIDIA_SMI = String.join("\n",
            "+-----------------------------------------------------------------------------------------+",
            "| NVIDIA-SMI 550.54.15              Driver Version: 550.54.15      CUDA Version: 12.4     |",
            "|-----------------------------------------+------------------------+----------------------+");

    @Test
    public void acceptsKnownBuildNames() {
        assertEquals("cu121", TorchBuilds.validate(" CU121 "));
        assertEquals("rocm6.2", TorchBuilds.validate("rocm6.2"));
        assertEquals("auto", TorchBuilds.validate("auto"));
        assertThrows(IllegalArgumentException.class, () -> TorchBuilds.validate("cuda12"));
        assertThrows(IllegalArgumentException.class, () -> TorchBuilds.validate("cu12"));
    }

    @Test
    public void findsTorchLinesAndThePinnedVersion() {
        List<String> lines = TorchBuilds.torchRequirements(String.join("\n",
                "numpy==1.26.4",
                "torch==2.2.0  # pinned",
                "torchvision==0.17.0",
                "torchmetrics==1.0",
                "openai-whisper==20231117",
                "torchaudio>=2.2 ; python_version < \"3.13\""));
        assertEquals(List.of("torch==2.2.0", "torchvision==0.17.0", "torchaudio>=2.2 ; python_version < \"3.13\""), lines);
        assertEquals(Optional.of("2.2.0"), TorchBuilds.pinnedTorch(lines));
        assertEquals(Optional.empty(), TorchBuilds.pinnedTorch(List.of("torch>=2")));
    }

    @Test
    public void readsTheDriverCudaVersionAndGpuCount() {
        assertEquals(Optional.of("12.4"), TorchBuilds.driverCuda(NVIDIA_SMI));
        assertEquals(Optional.empty(), TorchBuilds.driverCuda("command not found"));
        assertEquals(2, TorchBuilds.gpuCount("GPU 0: NVIDIA A10G (UUID: GPU-1)\nGPU 1: NVIDIA A10G (UUID: GPU-2)\n"));
    }

    @Test
    public void recommendsTheNewestPublishedBuildTheDriverSupports() {
        Optional<String> torch22 = Optional.of("2.2.0");
        assertEquals("cu121", TorchBuilds.recommend(false, Optional.of("12.4"), torch22,
                (build, version) -> build.equals("cu121") || build.equals("cu118")));
        assertEquals("cu118", TorchBuilds.recommend(false, Optional.of("12.0"), torch22, (build, version) -> true));
        assertEquals("cu124", TorchBuilds.recommend(false, Optional.of("12.4"), Optional.empty(), (build, version) -> false));
        assertEquals("cpu", TorchBuilds.recommend(false, Optional.of("11.4"), torch22, (build, version) -> true));
        assertEquals("cpu", TorchBuilds.recommend(false, Optional.empty(), torch22, (build, version) -> true));
        assertEquals("default", TorchBuilds.recommend(true, Optional.empty(), torch22, (build, version) -> true));
    }
}
