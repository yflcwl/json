package demo.hb.web.repository;

import demo.hb.web.config.SketchProperties;
import demo.hb.web.exception.GenerationException;
import demo.hb.web.exception.GenerationException.Code;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

@Repository
public class CanvasRepository {

    private static final Logger LOG = LoggerFactory.getLogger(CanvasRepository.class);
    private static final String PAGE_FILE = "page.json";
    private static final String META_FILE = "meta.json";
    private final Path root;

    public CanvasRepository(SketchProperties properties) throws IOException {
        this.root = Path.of(properties.dataDirectory()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        LOG.info("画布数据目录已就绪 directory={}", root);
    }

    public byte[] read(UUID id) {
        Path file = path(id);
        if (Files.notExists(file)) {
            throw new GenerationException(Code.NOT_FOUND, "找不到这张画布。");
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException ex) {
            throw storageFailure(ex);
        }
    }

    public void write(UUID id, byte[] page) {
        try {
            Path target = path(id);
            Files.createDirectories(target.getParent());
            atomicWrite(target, page);
            ensureNumber(target.getParent());
        } catch (IOException ex) {
            throw storageFailure(ex);
        }
    }

    public boolean exists(UUID id) {
        return Files.isRegularFile(path(id));
    }

    public void delete(UUID id) {
        Path directory = root.resolve(id.toString());
        if (Files.notExists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ex) {
            throw storageFailure(ex);
        }
    }

    public List<CanvasInfo> list() {
        try (var directories = Files.list(root)) {
            var canvases = new ArrayList<CanvasInfo>();
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                Path page = directory.resolve(PAGE_FILE);
                if (!Files.isRegularFile(page)) {
                    continue;
                }
                try {
                    UUID id = UUID.fromString(directory.getFileName().toString());
                    canvases.add(new CanvasInfo(id, Files.getLastModifiedTime(page).toInstant(),
                            readNumber(directory)));
                } catch (IllegalArgumentException ignored) {
                }
            }
            canvases.sort(Comparator.comparing(CanvasInfo::updatedAt).reversed());
            // 历史画布没有编号时按“旧→新”补齐并持久化，编号创建后不再随删除变化。
            int next = canvases.stream().mapToInt(CanvasInfo::number).max().orElse(0) + 1;
            for (int index = canvases.size() - 1; index >= 0; index--) {
                CanvasInfo info = canvases.get(index);
                if (info.number() > 0) {
                    continue;
                }
                int number = next++;
                try {
                    writeNumber(root.resolve(info.id().toString()), number);
                } catch (IOException ignored) {
                }
                canvases.set(index, new CanvasInfo(info.id(), info.updatedAt(), number));
            }
            return canvases;
        } catch (IOException ex) {
            throw storageFailure(ex);
        }
    }

    public record CanvasInfo(UUID id, Instant updatedAt, int number) {
    }

    private Path path(UUID id) {
        return root.resolve(id.toString()).resolve(PAGE_FILE);
    }

    private void ensureNumber(Path directory) throws IOException {
        if (Files.exists(directory.resolve(META_FILE))) {
            return;
        }
        int next = 1;
        try (var directories = Files.list(root)) {
            for (Path sibling : directories.filter(Files::isDirectory).toList()) {
                next = Math.max(next, readNumber(sibling) + 1);
            }
        }
        writeNumber(directory, next);
    }

    private static int readNumber(Path directory) {
        Path meta = directory.resolve(META_FILE);
        if (!Files.isRegularFile(meta)) {
            return 0;
        }
        try {
            String digits = Files.readString(meta, java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (IOException | NumberFormatException ex) {
            return 0;
        }
    }

    private void writeNumber(Path directory, int number) throws IOException {
        Files.createDirectories(directory);
        atomicWrite(directory.resolve(META_FILE),
                ("{\"number\":" + number + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void atomicWrite(Path target, byte[] content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".writing-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(content);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static GenerationException storageFailure(IOException ex) {
        LOG.error("Canvas storage operation failed", ex);
        return new GenerationException(Code.STORAGE_FAILURE, "画布数据读写失败，请检查本地数据目录。");
    }
}
