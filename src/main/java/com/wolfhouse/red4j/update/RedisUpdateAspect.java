package com.wolfhouse.red4j.update;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.ApplicationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Redis 更新切面，用于处理方法调用时的 Redis 更新操作。
 * <p>
 * 该切面监听标注了 {@link RedisUpdate} 注解的方法，
 * 在方法执行成功返回后，根据配置触发相应 Redis 服务的更新方法。
 *
 * @author Rylin Wolf
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RedisUpdateAspect {

    private final ApplicationContext             applicationContext;
    private final ExpressionParser               parser                  = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 环绕通知，处理正常返回和异常场景下的 Redis 更新逻辑
     */
    @Around("@annotation(redisUpdate)")
    public Object doAround(ProceedingJoinPoint joinPoint, RedisUpdate redisUpdate) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method          method    = signature.getMethod();
        Object          result    = null;
        Throwable       exception = null;

        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable t) {
            exception = t;
            throw t;
        } finally {
            handleRedisUpdate(joinPoint, redisUpdate, method, result, exception);
        }
    }

    private void handleRedisUpdate(JoinPoint joinPoint, RedisUpdate redisUpdate, Method method, Object result, Throwable exception) {
        String methodName = method.getName();
        try {
            // 1. 异常处理逻辑
            if (exception != null) {
                if (!redisUpdate.onException()) {
                    return;
                }
                log.debug("[RedisUpdateAspect] 捕获到异常且配置了 onException=true，将继续执行更新逻辑: method={}", methodName);
            }

            // 2. 布尔返回值处理逻辑
            if (exception == null && result instanceof Boolean) {
                boolean boolResult = (Boolean) result;
                if (!boolResult && !redisUpdate.ignoreResult()) {
                    log.debug("[RedisUpdateAspect] 方法返回 false 且未配置 ignoreResult=true，跳过更新: method={}", methodName);
                    return;
                }
            }

            String spel = redisUpdate.updateMethodSpEL();

            // 3. 如果是完整的 SpEL (以 @ 开头)，直接执行
            if (StringUtils.hasText(spel) && spel.trim().startsWith("@")) {
                evaluateSpel(joinPoint, spel, result, null);
                log.debug("[RedisUpdateAspect] 成功执行完整 SpEL 更新逻辑: spel={}", spel);
                return;
            }

            // 4. 确定更新数据
            Object updateData = getUpdateData(method, joinPoint.getArgs(), result);

            // 5. 获取 Redis 服务 Bean
            Class<?> serviceClass = redisUpdate.redisService();
            if (serviceClass == Void.class) {
                log.warn("[RedisUpdateAspect] RedisUpdate 未指定 redisService: method={}", methodName);
                return;
            }
            Object serviceBean = applicationContext.getBean(serviceClass);

            // 6. 确定要调用的方法名
            String updateMethodName = getUpdateMethodName(joinPoint, redisUpdate, result);
            if (!StringUtils.hasText(updateMethodName)) {
                log.warn("[RedisUpdateAspect] RedisUpdate 未指定有效的更新方法名");
                return;
            }

            // 7. 执行更新方法
            executeUpdate(serviceBean, updateMethodName, updateData);

            log.debug("[RedisUpdateAspect] 成功触发 Redis 更新: service={}, method={}, data={}",
                      serviceClass.getSimpleName(), updateMethodName, updateData);

        } catch (Exception e) {
            log.error("[RedisUpdateAspect] 执行 RedisUpdate 异常: method={}", methodName, e);
        }
    }

    /**
     * 解析更新方法名，支持 SpEL
     */
    private String getUpdateMethodName(JoinPoint joinPoint, RedisUpdate redisUpdate, Object result) {
        String spel = redisUpdate.updateMethodSpEL();
        if (StringUtils.hasText(spel)) {
            Object value = evaluateSpel(joinPoint, spel, result, null);
            return value != null ? value.toString() : null;
        }
        return redisUpdate.updateMethod();
    }

    /**
     * 使用 Spring 表达式语言 (SpEL) 解析表达式并返回计算结果。
     */
    private Object evaluateSpel(JoinPoint joinPoint, String spel, Object result, Class<?> resultType) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setBeanResolver(new org.springframework.context.expression.BeanFactoryResolver(applicationContext));
        context.setVariable("result", result);

        Object[]        args           = joinPoint.getArgs();
        MethodSignature signature      = (MethodSignature) joinPoint.getSignature();
        String[]        parameterNames = parameterNameDiscoverer.getParameterNames(signature.getMethod());

        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }
        Expression expression = parser.parseExpression(spel);
        if (resultType != null) {
            return expression.getValue(context, resultType);
        }
        return expression.getValue(context);
    }

    /**
     * 获取用于更新的数据
     */
    private Object getUpdateData(Method method, Object[] args, Object result) {
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        for (int i = 0; i < parameterAnnotations.length; i++) {
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof Redata) {
                    return args[i];
                }
            }
        }
        // 默认返回方法执行结果
        return result;
    }

    /**
     * 在 serviceBean 上执行指定的更新方法
     */
    private void executeUpdate(Object serviceBean, String methodName, Object updateData) throws Exception {
        Method[] methods      = serviceBean.getClass().getMethods();
        Method   targetMethod = null;

        for (Method m : methods) {
            if (m.getName().equals(methodName) && m.getParameterCount() == 1) {
                if (updateData == null) {
                    targetMethod = m;
                    break;
                }
                if (ClassUtils.isAssignable(m.getParameterTypes()[0], updateData.getClass())) {
                    targetMethod = m;
                    break;
                }
            }
        }

        if (targetMethod == null) {
            throw new NoSuchMethodException("未在 " + serviceBean.getClass().getName() + " 中找到匹配的更新方法: " + methodName);
        }

        targetMethod.invoke(serviceBean, updateData);
    }
}
