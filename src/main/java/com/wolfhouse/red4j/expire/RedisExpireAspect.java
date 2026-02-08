package com.wolfhouse.red4j.expire;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.ApplicationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

/**
 * Redis 过期切面，用于处理方法调用时的 Redis 过期操作。
 * <p>
 * 该切面监听标注了 {@link RedisExpire} 注解的类或方法，
 * 在方法执行成功返回后，根据配置的规则触发相应 Redis 服务的过期清理方法。
 *
 * @author Rylin Wolf
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RedisExpireAspect {
    private final ApplicationContext             applicationContext;
    private final ExpressionParser               parser                  = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 定义切点：标注了 @RedisExpire 注解的方法或者标注了 @RedisExpire 注解的类中的所有方法
     */
    @Pointcut("@annotation(com.wolfhouse.red4j.expire.RedisExpire) || @within(com.wolfhouse.red4j.expire.RedisExpire)")
    public void redisExpirePointcut() {
    }

    /**
     * 方法执行成功返回后的处理逻辑
     *
     * @param joinPoint 切点
     */
    @AfterReturning(pointcut = "redisExpirePointcut()")
    public void doAfterReturning(JoinPoint joinPoint) {
        MethodSignature signature  = (MethodSignature) joinPoint.getSignature();
        Method          method     = signature.getMethod();
        String          methodName = method.getName();

        // 获取注解（优先方法上的，其次类上的）
        RedisExpire classAnnotation  = joinPoint.getTarget().getClass().getAnnotation(RedisExpire.class);
        RedisExpire methodAnnotation = method.getAnnotation(RedisExpire.class);

        // 如果方法和类上都没有，理论上切点不会触发，但防御性检查
        if (methodAnnotation == null && classAnnotation == null) {
            return;
        }

        // 最终使用的注解：优先方法，若方法上没有则用类
        RedisExpire redisExpire = methodAnnotation != null ? methodAnnotation : classAnnotation;

        // 校验方法名是否匹配
        if (!isMatch(methodName, redisExpire)) {
            return;
        }

        // 执行过期逻辑
        try {
            executeExpire(joinPoint, redisExpire, classAnnotation);
        } catch (Exception e) {
            log.error("[RedisExpireAspect] 执行 RedisExpire 异常: method={}", methodName, e);
        }
    }

    /**
     * 校验方法名是否满足注解定义的匹配规则
     */
    private boolean isMatch(String methodName, RedisExpire redisExpire) {
        // 排除正则匹配
        for (String pattern : redisExpire.excludePattern()) {
            if (Pattern.matches(pattern, methodName)) {
                return false;
            }
        }

        // 排除值匹配
        for (String value : redisExpire.excludeValue()) {
            if (methodName.contains(value)) {
                return false;
            }
        }

        // 包含正则匹配
        boolean hasIncludePattern = redisExpire.includePattern().length > 0;
        if (hasIncludePattern) {
            for (String pattern : redisExpire.includePattern()) {
                if (Pattern.matches(pattern, methodName)) {
                    return true;
                }
            }
        }

        // 包含值匹配
        boolean hasIncludeValue = redisExpire.includeValue().length > 0;
        if (hasIncludeValue) {
            for (String value : redisExpire.includeValue()) {
                if (methodName.contains(value)) {
                    return true;
                }
            }
        }

        // 如果没有定义包含规则，默认匹配（只要没被排除）
        return !hasIncludePattern && !hasIncludeValue;
    }

    /**
     * 执行具体的过期操作
     */
    private void executeExpire(JoinPoint joinPoint, RedisExpire redisExpire, RedisExpire classExpire) throws Exception {
        String spel = redisExpire.expireMethodSpEL();

        // 1. 如果是完整的 SpEL (以 @ 开头)，直接执行
        if (StringUtils.hasText(spel) && spel.trim().startsWith("@")) {
            evaluateSpel(joinPoint, spel, null);
            log.debug("[RedisExpireAspect] 成功执行完整 SpEL 过期逻辑: spel={}", spel);
            return;
        }

        // 2. 否则，需要确定 serviceBean 和 methodName
        Class<?> serviceClass = redisExpire.redisService();
        if (serviceClass == Void.class && classExpire != null) {
            serviceClass = classExpire.redisService();
        }

        if (serviceClass == Void.class) {
            log.warn("[RedisExpireAspect] RedisExpire 未指定 redisService");
            return;
        }

        // 从上下文获取 Bean
        Object serviceBean = applicationContext.getBean(serviceClass);

        // 确定要调用的方法名
        String expireMethodName = getExpireMethodName(joinPoint, redisExpire, classExpire);
        if (!StringUtils.hasText(expireMethodName)) {
            log.warn("[RedisExpireAspect] RedisExpire 未指定有效的过期方法名");
            return;
        }

        // 调用目标方法（简单起见，目前仅支持无参的过期方法，这也是大多数 Redis 服务的 expireAll 设计）
        Method method = serviceBean.getClass().getMethod(expireMethodName);
        method.invoke(serviceBean);
        log.debug("[RedisExpireAspect] 成功触发 Redis 过期: service={}, method={}", serviceClass.getSimpleName(), expireMethodName);
    }

    /**
     * 解析过期方法名，支持 SpEL
     */
    private String getExpireMethodName(JoinPoint joinPoint, RedisExpire redisExpire, RedisExpire classExpire) {
        String spel = redisExpire.expireMethodSpEL();
        if (StringUtils.hasText(spel)) {
            Object value = evaluateSpel(joinPoint, spel, null);
            return value != null ? value.toString() : null;
        }

        String method = redisExpire.expireMethod();
        if (!StringUtils.hasText(method) && classExpire != null) {
            method = classExpire.expireMethod();
        }
        return method;
    }

    /**
     * 使用 Spring 表达式语言 (SpEL) 解析表达式并返回计算结果。
     *
     * @param joinPoint  方法切点，提供当前方法的上下文信息，包括参数列表等。
     * @param spel       待解析的 SpEL 表达式。
     * @param resultType 返回值的预期类型，如果为 null 则返回原始计算结果。
     * @return 解析后的 SpEL 表达式计算结果，类型可以是指定的 resultType 或原始结果。
     */
    private Object evaluateSpel(JoinPoint joinPoint, String spel, Class<?> resultType) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        // 允许访问 Spring Bean
        context.setBeanResolver(new org.springframework.context.expression.BeanFactoryResolver(applicationContext));

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
}
