package com.wolfhouse.red4j;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 操作 Redis 的工具类
 *
 * @author Rylin Wolf
 */
@SuppressWarnings("unused")
@Slf4j
public class RedisUtil {
    private static final ObjectMapper                    DEFAULT_OBJECT_MAPPER = new ObjectMapper();
    public final         RedisTemplate<String, Object>   redisTemplate;
    public final         RedissonClient                  redissonClient;
    public final         ValueOperations<String, Object> opsForValue;
    public final         SetOperations<String, Object>   opsForSet;
    public final         ZSetOperations<String, Object>  opsForZSet;
    @Setter
    @Getter
    private              ObjectMapper                    objectMapper;

    public RedisUtil(RedisTemplate<String, Object> redisTemplate, RedissonClient redissonClient) {
        this(redisTemplate, redissonClient, DEFAULT_OBJECT_MAPPER);
    }

    public RedisUtil(RedisTemplate<String, Object> redisTemplate, RedissonClient redissonClient, ObjectMapper objectMapper) {
        this.redisTemplate  = redisTemplate;
        this.opsForValue    = redisTemplate.opsForValue();
        this.opsForSet      = redisTemplate.opsForSet();
        this.opsForZSet     = redisTemplate.opsForZSet();
        this.redissonClient = redissonClient;
        this.objectMapper   = objectMapper;
    }

    private static void lockTaskErrorLog() {
        log.error("执行任务时发生错误");
    }

    public Long getAndIncrease(@NonNull String key, int value) {
        return opsForValue.increment(key, value);
    }

    // region set 方法

    public Long getAndDecrease(@NonNull String key, int value) {
        return opsForValue.decrement(key, value);
    }

    public Long addSetValue(@NonNull String key, Object... value) {
        return opsForSet.add(key, value);
    }

    public void removeSetValue(@NonNull String key, Object... value) {
        opsForSet.remove(key, value);
    }

    public Object popSetValue(@NonNull String key) {
        return opsForSet.pop(key);
    }

    public Set<Object> getSetMembers(@NonNull String key) {
        return opsForSet.members(key);
    }

    public Long sizeOfSetValue(@NonNull String key) {
        return opsForSet.size(key);
    }

    public Boolean isSetValueMember(@NonNull String key, Object value) {
        return opsForSet.isMember(key, value);
    }

    public Boolean addZSetValue(@NonNull String key, Object value, double score) {
        return opsForZSet.add(key, value, score);
    }

    public Long removeZSetValue(@NonNull String key) {
        return opsForZSet.remove(key);
    }


    // endregion

    // region value 方法

    public Double incrementZSetValue(@NonNull String key, Object value, double score) {
        return opsForZSet.incrementScore(key, value, score);
    }

    public void setValue(@NonNull String key, Object value) {
        this.opsForValue.set(key, value);
    }

    public void setValueExpire(@NonNull String key, Object value, Duration duration) {
        this.opsForValue.set(key, value, duration);
    }

    public Boolean setValueIfAbsent(@NonNull String key, Object value) {
        return this.opsForValue.setIfAbsent(key, value);
    }

    public Object getValue(@NonNull String key) {
        return this.opsForValue.get(key);
    }

    public Object getValueAndExpire(@NonNull String key, Duration duration) {
        Object value = this.opsForValue.get(key);
        this.redisTemplate.expire(key, duration);
        return value;
    }

    // endregion

    // region 内置方法

    public Object getValueAndDelete(@NonNull String key) {
        return opsForValue.getAndDelete(key);
    }

    public Boolean hasKey(@NonNull String key) {
        return redisTemplate.hasKey(key);
    }

    public Boolean delete(@NonNull String key) {
        return redisTemplate.delete(key);
    }

    public Boolean expire(@NonNull String key, Duration duration) {
        return redisTemplate.expire(key, duration);
    }

    // endregion

    // region 键匹配

    public long deleteMatch(@NonNull String pattern, int batchSize) {
        long count = 0;
        for (; ; ) {
            Set<String> keys = keysMatch(pattern, batchSize);
            if (keys.isEmpty()) {
                return count;
            }
            redisTemplate.delete(keys);
            count += keys.size();
        }
    }

    /**
     * 扫描 Redis 数据库中与指定匹配模式相符的键。批次大小默认为 10000。
     *
     * @param pattern 匹配模式，支持通配符，例如 "user:*"。
     * @return 返回匹配的键值集合。
     */
    public Set<String> keysMatch(@NonNull String pattern) {
        return keysMatch(pattern, 10000);
    }

    /**
     * 扫描 Redis 数据库中与指定匹配模式相符的键。
     *
     * @param pattern   匹配模式，支持通配符，例如 "user:*"。
     * @param batchSize 每次扫描的最大数量，控制扫描结果的批次大小。
     * @return 返回匹配的键值集合。
     */
    public Set<String> keysMatch(@NonNull String pattern, int batchSize) {
        HashSet<String> keys = new HashSet<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions()
                           .match(pattern)
                           .count(batchSize)
                           .build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        return keys;
    }

    /**
     * 扫描 Redis 数据库中与指定匹配模式相符的键，并同步执行提供方法
     *
     * @param pattern   匹配模式，支持通配符，例如 "user:*"。
     * @param batchSize 每次扫描的最大数量，控制扫描结果的批次大小。
     * @param function  附加方法，对键进行处理并返回处理结果
     * @return 返回执行结果，包含键数量和处理结果列表
     */
    public <T> KeyExecute<T> keysMatchWith(@NonNull String pattern, int batchSize, Function<String, T> function) {
        long    count  = 0;
        List<T> result = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions()
                           .match(pattern)
                           .count(batchSize)
                           .build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                count++;
                result.add(function.apply(key));
            }
        }
        return new KeyExecute<>(count, result);
    }

    // endregion

    // region 结果集转换

    @Nullable
    public <T> T convert(Object o, TypeReference<T> reference) {
        if (checkNull(o)) {
            return null;
        }
        return objectMapper.convertValue(o, reference);
    }

    @Nullable
    public <T> T convert(Object o, Class<T> clazz) {
        if (checkNull(o)) {
            return null;
        }
        return objectMapper.convertValue(o, clazz);
    }

    @Nullable
    public <T> List<T> convertList(Object o, Class<T> clazz) {
        return convertList(o, clazz, true);
    }

    /**
     * 将给定的对象转换为指定类型的列表。
     *
     * @param o          需要转换的对象。
     * @param clazz      列表中元素的目标类型。
     * @param ignoreNull 如果为 true，将过滤掉列表中的 null 值。
     * @return 转换后的列表，如果输入对象为 null，返回 null。
     */
    @Nullable
    public <T> List<T> convertList(Object o, Class<T> clazz, boolean ignoreNull) {
        if (checkNull(o)) {
            return null;
        }
        List<T> tList = objectMapper.convertValue(o, objectMapper.getTypeFactory()
                                                                 .constructCollectionType(List.class, clazz));
        return ignoreNull ? tList.stream().filter(Objects::nonNull).toList() : tList;
    }
    // endregion

    // region 分布式锁

    private boolean checkNull(Object o) {
        return Objects.isNull(o);
    }

    /**
     * 获取分布式锁
     *
     * @param key      锁的键
     * @param duration 锁的持续时间
     */
    public void lock(String key, Duration duration) {
        long seconds = duration.getSeconds();
        long ms      = seconds * 1000 + duration.getNano() / 1_000_000;
        redissonClient.getLock(key).lock(ms, TimeUnit.MILLISECONDS);
    }

    public boolean tryLock(String key) {
        return redissonClient.getLock(key).tryLock();
    }

    /**
     * 尝试释放分布式锁。仅在当前线程持有锁时才会成功
     *
     * @param key 锁的键
     */
    public void unlock(String key) {
        RLock lock = redissonClient.getLock(key);
        if (!lock.isLocked()) {
            // 锁不存在
            return;
        }
        if (!lock.isHeldByCurrentThread()) {
            // 锁非当前线程持有
            return;
        }
        lock.unlock();
    }

    /**
     * 使用分布式锁执行
     *
     * @param key      锁的键
     * @param duration 锁的持续时间
     * @param supplier 执行的函数
     * @param <T>      执行结果类型
     * @return 执行结果
     */
    public <T> T withLock(String key, Duration duration, Supplier<T> supplier) {
        Exception exception;
        lock(key, duration);
        try {
            return supplier.get();
        } catch (Exception e) {
            exception = e;
            lockTaskErrorLog();
        } finally {
            unlock(key);
        }

        throw new RuntimeException(exception);
    }

    /**
     * 尝试执行，如果锁被其他线程占用，则返回 false
     *
     * @param key      锁的键
     * @param consumer 待执行的任务
     * @return 是否执行成功
     */
    public <T> LockTaskResult<T> tryWithLock(String key, Function<Boolean, T> consumer) {
        Exception exception;
        try {
            boolean locked = tryLock(key);
            T       result = consumer.apply(locked);
            return locked ? LockTaskResult.success(result) : LockTaskResult.fail(result);
        } catch (Exception e) {
            exception = e;
            lockTaskErrorLog();
        } finally {
            unlock(key);
        }
        throw new RuntimeException(exception);
    }

    /**
     * 表示分布式锁执行任务的结果。
     *
     * @param <T>     任务执行结果的类型
     * @param success 标识任务是否执行成功
     * @param result  任务执行结果，如果任务失败则为 null
     */
    public record LockTaskResult<T>(boolean success, T result) {

        public static <T> LockTaskResult<T> fail(T result) {
            return new LockTaskResult<>(false, result);
        }

        public static <T> LockTaskResult<T> success(T result) {
            return new LockTaskResult<>(true, result);
        }
    }
    // endregion

    /**
     * 键匹配执行结果封装
     *
     * @param count  匹配到的键数量
     * @param result 执行结果
     */
    public record KeyExecute<T>(long count, List<T> result) {}

}
