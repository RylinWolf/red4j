package com.wolfhouse.red4j.update;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Redis 更新数据注解，用于标注在方法参数上。
 * <p>
 * 表明更新 Redis 时使用该参数作为目标更新方法的参数。
 *
 * @author Rylin Wolf
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Redata {
}
