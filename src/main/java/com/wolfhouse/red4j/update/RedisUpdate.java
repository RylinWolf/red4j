package com.wolfhouse.red4j.update;

import org.intellij.lang.annotations.Language;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Redis 更新注解，用于标注在方法上。
 * <p>
 * 当方法执行完成后，会自动触发特定 Redis 服务的更新操作。
 *
 * @author Rylin Wolf
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RedisUpdate {

    /**
     * 指定要操作的 Redis 服务类。
     * 默认会从 Spring 容器中查找该类型的 Bean。
     */
    Class<?> redisService() default Void.class;

    /**
     * 指定更新方法名，必须是 {@link #redisService()} 指定的服务类中的方法。
     * 优先级低于 {@link #updateMethodSpEL()}。
     */
    String updateMethod() default "update";

    /**
     * 指定更新方法名，使用 SpEL 表达式。
     * <p>
     * 1. 如果表达式以 "@" 开头（例如 "@webRedisMediator.update(#result)"），则被视为完整的 SpEL，
     * 将直接执行该表达式，此时 {@link #redisService()} 和 {@link #updateMethod()} 将被忽略。
     * 2. 否则，该表达式应返回一个方法名字符串，且必须是 {@link #redisService()} 指定的服务类中的方法。
     * <p>
     * 表达式上下文中包含方法参数和方法返回值 (#result)。
     */
    @Language("SpEL")
    String updateMethodSpEL() default "";
}
