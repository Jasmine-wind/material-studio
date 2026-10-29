package studio;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static studio.Models.*;

@Service
public class Storage {
    public static final long MAX_BYTES = 10 * 1024 * 1024;
    private final Path uploads, results;
    public Storage(@Value("${studio.storage}") String root) throws IOException {
        uploads = Path.of(root).toAbsolutePath().normalize().resolve("uploads");
        results = Path.of(root).toAbsolutePath().normalize().resolve("results");
        Files.createDirectories(uploads);
        Files.createDirectories(results);
    }
    public Asset save(MultipartFile file, String owner) throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw ApiError.bad("图片不能为空且不得超过 10MB");
        byte[] bytes = file.getBytes();
        String mime = signature(bytes);
        if (mime == null || !mime.equals(file.getContentType())) throw ApiError.bad("仅支持签名与 MIME 一致的 PNG / JPEG 图片");
        try (var in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw ApiError.bad("无法解析图片");
            var reader = readers.next();
            try {
                reader.setInput(in);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > 40_000_000) throw ApiError.bad("图片像素不得超过 4000 万");
                if (reader.read(0) == null) throw ApiError.bad("图片内容损坏");
            } finally { reader.dispose(); }
        } catch (IOException e) { throw ApiError.bad("图片内容损坏"); }
        var asset = new Asset();
        asset.id = UUID.randomUUID().toString();
        asset.ownerId = owner;
        asset.originalName = Optional.ofNullable(file.getOriginalFilename()).orElse("image");
        if (asset.originalName.length() > 255) asset.originalName = asset.originalName.substring(0, 255);
        asset.mimeType = mime;
        asset.sizeBytes = bytes.length;
        asset.storageKey = UUID.randomUUID() + (mime.equals("image/png") ? ".png" : ".jpg");
        Files.write(upload(asset.storageKey), bytes, StandardOpenOption.CREATE_NEW);
        return asset;
    }
    static String signature(byte[] b) {
        if (b.length >= 8 && Arrays.equals(Arrays.copyOf(b, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
        if (b.length >= 3 && b[0] == (byte)255 && b[1] == (byte)216 && b[2] == (byte)255) return "image/jpeg";
        return null;
    }
    private Path safe(Path base, String key) {
        if (key == null || !key.matches("[a-zA-Z0-9.-]+") || key.contains("..")) throw ApiError.bad("非法文件键");
        return base.resolve(key);
    }
    public Path upload(String key) { return safe(uploads, key); }
    public Path result(String key) { return safe(results, key); }
    public Path temporary(String id) { return result(id + ".tmp"); }
    public void removeUpload(String key) throws IOException { Files.deleteIfExists(upload(key)); }
}
