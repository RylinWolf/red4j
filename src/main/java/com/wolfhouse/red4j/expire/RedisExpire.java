package com.wolfhouse.red4j.expire;

import org.intellij.lang.annotations.Language;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Redis 过期注解，用于标注在类或方法上。
 * <p>
 * 当方法执行完成后，会自动触发特定 Redis 服务的过期（清理）操作。
 * 支持通过方法名匹配（正则或包含）来精细控制触发时机。
 *
 * @author Rylin Wolf
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RedisExpire {
    /**
     * 匹配方法名（正则表达式）。
     * 默认匹配 add, delete, update 以及以它们开头的、符合下划线或小驼峰规则的名称
     */
    @Language("RegExp")
    String[] includePattern() default {"add", "delete", "update", "^add[A-Z0-9_].*", "^delete[A-Z0-9_].*", "^update[A-Z0-9_].*"};

    /**
     * 排除方法名（正则表达式）。
     * 优先级高于 includePattern。
     */
    @Language("RegExp")
    String[] excludePattern() default {};

    /**
     * 匹配方法名包含的字符串。
     */
    String[] includeValue() default {};

    /**
     * 排除方法名包含的字符串。
     * 优先级高于 includeValue。
     */
    String[] excludeValue() default {};

    /**
     * 指定要操作的 Redis 服务类。
     * 默认会从 Spring 容器中查找该类型的 Bean。
     */
    Class<?> redisService() default Void.class;

    /**
     * 指定过期方法名，必须是 {@link #redisService()} 指定的服务类中的方法。
     * 优先级低于 {@link #expireMethodSpEL()}。
     */
    String expireMethod() default "expireAll";

    /**
     * 指定过期方法名，使用 SpEL 表达式。
     * <p>
     * 1. 如果表达式以 "@" 开头（例如 "@webRedisMediator.expireAll()"），则被视为完整的 SpEL，
     * 将直接执行该表达式，此时 {@link #redisService()} 和 {@link #expireMethod()} 将被忽略。
     * 2. 否则，该表达式应返回一个方法名字符串，且必须是 {@link #redisService()} 指定的服务类中的方法。
     * <p>
     * 表达式上下文中包含方法参数等信息。
     */
    @Language("SpEL")
    String expireMethodSpEL() default "";
}
