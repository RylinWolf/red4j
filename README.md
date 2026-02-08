# red4j

本人在编写 Spring Boot 项目时，苦于构建各种 Redis 的键。于是设计了该工具类，用于全局管理和便捷构建 Redis 键。
此外，为方便进行 Redis 键过期和自动缓存更新，提供了注解式清理/更新功能。

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
- 自动缓存清理：提供 `@RedisExpire` 注解，支持方法名正则/关键字匹配触发指定 Service 的清理操作
- 自动缓存更新：提供 `@RedisUpdate` 注解，支持方法执行成功后根据返回值或特定参数触发指定 Service 的更新操作

## 项目结构

```
redis-key-util
├─ pom.xml
├─ src/main/java/com/wolfhouse/red4j/
│  ├─ expire/
│  │  ├─ RedisExpire.java              # 自动过期注解
│  │  └─ RedisExpireAspect.java        # 过期处理切面
│  ├─ update/
│  │  ├─ RedisUpdate.java              # 自动更新注解
│  │  ├─ Redata.java                   # 参数更新注解
│  │  └─ RedisUpdateAspect.java        # 更新处理切面
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
    <version>2.1-SNAPSHOT</version>
    <scope>system</scope>
    <systemPath>${project.basedir}/lib/redis-key-util-2.1-SNAPSHOT.jar</systemPath>
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
    <version>2.1-SNAPSHOT</version>
</dependency>
```

如果你有发布好的 jar，可用 `install:install-file`：

```bash
mvn install:install-file \
  -Dfile=/path/to/redis-key-util-2.1-SNAPSHOT.jar \
  -DgroupId=com.wolfhouse \
  -DartifactId=redis-key-util \
  -Dversion=2.1-SNAPSHOT \
  -Dpackaging=jar
```

## Spring Boot 集成

本库包含一个注解处理器 `RedisKeyAnnotationProcessor`（`@Component`），会在启动阶段扫描 `@RedisKey` 注解并调用
`RedisKeyUtil` 完成键注册。因此需要将 `RedisKeyUtil` 声明为 Spring Bean。

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
- `inheritPrefix`：是否继承类级注解的前缀配置（默认为 `true`；仅在字段级注解中使用，且所属类也使用了 `@RedisKey` 时有效）
- `isKeysConstant`：是否为“键常量类”——扫描所有 `static final String` 字段并注册

### 1) 类级：键常量类

```java

@Component
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

@Component
@RedisKey(prefix = "order", secondPrefix = "cache", separator = ":")
public class OrderService {
    @RedisKey(name = "list", asName = true)
    private final String ORDER_LIST = "orderList"; // 键：order:cache:order:list:list

    @RedisKey(secondPrefix = "detail", asName = true)
    private final String ORDER_DETAIL = "orderDetail"; // 键：order:cache:detail:order:detail
}
```

字段级约定（见 `RedisKeyAnnotationProcessor#processAnnotatedFields`）：

- 类级 `prefix/separator/secondPrefix` 会作为默认配置。
- 当 `inheritPrefix = true` (默认) 时，字段将继承类级定义的 `prefix` 和 `separator`。
- 字段级 `secondPrefix` 若设置会覆盖类级的 `secondPrefix`；若未设置且 `inheritPrefix = true`，则继承类级的 `secondPrefix`。
- 当 `inheritPrefix = false` 时，字段将完全使用其自身注解定义的配置（或使用 `RedisKeyUtil` 的全局默认配置）。
- 当 `asName=true` 时，字段名（按 `_` 拆分并转小写）与 `name` 一起拼接。
- 注册用的 “key” 优先取字段值，若为空则退回字段名的小写，并输出 warn 日志。

## 编程式 API 用法（RedisKeyUtil）

```java
void main() {
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
}
```

## Redis 操作工具（RedisUtil）

`RedisUtil` 基于 Spring Data Redis 的 `RedisTemplate<String, Object>`，常用方法示例：

```java
// value
void main() {
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
}
```

## 自动缓存清理（@RedisExpire）

当某些业务方法（如新增、修改、删除）执行成功后，往往需要清理相关的 Redis 缓存。`@RedisExpire` 注解可以自动化这一过程。

### 配置与用法

1. **标注在类上**：该类下的所有方法执行成功后都会尝试触发清理。
2. **标注在方法上**：仅该方法触发。

```java

@Service
@RedisExpire(redisService = UserRedisService.class) // 默认触发 UserRedisService.expireAll()
public class UserServiceImpl implements UserService {

    public void addUser(User user)    { ...}    // 匹配默认 includeValue "add"，触发

    public void updateUser(User user) { ...} // 匹配默认 includeValue "update"，触发

    public void deleteUser(Long id)   { ...}   // 匹配默认 includeValue "delete"，触发

    public void getUser(Long id)      { ...}      // 不匹配，不触发
}
```

### 关键属性

- `redisService`：指定要调用的 Redis 服务类。
- `expireMethod`：指定清理方法名（默认 `expireAll`）。
- `includeValue/excludeValue`：方法名关键字匹配。
- `includePattern/excludePattern`：方法名正则匹配。
- `expireMethodSpEL`：支持使用 SpEL 表达式动态确定方法名或直接执行逻辑。

## 自动缓存更新（@RedisUpdate）

在更新数据库后，可能需要同步更新 Redis 中的某个特定值。

### 用法示例

```java

@Service
public class UserServiceImpl implements UserService {

    // 默认将方法返回值作为参数传递给 UserRedisService.update(result)
    @RedisUpdate(redisService = UserRedisService.class)
    public User updateUserInfo(User user) {
        ...
        return user;
    }

    // 使用 @Redata 显式标识要更新到 Redis 的参数
    @RedisUpdate(redisService = UserRedisService.class, updateMethod = "saveToCache")
    public void saveUser(@Redata User user, String otherParam) {
        ...
    }
}
```

### 关键属性

- `redisService`：指定 Redis 服务类。
- `updateMethod`：更新方法名（默认 `update`）。
- `updateMethodSpEL`：支持 SpEL。
- `@Redata`：用于方法参数，标识该参数作为更新数据。若不标注，则默认使用方法返回值。

## 注意事项与最佳实践

- 建议在应用启动早期就初始化 `RedisKeyUtil`，以便注解处理器能够正确注册所有键
- 生产环境中优先使用“方式 B：安装到本地 Maven 仓库”，避免 `systemPath` 方式
- 统一在一个（或少数几个）“键常量类”中集中声明常量，便于维护
- 调整 `prefix/secondPrefix/separator` 时请确保与历史数据兼容
- 避免在多线程场景频繁修改 `RedisKeyUtil` 的 `secondaryPrefix`；需要并发安全时使用 `registerKeyWithSecPrefix`
- 不建议多次配置 `prefix` 和 `separator` 参数，这两个参数是全局的
