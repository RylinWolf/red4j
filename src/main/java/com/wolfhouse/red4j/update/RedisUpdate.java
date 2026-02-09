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

    /**
     * 当方法返回值为布尔型时，是否忽略返回值，始终执行更新。
     * <p>
     * 默认为 false，即：若返回 false 则不执行更新，返回 true 则执行更新。
     * 若设置为 true，则无论返回 true 还是 false，都会执行更新。
     */
    boolean ignoreResult() default false;

    /**
     * 当方法抛出异常时，是否仍然执行更新。
     * <p>
     * 默认为 false，即：抛出异常时不执行更新。
     */
    boolean onException() default false;
}
