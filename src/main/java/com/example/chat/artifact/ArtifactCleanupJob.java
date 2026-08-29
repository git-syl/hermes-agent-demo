package com.example.chat.artifact;

import com.example.chat.config.ArtifactProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * 定期清理 {@link ArtifactProperties#getDir()} 下过期的 artifact 子目录。
 *
 * <p><b>触发</b>：{@code chat.artifacts.cleanup-cron} 默认 {@code "0 0 3 * * *"}
 * （每天凌晨 3 点）。{@code ttlDays<=0} 或 {@code cleanupCron} 为空时直接 no-op，
 * 不会调度也不会扫盘。
 *
 * <p><b>安全护栏</b>：
 * <ul>
 *   <li>仅删 {@code <artifacts.dir>} 下"看起来像 UUID"的<u>直接</u>子目录
 *       （{@link ArtifactStorage} 入盘命名是 {@code UUID.randomUUID()}），其它
 *       任何文件/目录一律跳过——配错 {@code dir} 也不会误删用户数据。</li>
 *   <li>按子目录的 {@code lastModifiedTime} 判断超期，与创建时间近似一致。</li>
 *   <li>单个子目录删除失败只 {@code warn}，不打断整次任务。</li>
 *   <li>每次跑结束打 1 行汇总日志：{@code scanned/deleted/errors}。</li>
 * </ul>
 *
 * <p>该 bean 通过 {@code @EnableScheduling}（在 {@code ChatApplication} 上）激活。
 */
@Component
public class ArtifactCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ArtifactCleanupJob.class);

    /** UUID v4 字符串模式：8-4-4-4-12，全小写十六进制。{@link java.util.UUID#randomUUID()} 输出与之匹配。 */
    private static final Pattern UUID_DIR_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final ArtifactProperties props;

    public ArtifactCleanupJob(ArtifactProperties props) {
        this.props = props;
    }

    /**
     * cron 由配置注入。Spring 6 接受 6 段 cron（秒 分 时 日 月 周）。
     * 默认 {@code 0 0 3 * * *}（每天凌晨 3 点），错峰避开业务峰值。
     */
    @Scheduled(cron = "${chat.artifacts.cleanup-cron:0 0 3 * * *}")
    public void cleanup() {
        int ttlDays = props.getTtlDays();
        String cron = props.getCleanupCron();
        if (ttlDays <= 0 || !StringUtils.hasText(cron)) {
            log.debug("Artifact cleanup skipped (ttlDays={}, cron='{}')", ttlDays, cron);
            return;
        }

        Path root = Path.of(props.getDir());
        if (!Files.isDirectory(root)) {
            log.debug("Artifact cleanup skipped: dir does not exist yet ({})", root);
            return;
        }

        long cutoffMillis = System.currentTimeMillis() - Duration.ofDays(ttlDays).toMillis();
        int scanned = 0;
        int deleted = 0;
        int errors = 0;

        try (var stream = Files.list(root)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                String name = child.getFileName().toString();
                if (!UUID_DIR_PATTERN.matcher(name).matches()) {
                    continue;
                }
                scanned++;
                try {
                    long mtime = Files.getLastModifiedTime(child).toMillis();
                    if (mtime < cutoffMillis) {
                        deleteRecursively(child);
                        deleted++;
                    }
                } catch (IOException e) {
                    errors++;
                    log.warn("Artifact cleanup failed to delete {}: {}", child, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Artifact cleanup failed to list {}: {}", root, e.getMessage());
            return;
        }

        log.info("Artifact cleanup done: dir={}, ttlDays={}, scanned={}, deleted={}, errors={}",
                root, ttlDays, scanned, deleted, errors);
    }

    /**
     * 递归删除目录树。倒序遍历，子节点先删、父目录最后删，保证空目录后才被移除。
     */
    private static void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // 局部失败转 unchecked，由外层捕获并计入 errors。
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
