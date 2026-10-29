package studio;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.core.task.TaskRejectedException;
import java.nio.file.Files;
import java.util.*;
import static studio.Models.*;

@Service
public class TaskService {
    private final StudioMapper mapper;
    private final Templates templates;
    private final Storage storage;
    private final TaskRunner runner;
    private final ThreadPoolTaskExecutor executor;
    private final TransactionTemplate transaction;
    private final boolean demoEnabled;
    public TaskService(StudioMapper mapper, Templates templates, Storage storage, TaskRunner runner,
                       ThreadPoolTaskExecutor executor, PlatformTransactionManager manager,
                       @Value("${studio.demo-enabled}") boolean demoEnabled) {
        this.mapper = mapper; this.templates = templates; this.storage = storage; this.runner = runner;
        this.executor = executor; this.transaction = new TransactionTemplate(manager); this.demoEnabled = demoEnabled;
    }
    public Accepted create(String owner, CreateTask request) {
        var template = templates.get(request.templateId());
        if (request.assetIds() == null || request.assetIds().size() != template.requiredImageCount()
                || new HashSet<>(request.assetIds()).size() != request.assetIds().size()) throw ApiError.bad("素材数量必须符合模板要求，且不能重复");
        for (String id : request.assetIds()) {
            var asset = mapper.asset(id, owner);
            if (asset == null) throw ApiError.missing();
            if (!template.acceptedMimeTypes().contains(asset.mimeType) || asset.sizeBytes > template.maxFileSizeBytes()
                    || !Files.isRegularFile(storage.upload(asset.storageKey))) throw ApiError.bad("素材格式、大小或文件状态不符合模板要求");
        }
        var simulation = request.simulation();
        if (simulation != null && !demoEnabled) throw ApiError.bad("当前环境未启用演示模拟参数");
        String failStage = simulation == null ? null : simulation.failStage();
        int delay = simulation == null || simulation.delayMs() == null ? 2000 : simulation.delayMs();
        if (delay < 0 || delay > 10000 || (failStage != null && !TaskRunner.STAGES.contains(failStage))) throw ApiError.bad("非法模拟参数：阶段或耗时（0～10000ms）");
        var task = new Task();
        task.id = UUID.randomUUID().toString(); task.ownerId = owner; task.templateId = template.id();
        task.simulateFailStage = failStage; task.simulateDelayMs = delay;
        transaction.executeWithoutResult(status -> {
            mapper.insertTask(task);
            for (int i = 0; i < request.assetIds().size(); i++) mapper.link(task.id, i, request.assetIds().get(i));
        }); // Commit is complete before the task can be scheduled.
        try { executor.execute(() -> runner.run(task)); }
        catch (TaskRejectedException e) {
            mapper.fail(task.id, "NONE", "服务繁忙，任务未能进入执行队列");
            throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "QUEUE_FULL", "任务队列已满，本次任务已标记失败，请稍后再提交");
        }
        return new Accepted(task.id, "QUEUED", "NONE");
    }
    public Task get(String owner, String id) {
        var task = mapper.task(id, owner);
        if (task == null) throw ApiError.missing();
        return task;
    }
    public TaskView view(Task t) {
        return new TaskView(t.id, t.templateId, templates.get(t.templateId).title(), t.status, t.stage, t.failedStage,
                t.errorMessage, "SUCCEEDED".equals(t.status) ? "/api/tasks/" + t.id + "/result" : null,
                t.createdAt, t.updatedAt, t.finishedAt);
    }
    public List<TaskView> list(String owner) { return mapper.tasks(owner).stream().map(this::view).toList(); }
}
