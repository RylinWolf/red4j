# redis-key-util
本人在编写 Spring Boot 项目时，苦于构建各种 Redis 的键。于是设计了该工具类，用于全局管理和便捷构建 Redis 键。

这是一个基于 Spring Boot 的用于统一管理与生成 Redis 键（key）的实用工具包，支持通过注解与工具类两种方式生成、注册和获取 Redis 键。
同时提供基于 Spring Data Redis 的 `RedisUtil`，封装常用的操作方法。

适用场景：
- 使用 Spring Boot 3.x 开发
- 需要统一的 Redis Key 前缀/分隔符规范；
- 希望通过注解在启动期批量注册键名，避免手写字符串；
- 在代码中以强约束、可读的方式管理 Redis 键。


## 功能特性
- 统一键格式：支持主前缀（prefix）、二级前缀（secondPrefix）、分隔符（separator）
- 注解式注册：
  - 类级注解可声明“键常量类”，扫描所有静态常量并注册
  - 字段级注解可按约定规则拼接键名
- 编程式注册：提供 `RedisKeyUtil` API 进行注册、查询、删除等
- 常用 Redis 操作工具：`RedisUtil` 封装字符串、集合、ZSet、键匹配等方法


## 项目结构

```
redis-key-util
├─ pom.xml
├─ src/main/java/com/wolfhouse/rediskeyutil/
│  ├─ Except.java                      # 用于标记某些常量不参与键注册
│  ├─ RedisKey.java                    # 注解定义
│  ├─ RedisKeyAnnotationProcessor.java # 启动期解析注解并注册键
│  ├─ RedisKeyUtil.java                # 键管理工具类
│  └─ RedisUtil.java                   # Redis 操作工具
└─ target/
   └─ redis-key-util-1.0-SNAPSHOT.jar  # 已构建的发布包（构建后生成）
```


## 快速开始

本项目暂未发布到 Maven 中央仓库，提供 jar 包方式引入。你可以选择以下任一方式：

### 方式 A：直接使用本地 jar（快速验证）

1) 先构建 jar（如果你拿到的是源码）：

```
mvn -DskipTests package
```

2) 在你的应用 `pom.xml` 中声明系统依赖

```xml
<dependency>
  <groupId>com.wolfhouse</groupId>
  <artifactId>redis-key-util</artifactId>
  <version>1.0-SNAPSHOT</version>
  <scope>system</scope>
  <systemPath>${project.basedir}/lib/redis-key-util-1.0-SNAPSHOT.jar</systemPath>
</dependency>
```

将 jar 放到项目的 `lib/` 目录，并与 `systemPath` 保持一致。


### 方式 B：安装到本地 Maven 仓库

如果下载了源码，则在项目中直接执行：

```
mvn clean install -DskipTests
```

然后在你的应用 `pom.xml` 中正常引入：

```xml
<dependency>
  <groupId>com.wolfhouse</groupId>
  <artifactId>redis-key-util</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

如果你有发布好的 jar，可用 `install:install-file`：

```bash
mvn install:install-file \
  -Dfile=/path/to/redis-key-util-1.0-SNAPSHOT.jar \
  -DgroupId=com.wolfhouse \
  -DartifactId=redis-key-util \
  -Dversion=1.0-SNAPSHOT \
  -Dpackaging=jar
```

## Spring Boot 集成

本库包含一个注解处理器 `RedisKeyAnnotationProcessor`（`@Component`），会在启动阶段扫描 `@RedisKey` 注解并调用 `RedisKeyUtil` 完成键注册。因此需要将 `RedisKeyUtil` 声明为 Spring Bean。

示例配置：

```java
@Configuration
public class RedisKeyConfig {
  @Bean
  public RedisKeyUtil redisKeyUtil() {
    // 可自定义默认前缀与分隔符
    return RedisKeyUtil.of("service", ":");
  }
}
```

若使用 `RedisUtil`，需要事先配置 `RedisTemplate<String, Object>`：

```java
@Configuration
public class RedisTemplateConfig {
  @Bean
  public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);
    // 根据需要定制序列化器
    template.afterPropertiesSet();
    return template;
  }

  @Bean
  public RedisUtil redisUtil(RedisTemplate<String, Object> redisTemplate) {
    return new RedisUtil(redisTemplate);
  }
}
```


## 注解用法

`@RedisKey` 可用于：类、字段、方法、参数（本库主要在类/字段上生效）。

关键属性：
- `prefix`：全局主前缀（默认 `service`）, 不建议多次配置
- `secondPrefix`：二级前缀（可选）
- `separator`：分隔符（默认 `:`）
- `name`：自定义名称；当 `asName=true` 时，会与字段名下划线拆分后的片段一起参与拼接
- `asName`：字段名是否参与键名拼接（对类级注解不生效）
- `isKeysConstant`：是否为“键常量类”——扫描所有 `static final String` 字段并注册

### 1) 类级：键常量类

```java
@RedisKey(secondPrefix = "user:cache", isKeysConstant = true)
public class UserKeys {
  public static final String USER_LIST = "userList";   // 键：service:user:cache:user:list
  public static final String USER_INFO = "userInfo";   // 键：service:user:cache:user:info

  @Except
  public static final String IGNORE_THIS = "ignore";   // 被排除，不注册
}
```

规则说明：
- 默认情况下，常量名会按下划线拆分并转为小写作为附加前缀片段，如 `USER_LIST` → `user:list`
- 最终键格式约为：`[prefix]:[secondPrefix]:[常量名片段]`
- 常量“值”作为 `RedisKeyUtil` 的 key，可通过 `getKey("userList")` 获取最终 Redis 键

### 2) 字段级：按字段名与自定义名生成

```java
@RedisKey(prefix = "order", secondPrefix = "cache", separator = ":")
public class OrderService {
  @RedisKey(name = "list", asName = true)
  private final String ORDER_LIST = "orderList"; // 键：order:cache:order:list

  @RedisKey(secondPrefix = "detail", asName = true)
  private final String ORDER_DETAIL = "orderDetail"; // 键：order:cache:detail:order:detail
}
```

字段级约定（见 `RedisKeyAnnotationProcessor#processAnnotatedFields`）：
- 类级 `prefix/separator/secondPrefix` 会作为默认配置
- 字段级 `secondPrefix` 若设置会与类级合并/覆盖
- 当 `asName=true` 时，字段名（按 `_` 拆分并转小写）与 `name` 一起拼接
- 注册用的 “key” 优先取字段值，若为空则退回字段名的小写，并输出 warn 日志


## 编程式 API 用法（RedisKeyUtil）

```java
// 创建实例（或通过 Spring @Bean 注入）
RedisKeyUtil keyUtil = RedisKeyUtil.of("service", ":");

// 可链式设置“二级前缀”，仅影响后续 registerKey 调用
keyUtil.secondaryPrefix("user", "cache"); // → 二级前缀为 "user:cache"

// 1) 注册：使用当前 prefix + secondaryPrefix
keyUtil.registerKey("userList", "user:list");
String k1 = keyUtil.getKey("userList"); // service:user:cache:user:list

// 2) 注册（线程场景推荐）：显式传入二级前缀
keyUtil.registerKeyWithSecPrefix("userInfo", "user:info", "user", "cache");
String k2 = keyUtil.getKey("userInfo"); // service:user:cache:user:info

// 3) 注册：显式传入完整前缀
keyUtil.registerKeyWithPrefix("orderList", "order:list", "biz:order");
String k3 = keyUtil.getKey("orderList"); // biz:order:order:list

// 查询、删除
keyUtil.getKeys();        // 所有已注册 key（逻辑键）
keyUtil.getKeyMap();      // 逻辑键 → Redis 实际键 的映射表
keyUtil.removeKey("userList");
keyUtil.clear();
```


## Redis 操作工具（RedisUtil）

`RedisUtil` 基于 Spring Data Redis 的 `RedisTemplate<String, Object>`，常用方法示例：

```java
// value
redisUtil.setValue("counter", 1);
redisUtil.getAndIncrease("counter", 2);      // 自增
redisUtil.getValueAndExpire("token", Duration.ofMinutes(30));

// set
redisUtil.addSetValue("set:k", "a", "b");
redisUtil.isSetValueMember("set:k", "a");

// zset
redisUtil.addZSetValue("rank", "u1", 100);
redisUtil.incrementZSetValue("rank", "u1", 5);

// 键匹配（游标扫描）
Set<String> keys = redisUtil.keysMatch("user:*", 1000);
```


## 注意事项与最佳实践
- 建议在应用启动早期就初始化 `RedisKeyUtil`，以便注解处理器能够正确注册所有键
- 生产环境中优先使用“方式 B：安装到本地 Maven 仓库”，避免 `systemPath` 方式
- 统一在一个（或少数几个）“键常量类”中集中声明常量，便于维护
- 调整 `prefix/secondPrefix/separator` 时请确保与历史数据兼容
- 避免在多线程场景频繁修改 `RedisKeyUtil` 的 `secondaryPrefix`；需要并发安全时使用 `registerKeyWithSecPrefix`
- 不建议多次配置 `prefix` 和 `separator` 参数，这两个参数是全局的
