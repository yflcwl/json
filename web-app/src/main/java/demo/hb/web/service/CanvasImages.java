package demo.hb.web.service;

import demo.hb.web.config.SketchProperties;
import demo.hb.web.exception.GenerationException;
import demo.hb.web.exception.GenerationException.Code;
import demo.hb.web.repository.CanvasRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class CanvasImages {

    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private final Path root;
    private final CanvasRepository repository;

    public CanvasImages(SketchProperties properties, CanvasRepository repository) {
        this.root = Path.of(properties.dataDirectory()).toAbsolutePath().normalize();
        this.repository = repository;
    }

    public String store(UUID id, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new GenerationException(Code.INVALID_INPUT, "请选择图片文件。");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new GenerationException(Code.REQUEST_TOO_LARGE, "单张图片不能超过 5MB。");
        }
        if (!repository.exists(id)) {
            throw new GenerationException(Code.NOT_FOUND, "找不到这张画布。");
        }
        String extension = imageExtension(bytes);
        if (extension == null) {
            throw new GenerationException(Code.INVALID_INPUT, "仅支持 PNG、JPEG、GIF、WEBP 图片。");
        }
        String name = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path directory = root.resolve(id.toString()).resolve("uploads");
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(name), bytes);
        } catch (IOException ex) {
            throw new GenerationException(Code.STORAGE_FAILURE, "图片保存失败，请检查本地数据目录。");
        }
        return "uploads/" + name;
    }

    public List<String> resolve(UUID id, List<String> images) {
        if (images.isEmpty()) {
            return List.of();
        }
        Path uploads = root.resolve(id.toString()).resolve("uploads");
        Path canvasDirectory = uploads.getParent();
        List<String> resolved = new ArrayList<>(images.size());
        for (String image : images) {
            if (image == null || image.isBlank() || image.length() > 200) {
                throw new GenerationException(Code.INVALID_INPUT, "图片路径无效。");
            }
            Path relative = Path.of(image);
            if (relative.isAbsolute()) {
                throw new GenerationException(Code.INVALID_INPUT, "图片路径无效。");
            }
            Path file = canvasDirectory.resolve(relative).normalize();
            if (!file.startsWith(uploads) || !Files.isRegularFile(file)) {
                throw new GenerationException(Code.INVALID_INPUT, "图片不存在或已被移除。");
            }
            if (imageExtension(readHead(file)) == null) {
                throw new GenerationException(Code.INVALID_INPUT, "图片格式不受支持。");
            }
            try {
                if (Files.size(file) > MAX_IMAGE_BYTES) {
                    throw new GenerationException(Code.REQUEST_TOO_LARGE, "单张图片不能超过 5MB。");
                }
            } catch (IOException ex) {
                throw new GenerationException(Code.STORAGE_FAILURE, "图片读取失败，请重试。");
            }
            resolved.add(file.toString());
        }
        return resolved;
    }

    private static byte[] readHead(Path file) {
        try (var input = Files.newInputStream(file)) {
            return input.readNBytes(12);
        } catch (IOException ex) {
            throw new GenerationException(Code.STORAGE_FAILURE, "图片读取失败，请重试。");
        }
    }

    private static String imageExtension(byte[] head) {
        if (head.length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) {
            return "png";
        }
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8
                && (head[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (head.length >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8'
                && (head[4] == '7' || head[4] == '9') && head[5] == 'a') {
            return "gif";
        }
        if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return "webp";
        }
        return null;
    }
}
