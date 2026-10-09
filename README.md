# Smart Finance Backend

Java 17 / Spring Boot 3.3.2 项目，依赖基于提供的 POM。

## 本地开发

需要 JDK 17 和 Maven 3.9.x。

```sh
mvn verify
mvn spring-boot:run
```

默认 `local` profile 使用内存 H2（MySQL 兼容模式），无需数据库密码；进程退出后数据不保留。
`GET /api/health` 返回 `{"status":"UP"}`。此接口仅检查 HTTP 服务，不代表外部服务健康。

OpenAPI 定义位于 `src/main/resources/api.yaml`，接口在构建时生成到 `target/generated-sources/openapi`。
模板目录已预留；未提供自定义模板时使用生成器默认模板。
生成器启用 `useSpringBoot3` 以使用 Jakarta 命名空间；编译目标为 Java 17。
添加 H2 runtime 依赖仅为无需外部服务即可启动初始项目，其余依赖版本沿用提供的 POM。

## MySQL

设置 `SPRING_PROFILES_ACTIVE=mysql`、`DB_URL`（例如 `jdbc:mysql://localhost:3306/smart_finance`）、
`DB_USERNAME` 和 `DB_PASSWORD` 后运行。不要把密码提交到 Git。
MySQL profile 不自动创建业务表或 Batch 表；需在数据库预先应用所需 schema。
MinIO 客户端依赖已包含；初始项目没有对象存储业务，因此无需 MinIO 服务即可运行。

## 登录与三层结构

登录使用数据库账号、BCrypt 密码哈希和服务端 Session，不返回密码或密码哈希。

| 层 | 代码 | 职责 |
| --- | --- | --- |
| Controller | `controller/AuthController.java` | 实现生成的 AuthApi，接收请求、调用 Service、处理 Session 与响应 |
| Service | `service/AuthService.java`、`service/impl/AuthServiceImpl.java`、`service/impl/DatabaseUserDetailsService.java` | 认证、密码与账号状态检查，通过 UserDao 加载账号 |
| DAO | `dao/UserDao.java`、`dao/impl/JdbcUserDao.java` | 参数化 SQL 查询和插入用户 |

Controller 不引用 DAO、JdbcTemplate 或用户实体；SQL 仅出现在 DAO。
`config/SecurityConfig.java` 配置 Spring Security、BCrypt、CSRF 与会话策略。

| 接口 | 行为 |
| --- | --- |
| `GET /api/auth/csrf` | 获取 token、headerName、parameterName；同时保留 JSESSIONID cookie |
| `POST /api/auth/login` | JSON `{ "username": "你的用户名", "password": "你的密码" }`；成功返回用户名并更换 Session ID |
| `GET /api/auth/me` | 携带登录后的 cookie 获取当前用户名；未登录返回 401 |
| `POST /api/auth/logout` | 携带 cookie 与有效 CSRF token，销毁 Session，返回 204 |

登录和退出请求都必须携带 `/api/auth/csrf` 返回的 token（请求头名为 `X-CSRF-TOKEN`）。
登录成功会清除旧 CSRF token，因此后续 POST 前要重新获取。
前端同源请求使用 `credentials: 'same-origin'` 保存 cookie；用户名和密码放在请求体中，不能放在 URL 中。
未提供或提供其他 Session 的 CSRF token 返回 403；输入不合法返回 400；
密码错误、账号不存在及禁用账号统一返回 401 和 `INVALID_CREDENTIALS`。

除健康检查、登录、CSRF、API 文档外，应用路由默认要求登录。
Session 空闲 30 分钟过期；cookie 为 HttpOnly、SameSite=Strict。
生产部署须使用 HTTPS，并设置 `SESSION_COOKIE_SECURE=true`；多实例部署需要共享 Session 或负载均衡会话粘滞。
当前实现未包含注册、密码找回、多因素认证或登录限流。

### 本地测试账号

没有固定的演示账号或密码。仅 `local` profile 可以通过环境变量按需创建一个账号：

```sh
read -rp '本地用户名: ' LOGIN_BOOTSTRAP_USERNAME
read -rsp '本地密码: ' LOGIN_BOOTSTRAP_PASSWORD
echo
export LOGIN_BOOTSTRAP_USERNAME LOGIN_BOOTSTRAP_PASSWORD
mvn spring-boot:run
unset LOGIN_BOOTSTRAP_USERNAME LOGIN_BOOTSTRAP_PASSWORD
```

用户名为 1–64 个非空白字符，密码至少 8 个字符且不超过 72 个 UTF-8 字节（BCrypt 限制）。
数据库只保存 BCrypt 哈希；已有同名账号不会被覆盖。默认 H2 是内存数据库，重新启动需重新创建账号。
这两个环境变量仅用于本地启动，不要在 Git、日志或聊天中填写真实密码。

MySQL 环境请先应用 `src/main/resources/db/schema-users.sql`，并由可信的账号管理流程
向 `app_user` 写入用户名、BCrypt 哈希和 enabled 状态。`mysql` profile 不运行本地账号初始化器。
Spring Batch 所需表仍需另行准备。

### 验证

`mvn verify` 包含真实 HTTP 与数据库集成测试，覆盖登录、Cookie / Session 更换、当前用户、退出、
未知 / 禁用账号、输入验证、CSRF 和 SQL 注入输入。OpenAPI 的认证接口和模型仍由 `api.yaml` 构建生成。
