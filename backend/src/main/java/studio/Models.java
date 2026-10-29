package studio;

import java.time.Instant;
import java.util.List;

public final class Models {
    private Models() {}
    public record Template(String id, String title, String category, String coverUrl, String description,
                           int requiredImageCount, List<String> acceptedMimeTypes, long maxFileSizeBytes) {}
    public record Simulation(String failStage, Integer delayMs) {}
    public record CreateTask(String templateId, List<String> assetIds, Simulation simulation) {}
    public record Accepted(String id, String status, String stage) {}
    public record AssetView(String id, String mimeType, long sizeBytes, String previewUrl) {}
    public record TaskView(String id, String templateId, String templateTitle, String status, String stage,
                           String failedStage, String errorMessage, String resultUrl, Instant createdAt,
                           Instant updatedAt, Instant finishedAt) {}
    public static class Asset {
        public String id, ownerId, originalName, mimeType, storageKey;
        public long sizeBytes;
        public Instant createdAt;
    }
    public static class Task {
        public String id, ownerId, templateId, status, stage, failedStage, errorMessage, resultStorageKey, simulateFailStage;
        public int simulateDelayMs;
        public Instant createdAt, updatedAt, finishedAt;
    }
}
