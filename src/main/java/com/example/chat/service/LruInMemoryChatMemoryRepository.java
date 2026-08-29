package com.example.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 LRU 淘汰策略的内存版 {@link ChatMemoryRepository}。
 *
 * <p>替换 Spring AI 自带的 {@code InMemoryChatMemoryRepository}：
 * 后者底层是裸 {@code ConcurrentHashMap}，对会话数量没有任何上限，长时间运行
 * 会无界增长直至 OOM；本类用 {@link LinkedHashMap} 的 access-order 模式实现
 * 经典 LRU —— 每次 {@link #findByConversationId(String)} / {@link #saveAll(String, List)}
 * 都会把对应 sessionId 移到链表尾（MRU），容量超限时把链表头（LRU）整条淘汰。
 *
 * <p><b>线程安全</b>：{@link LinkedHashMap} 本身非线程安全，本类用单一对象锁
 * 包住所有读写。服务端 memory 路径调用频次不高（每个 chat 请求 2 次 get +
 * 2~N 次 saveAll），单锁不会成为瓶颈；将来真撞性能瓶颈再换 Caffeine 之类。
 *
 * <p><b>容量含义</b>：上限是"活跃会话数"（按 sessionId），不是"消息总数"也不是
 * "字节数"。单个会话内的消息数由 {@code MessageWindowChatMemory.maxMessages} 控制。
 * 粗略估算总内存上限：{@code maxConversations × maxMessages × 平均 message 大小}。
 */
public class LruInMemoryChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(LruInMemoryChatMemoryRepository.class);

    private final int maxConversations;
    private final LinkedHashMap<String, List<Message>> store;
    private final Object lock = new Object();

    public LruInMemoryChatMemoryRepository(int maxConversations) {
        Assert.isTrue(maxConversations > 0, "maxConversations must be positive");
        this.maxConversations = maxConversations;
        // accessOrder=true：get/put 都会把条目搬到链表尾；removeEldestEntry 返回 true 时淘汰链表头。
        this.store = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<Message>> eldest) {
                if (size() > LruInMemoryChatMemoryRepository.this.maxConversations) {
                    log.info("LRU evict conversation id={} (size={} > max={})",
                            eldest.getKey(), size(), LruInMemoryChatMemoryRepository.this.maxConversations);
                    return true;
                }
                return false;
            }
        };
        log.info("LruInMemoryChatMemoryRepository initialized with maxConversations={}", maxConversations);
    }

    @Override
    public List<String> findConversationIds() {
        synchronized (lock) {
            return new ArrayList<>(store.keySet());
        }
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        synchronized (lock) {
            List<Message> messages = store.get(conversationId);
            return messages != null ? new ArrayList<>(messages) : List.of();
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.notNull(messages, "messages cannot be null");
        Assert.noNullElements(messages, "messages cannot contain null elements");
        synchronized (lock) {
            // 防御性拷贝：避免外部修改 list 影响内部状态。
            store.put(conversationId, new ArrayList<>(messages));
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        synchronized (lock) {
            store.remove(conversationId);
        }
    }

    /** 当前活跃会话数。仅用于监控 / 测试。 */
    public int size() {
        synchronized (lock) {
            return store.size();
        }
    }
}
