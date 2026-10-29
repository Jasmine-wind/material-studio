package studio;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.List;
import static studio.Models.*;

/** Every mapper update autocommits independently. No transaction spans processing or sleeping. */
@Service
public class TaskRunner {
    public static final List<String> STAGES = List.of("CHECK_MATERIAL", "GENERATE", "FINALIZE");
    private final StudioMapper mapper;
    private final Storage storage;
    public TaskRunner(StudioMapper mapper, Storage storage) { this.mapper = mapper; this.storage = storage; }
    public void run(Task task) {
        String stage = "NONE";
        Path temporary = storage.temporary(task.id);
        try {
            String previous = "NONE";
            var inputs = mapper.inputs(task.id);
            if (inputs.isEmpty()) throw new IllegalStateException("任务素材缺失");
            String resultKey = task.id + (inputs.getFirst().mimeType.equals("image/png") ? ".png" : ".jpg");
            for (String next : STAGES) {
                stage = next;
                if (mapper.startStage(task.id, previous, stage, previous.equals("NONE") ? "QUEUED" : "RUNNING") != 1) return;
                Thread.sleep(task.simulateDelayMs);
                if (stage.equals(task.simulateFailStage)) throw new IllegalStateException("演示：" + stage + " 阶段指定失败");
                switch (stage) {
                    case "CHECK_MATERIAL" -> {
                        for (var input : inputs) if (!Files.isRegularFile(storage.upload(input.storageKey)))
                            throw new IllegalStateException("上传素材文件不存在");
                    }
                    case "GENERATE" -> Files.copy(storage.upload(inputs.getFirst().storageKey), temporary, StandardCopyOption.REPLACE_EXISTING);
                    case "FINALIZE" -> Files.move(temporary, storage.result(resultKey), StandardCopyOption.ATOMIC_MOVE);
                    default -> throw new IllegalStateException("未知阶段");
                }
                previous = stage;
            }
            mapper.succeed(task.id, resultKey);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LoggerFactory.getLogger(TaskRunner.class).warn("Task {} failed at {}", task.id, stage, e);
            String message = e instanceof IllegalStateException ? e.getMessage() : "处理异常，请检查素材或服务日志";
            mapper.fail(task.id, stage, message);
        } finally {
            try { Files.deleteIfExists(temporary); } catch (Exception e) { LoggerFactory.getLogger(TaskRunner.class).warn("Temporary cleanup failed", e); }
        }
    }
}
