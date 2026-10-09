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
Controller 层也显式声明路由，便于直接查看：`AuthController` 的公共前缀为 `/api/auth`，
方法使用 `@GetMapping` 或 `@PostMapping` 声明 `/csrf`、`/register`、`/login`、`/me`、`/logout`；
`UserManagementController` 的前缀为 `/api/admin/users`，声明 GET 用户列表和 PUT `/{id}/role`；
`HealthController` 声明 GET `/api/health`。完整路径由类前缀与方法路径组成。
Controller 仍实现生成接口，参数绑定和校验约束来自接口定义。
修改路径时需同时更新 OpenAPI 定义、JSON 文档及前端 `src/api/endpoints.ts`。
模板目录已预留；未提供自定义模板时使用生成器默认模板。
生成器启用 `useSpringBoot3` 以使用 Jakarta 命名空间；编译目标为 Java 17。
添加 H2 runtime 依赖仅为无需外部服务即可启动初始项目，其余依赖版本沿用提供的 POM。

## MySQL

设置 `SPRING_PROFILES_ACTIVE=mysql`、`DB_URL`（例如 `jdbc:mysql://localhost:3306/smart_finance`）、
`DB_USERNAME` 和 `DB_PASSWORD` 后运行。不要把密码提交到 Git。
MySQL profile 不自动创建业务表或 Batch 表；需在数据库预先应用所需 schema。
MinIO 客户端依赖已包含；初始项目没有对象存储业务，因此无需 MinIO 服务即可运行。

## 用户身份与权限

### 业务表公共字段

所有业务表统一包含以下字段；当前业务表为 `app_user`，Spring Batch 框架表保留官方结构。

| 字段 | 含义 | 规则 |
| --- | --- | --- |
| `id` | 主键 | 自增 BIGINT，保留现有主键 |
| `created_at` | 创建时间 | 数据库生成，微秒精度，不因后续修改而改变 |
| `created_by` | 创建用户 | 注册时为注册用户名，本地初始化为 `SYSTEM` |
| `updated_at` | 修改时间 | 创建时初始化，修改身份时由数据库刷新 |
| `updated_by` | 修改用户 | 创建时与创建用户一致，修改身份时记录已认证的实际管理员 |
| `is_deleted` | 是否删除 | 默认 `false`，`true` 表示逻辑删除 |

审计用户由后端确定，客户端不能通过请求字段伪造。时间使用数据库服务器的时区。
逻辑删除的账号不能登录，已有 Session 在下一次请求失效，也不出现在用户列表中；
身份修改按用户不存在处理，已删除管理员不计入最后管理员保护的数量。
已删除账号仍占用原用户名，避免新账号继承原账号身份。此变更补充字段及读取规则，
当前没有新增删除用户接口。管理员列表接口的现有响应字段保持兼容。

新数据库执行 `src/main/resources/db/schema-users.sql`。
已有 MySQL 数据库先确认身份字段迁移 V2 已完成，再在启动新版应用前执行一次
`src/main/resources/db/migration/V3__add_user_audit_mysql.sql`；脚本不自动执行。
旧记录的真实创建/修改时间和用户无法从现有数据恢复，因此以迁移时间填充时间字段，
并将创建/修改用户标记为 `LEGACY`，原有主键、账号、密码、身份均保留，默认未删除。
后续新增业务表和写入代码也应遵守以上公共字段及审计规则。

身份保存在 `app_user.role` 中，取值为 `ADMIN`（管理员）或 `USER`（一般用户）。
新注册用户一律为 `USER`，注册请求中传入身份也不能自行获得管理员权限。
注册、登录和当前用户响应为 `{ username, role }`。

管理员在前端“ユーザー管理”页面查看用户，选择身份后点击保存。
`GET /api/admin/users` 返回 `{ id, username, role, enabled }` 列表；
`PUT /api/admin/users/{id}/role` 接收 `{ "role": "ADMIN" }` 或 `{ "role": "USER" }`，
要求登录 Cookie 和 CSRF token。未登录返回 401，一般用户返回 403。
列表和修改结果不包含密码或密码哈希。
每次认证请求都会重新读取数据库身份，降级后旧 Session 也不再拥有管理员权限。
最后一个启用的管理员不能被降级，返回 409 和 `LAST_ADMIN`；并发修改也受此限制。

### 首个管理员

没有固定管理员账号。`local` profile 下，按“本地测试账号”一节安全设置
`LOGIN_BOOTSTRAP_USERNAME` 和 `LOGIN_BOOTSTRAP_PASSWORD`，再运行：

```sh
LOGIN_BOOTSTRAP_ROLE=ADMIN mvn spring-boot:run
```

该设置只影响新创建的本地初始化账号，不会覆盖已有账号的密码或身份。
未指定时默认 `USER`。本地 H2 内存数据库的账号和身份在后端重启后消失。

MySQL 新安装使用 `src/main/resources/db/schema-users.sql` 创建用户表。
已有用户表必须在部署前**执行一次**
`src/main/resources/db/migration/V2__add_user_role_mysql.sql`；此脚本不自动执行，
请按现有数据库变更流程应用。迁移给原有账号赋予 `USER`，不会丢失账号或密码哈希。
由可信数据库管理员为指定的已有账号初始化管理员身份，例如将下面的占位用户名替换后执行：

```sql
UPDATE app_user
SET role = 'ADMIN', updated_at = CURRENT_TIMESTAMP(6), updated_by = 'SYSTEM'
WHERE username = '指定的管理员用户名' AND enabled = TRUE AND is_deleted = FALSE;
```

初始化完成后，其余身份修改通过管理员页面进行。当前云环境验证使用 H2，未连接你的 MySQL。

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
| `POST /api/auth/register` | JSON `{ username, password, confirmPassword }`；成功返回 201 和用户名，不自动登录 |
| `POST /api/auth/login` | JSON `{ "username": "你的用户名", "password": "你的密码" }`；成功返回用户名并更换 Session ID |
| `GET /api/auth/me` | 携带登录后的 cookie 获取当前用户名；未登录返回 401 |
| `POST /api/auth/logout` | 携带 cookie 与有效 CSRF token，销毁 Session，返回 204 |

注册、登录和退出请求都必须携带 `/api/auth/csrf` 返回的 token（请求头名为 `X-CSRF-TOKEN`）。
登录成功会清除旧 CSRF token，因此后续 POST 前要重新获取。
前端同源请求使用 `credentials: 'same-origin'` 保存 cookie；用户名和密码放在请求体中，不能放在 URL 中。
未提供或提供其他 Session 的 CSRF token 返回 403；输入不合法返回 400；
密码错误、账号不存在及禁用账号统一返回 401 和 `INVALID_CREDENTIALS`。

除健康检查、注册、登录、CSRF、API 文档外，应用路由默认要求登录。
Session 空闲 30 分钟过期；cookie 为 HttpOnly、SameSite=Strict。
生产部署须使用 HTTPS，并设置 `SESSION_COOKIE_SECURE=true`；多实例部署需要共享 Session 或负载均衡会话粘滞。
当前实现未包含密码找回、多因素认证或登录/注册限流。

### 注册账号

前端首页和登录页的登录按钮下方提供“新規登録”入口，注册页为 `#/register`。
注册字段为用户名、密码、确认密码。用户名为 1–64 个非空白字符；密码至少 8 个字符、
最多 72 个 UTF-8 字节且不能全为空白，确认密码必须与密码完全一致。
后端使用 BCrypt 哈希保存密码，不保存确认密码，也不会在响应中返回密码。
注册成功后需要登录；重复用户名返回 409 和 `USERNAME_TAKEN`，不会覆盖或启用已有账号。
数据库唯一约束保证并发注册不会创建重复账号。

默认 `local` profile 下，注册账号也保存在内存 H2 中，后端重启后会消失。
需要持久保存账号时使用上文的 MySQL profile，并先创建用户表。

### JSON 接口文档

可下载的 OpenAPI 3.0.3 文档位于 [`docs/smart-finance-openapi.json`](docs/smart-finance-openapi.json)，
包含健康检查、CSRF、注册、登录、当前用户和退出接口的请求字段、状态码和认证要求。
可以导入支持 OpenAPI 的接口工具；文档不包含真实凭据。

`src/main/resources/api.yaml` 是接口和代码生成的定义来源。修改后执行：

```sh
# Python 3 + PyYAML（当前云环境已提供）
python3 scripts/export-api-doc.py
mvn verify
```

验证会检查 JSON 与 YAML 一致，避免导出的文档过期。

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

## 与前端联调

前端仓库：[smart-finance-frontend](https://github.com/annfueeeri/smart-finance-frontend)。
登录页统一发送 JSON `{ username, password }`；原来的 email 演示字段已改为 username。
邮箱形式的账号也可以作为 username 使用，但需要事先在数据库中创建。

先按上文初始化本地账号并启动后端，然后在前端执行 `npm ci` 和 `npm run dev`。
前端的 `src/api/auth.ts` 统一处理 CSRF、登录、当前用户和退出请求。
Vite 默认将 `/api` 代理到后端 8080；本云环境后端使用 18080 时，前端启动命令为：

```sh
BACKEND_URL=http://127.0.0.1:18080 npm run dev
```

浏览器始终请求前端网站的 `/api`，Cookie 和 CSRF 保持同源。
生产部署同样应反向代理 `/api`，并在 HTTPS 下设置 `SESSION_COOKIE_SECURE=true`。
前后端保持两个独立仓库，云环境中可以一起启动和测试。
