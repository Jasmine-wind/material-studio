package studio;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import static studio.Models.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final Templates templates;
    private final Storage storage;
    private final StudioMapper mapper;
    private final TaskService tasks;
    public ApiController(Templates templates, Storage storage, StudioMapper mapper, TaskService tasks) {
        this.templates = templates; this.storage = storage; this.mapper = mapper; this.tasks = tasks;
    }
    @GetMapping("/templates") public List<Template> templates(@RequestParam(required = false) String category) { return templates.list(category); }
    @GetMapping("/templates/{id}") public Template template(@PathVariable String id) { return templates.get(id); }
    @PostMapping("/assets") public ResponseEntity<AssetView> upload(@RequestHeader("X-Demo-User") String owner,
                                                                 @RequestPart("file") MultipartFile file) throws IOException {
        var asset = storage.save(file, owner);
        try { mapper.insertAsset(asset); }
        catch (RuntimeException e) { storage.removeUpload(asset.storageKey); throw e; }
        return ResponseEntity.status(201).body(new AssetView(asset.id, asset.mimeType, asset.sizeBytes, "/api/assets/" + asset.id + "/content"));
    }
    @GetMapping("/assets/{id}/content") public ResponseEntity<FileSystemResource> asset(@RequestHeader("X-Demo-User") String owner, @PathVariable String id) {
        var asset = mapper.asset(id, owner);
        if (asset == null) throw ApiError.missing();
        return image(storage.upload(asset.storageKey), asset.mimeType);
    }
    @PostMapping("/tasks") public ResponseEntity<Accepted> create(@RequestHeader("X-Demo-User") String owner, @RequestBody CreateTask request) {
        return ResponseEntity.accepted().body(tasks.create(owner, request));
    }
    @GetMapping("/tasks") public List<TaskView> list(@RequestHeader("X-Demo-User") String owner) { return tasks.list(owner); }
    @GetMapping("/tasks/{id}") public TaskView task(@RequestHeader("X-Demo-User") String owner, @PathVariable String id) { return tasks.view(tasks.get(owner, id)); }
    @GetMapping("/tasks/{id}/result") public ResponseEntity<FileSystemResource> result(@RequestHeader("X-Demo-User") String owner, @PathVariable String id) {
        var task = tasks.get(owner, id);
        if (!"SUCCEEDED".equals(task.status)) throw new ApiError(HttpStatus.CONFLICT, "RESULT_NOT_READY", "任务尚未成功，结果未就绪");
        return image(storage.result(task.resultStorageKey), task.resultStorageKey.endsWith(".png") ? "image/png" : "image/jpeg");
    }
    private ResponseEntity<FileSystemResource> image(Path path, String mime) {
        if (!Files.isRegularFile(path)) throw ApiError.missing();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(path));
    }
}
