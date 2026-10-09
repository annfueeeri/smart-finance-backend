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
`UserManagementController` 的方法分别显式声明 GET `/api/users`、GET `/api/admin/users` 和 PUT `/api/admin/users/{id}/role`；
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

所有业务表统一包含以下字段；当前业务表为 `app_user`、`ledger_account`、`ledger_transaction` 及预算表（见下文），Spring Batch 框架表保留官方结构。

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
当前没有新增删除用户接口。用户一览在原有基本字段之外返回创建、修改审计信息和删除状态，不返回密码哈希。

新数据库执行 `src/main/resources/db/schema-users.sql`。
已有 MySQL 数据库先确认身份字段迁移 V2 已完成，再在启动新版应用前执行一次
`src/main/resources/db/migration/V3__add_user_audit_mysql.sql`；脚本不自动执行。
旧记录的真实创建/修改时间和用户无法从现有数据恢复，因此以迁移时间填充时间字段，
并将创建/修改用户标记为 `LEGACY`，原有主键、账号、密码、身份均保留，默认未删除。
后续新增业务表和写入代码也应遵守以上公共字段及审计规则。

身份保存在 `app_user.role` 中，取值为 `ADMIN`（管理员）或 `USER`（一般用户）。
新注册用户一律为 `USER`，注册请求中传入身份也不能自行获得管理员权限。
注册、登录和当前用户响应为 `{ username, role }`。

前端侧边栏“ユーザー一覧”对管理员和一般用户都显示。
`GET /api/users` 按当前会话的数据库身份限定查询范围：管理员查看全部未删除用户，一般用户只查看自身。
返回用户基本信息、审计字段以及 `displayName`、`email`、`phone`、`currency`、`timezone`、`monthlyBudget`、`budgetStartDay` 的列表，
其中日期时间为数据库本地时间的 ISO 字符串，不带时区偏移；用户一览只返回 `isDeleted=false` 的账号。
管理员可在页面修改其他用户身份，自己的行只读；一般用户没有编辑控件。
身份提升或降级后，原会话的下一次查询立即按新身份决定范围。
兼容接口 `GET /api/admin/users` 仍仅允许管理员访问，并返回同样的用户摘要；
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

Controller 不通过 DAO 或 JdbcTemplate 访问数据库；用户管理 Controller 将业务层返回的内部账号转换为安全响应，SQL 仅出现在 DAO。
`config/SecurityConfig.java` 配置 Spring Security、BCrypt、CSRF 与会话策略。

| 接口 | 行为 |
| --- | --- |
| `GET /api/auth/csrf` | 获取 token、headerName、parameterName；同时保留 JSESSIONID cookie |
| `POST /api/auth/register` | JSON 必填 `{ displayName, username, password, confirmPassword }`，可选联系方式和记账偏好见下文；成功返回 201，不自动登录 |
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


## 完整注册资料与记账偏好

注册表单分为基本资料、联系方式、记账设置和密码设置。登录账号不要求邮箱格式，
支持普通用户名、中文、数字和邮箱形式；姓名/昵称与账号分开保存，允许重名，账号仍须唯一。
联系邮箱不是登录别名，登录始终使用 `username`。

| 接口字段 | 数据库字段 | 规则 |
| --- | --- | --- |
| `displayName` | `display_name` | 必填姓名/昵称，1–80 字符，不允许控制字符或全空白 |
| `username` | `username` | 必填唯一登录账号，1–64 个非空白字符 |
| `email` | `email` | 可选联系邮箱，最多 254 字符，未填写为空字符串 |
| `phone` | `phone` | 可选手机号，填写时 7–32 字符，支持数字、国际区号和常见分隔符 |
| `currency` | `currency` | ISO 4217 默认币种，缺省 JPY |
| `timezone` | `timezone` | IANA 时区，缺省 Asia/Tokyo |
| `monthlyBudget` | `monthly_budget` | 可选月度预算，十进制字符串，不填为数据库 NULL、响应空字符串 |
| `budgetStartDay` | `budget_start_day` | 每月预算周期开始日，1–28，缺省 1 |
| `password`、`confirmPassword` | `password_hash` | 保持原密码约束，数据库仅保存 BCrypt 哈希 |

`POST /api/auth/register` 示例（密码仅为示例，不是内置凭据）：

```json
{
  "displayName": "山田 太郎",
  "username": "taro_2026",
  "email": "taro@example.com",
  "phone": "+81 90-1234-5678",
  "currency": "CNY",
  "timezone": "Asia/Shanghai",
  "monthlyBudget": "5000.50",
  "budgetStartDay": 15,
  "password": "Example-only-ChangeMe-2026",
  "confirmPassword": "Example-only-ChangeMe-2026"
}
```

金额最多 12 位整数，小数位不能超过币种精度（JPY/KRW 为 0，CNY/USD 为 2），
以 BigDecimal 和 DECIMAL 保存，禁止浮点舍入。月度预算可以为 0；空值表示未设置预算。
新增资料和偏好也返回在用户一览中，查询范围继续由管理员/一般用户身份决定。
这些偏好为后续真实记账提供设置；资产总览仍为演示数据，真实收支模块按账户币种校验金额，但不会自动换算金额或生成预算报表。

已有 MySQL 在 V2、V3 完成后执行一次 `src/main/resources/db/migration/V4__add_user_profile_mysql.sql`。
姓名暂回填为原账号，邮箱/手机号为空，偏好使用默认值，原主键、密码、身份及审计时间保持不变。
新安装直接执行 `db/schema-users.sql`。此迁移不自动执行；旧客户端注册请求也必须增加 `displayName`。


### 个人账户、收支与导入导出

侧边栏“収支明細”使用真实数据库记录。先创建个人账户（银行、现金、电子钱包等），再记录收入或支出。
管理员和一般用户均仅可访问自己的财务账户、流水、导入及导出；用户一览的管理员权限不扩展到财务数据。
所有金额请求及 JSON 响应使用十进制字符串；金额必须为正数，最多 12 位整数和 4 位小数，
且小数位不得超过账户币种的最小货币单位（例如 JPY 0 位、CNY 2 位）。没有汇率换算。
收入分类：SALARY 工资、BONUS 奖金、PART_TIME 兼职、INVESTMENT 投资收益、INTEREST 利息、GIFT 红包、OTHER_INCOME 其他收入。
支出分类：FOOD 餐饮、SHOPPING 购物、TRANSPORT 交通、HOUSING 住房、ENTERTAINMENT 娱乐、MEDICAL 医疗、OTHER_EXPENSE 其他支出。
每条流水含账户、日期、分类、可选商家和备注，以及主键、创建/修改时间和用户、逻辑删除字段。

| 方法 | 完整路径 | 用途 |
| --- | --- | --- |
| GET | `/api/ledger/options` | 本人的账户、分类、币种、时区及当地今天 |
| POST | `/api/accounts` | 创建个人账户 |
| POST | `/api/transactions` | 新增个人流水 |
| GET | `/api/transactions` | 按 kind/start/end/accountId 筛选，page 从 0 开始，size 默认 20、最大 100 |
| POST | `/api/transactions/import/inspect` | multipart file 读取标题和前 5 行，不写入 |
| POST | `/api/transactions/import/preview` | multipart file + mapping（JSON 字符串），预览全部行、重复和错误，不写入 |
| POST | `/api/transactions/import` | JSON entries + skipDuplicates 确认原子导入 |
| GET | `/api/transactions/export` | format=csv/xlsx，与列表同样筛选；导出全部筛选行，超过 10,000 行返回 400 |

写入接口必须携带登录后的 CSRF 令牌和会话 Cookie。所有归属及审计用户从服务端当前认证账号获取。
CSV 支持 UTF-8（可带 BOM）；Excel 支持 XLS 和 XLSX，读取第一张工作表，拒绝公式。
首个非空行为表头；文件最多 5MB、500 条非空数据行、30 列。非法文件或映射返回 400。
金额和日期必须映射。其他列可映射，也可选择默认方向、本人账户与分类。
账户列使用账户名称，不会自动创建；同名不同币种账户需映射 currency 列区分。
日期支持 YYYY-MM-DD、YYYY/M/D；Excel 日期单元格也支持。金额须正数，可含规范的逗号千分组。
收支和分类接受机器代码、中日文名称；缺少分类默认“其他收入/支出”。

预览返回各原始记录号、规范化字段、errors 和 duplicate。valid 包含无错误的重复行。
相同账户、方向、金额、日期、分类、商家、备注、标准化标签视为重复，只比较本人未删除流水。
前端在确认时明确排除错误行；后端重新校验整个提交批次，任一提交行无效则整批回滚。
`skipDuplicates` 省略、null 或 true 默认跳过重复；明确 false 允许重复，重复金额不会自动合并。
同一用户的并发导入通过数据库用户行锁串行执行。预览不保证确认时数据状态不变，因此确认会再次查重。
导出 CSV 包含 UTF-8 BOM，并转义以 `= + - @` 或控制字符开头的文本防止公式执行；重新导入会还原该转义。
XLSX 将全部业务数据保存为字符串单元格。导出列 kind,amount,date,account,category,merchant,note,currency,tags 可直接重新映射导入。

已有 MySQL 完成 V2/V3/V4 后，在启动新版应用前执行一次
`src/main/resources/db/migration/V5__create_ledger_mysql.sql`。
新 MySQL 安装按顺序执行 `db/schema-users.sql` 和 `db/schema-ledger.sql`。
迁移不会自动执行；Spring Batch 表保留框架结构。local H2 自动初始化全部业务表，但进程重启仍清空内存数据。
可下载 JSON 接口文档：`docs/smart-finance-openapi.json`，由 `python3 scripts/export-api-doc.py` 从规范 YAML 生成。


### 真实预算管理、结转和站内预警

业务表 `budget_plan`、`budget_adjustment`、`budget_template`、`budget_notification` 均具有
主键、创建/修改时间及用户、`is_deleted`；预算关联、调整及提醒用复合外键保证用户归属一致。
通过认证会话确定所有者，管理员也只能操作自己的预算、模板、历史与通知。
创建/调整/复制/应用模板/标记已读均要求登录后的 CSRF。

| 方法 | 完整路径 | 用途 |
| --- | --- | --- |
| GET / POST | `/api/budgets` | 按日期范围重叠筛选执行列表 / 新建预算 |
| PUT | `/api/budgets/{id}` | 额度、阈值、结转策略调整，理由必填 |
| GET | `/api/budgets/{id}/adjustments` | 调整前后基础额度及结转修改历史 |
| GET / POST | `/api/budget-templates` | 本人常用月度方案 / 保存某月全部月度配置 |
| POST | `/api/budget-templates/{id}/apply` | 原子应用方案到新月份 |
| POST | `/api/budgets/copy-month` | 复制上月或指定月份配置 |
| GET | `/api/budgets/history` | 已结束月度预算、分类执行和超支频次 |
| GET | `/api/budget-notifications` | 最新200条本人站内预警 |
| PUT | `/api/budget-notifications/{id}/read` | 标记本人通知已读 |

`category=TOTAL` 汇总该币种所有支出，其他预算仅允许支出分类。
同用户/分类/币种/周期类型/起止日期唯一；模板应用或复制任一配置冲突会整体回滚，已有预算不覆盖。
额度必须正数十进制字符串，最多12位整数/4位小数并遵循币种最小单位，所有计算采用BigDecimal。
余额为含结转额度减实际消费，可以为负数；超支金额为差额正部，执行率为支出÷可用额度，超支率为超支÷可用额度。
支出来自真实流水，区间闭合、同币种、本人账户且未删除，不计收入；已登记的未来日期消费也属于对应周期支出。
月度按自然月（注册 `budgetStartDay` 可用于手工设定CUSTOM日期，不能隐式改变月度模板周期）；
WEEK为周一至周日，QUARTER为自然季度，YEAR为自然年，CUSTOM为自定义闭区间。
日期前后十年以内，周期最长3661天。注册 `monthlyBudget` 仅作为新预算输入建议，不自动建立预算。

仅MONTH支持 `NONE` / `ONCE` / `CUMULATIVE`：
- ONCE结转 `max(基础金额−当月消费,0)`，收到的旧结转不再次传递；基础金额优先被消费。
- CUMULATIVE结转 `max(基础金额+收到的结转−当月消费,0)`。
- NONE不生成下月；如果之前已结转，切换NONE会清零它带给下一月的结转。

下一月缺少预算时复制其基础配置，已存在时只更新结转；结转来源唯一，不会反复增加余额。
按月份顺序处理整个链，补录历史支出、修改历史金额会修正后续结转并记录前后值。
同用户的写入、导入、预算更新、结转、通知通过用户行锁串行执行。
消费创建在同事务中刷新；批量导入完成后刷新一次；后台每分钟按用户时区结转，即使页面关闭仍写站内消息。
站内预警阈值自定义1–100，最多10个不重复整数；每预算/事件一次（50%、80%、100%各一次，超支单独一次）。
未开始和已结束预算不创建新的阈值提醒；消息保留当时的消费快照，不因后来增加额度而删除。
邮件和推送按照本次选择只预留配置，不实现发送。`budget.notifications.email/push.enabled=false`；
BUDGET_SMTP_HOST/PORT/USERNAME/PASSWORD、BUDGET_EMAIL_FROM、BUDGET_PUSH_PROVIDER_URL/API_KEY仅用于后续接入，
即使填写这些预留变量当前也不会发信或推送，不要把密钥提交到Git。

预测采用截至用户当地今天的日均消费×周期天数，按币种舍入，至少为周期全部已登记消费；
过去周期预测等于实际消费，未来周期没有虚构消费。历史查询from/to为YYYY-MM，默认最近12个结束月份，
只包含已结束MONTH预算；分类统计排除TOTAL，并按分类/币种统计累计预算、支出、超支金额和超支月数。

已有MySQL在V2–V5完成后执行一次 `src/main/resources/db/migration/V6__create_budgets_mysql.sql`。
新安装依次执行schema-users.sql、schema-ledger.sql、schema-budgets.sql，框架表保留原结构。
local H2自动初始化；生产MySQL不自动执行迁移。当前云验证使用H2的MySQL模式，未连接生产MySQL。
接口JSON文档 `docs/smart-finance-openapi.json` 与api.yaml一致，可用于本地对接和字段核对。

### 财务报表与账户基础数据

侧边栏“財務レポート”使用本人真实流水，提供总收入、总支出、净结余、平均日支出，
日/周/月/年零流水补齐趋势、分类占比、最近六个月分类变化、账户现金流、资产/负债/净资产曲线、
账户余额历史、同比环比、商家前20名和标签项目支出。自定义筛选支持日期、账户、币种、收支方向、分类和标签。
各币种独立统计，不进行无汇率换算的加总。所有财务计算使用BigDecimal，接口金额及百分比为字符串。
汇总没有流水下载的10000条限制；默认统计用户当地本月1日至今天；DAY最多366天，其他分组最多3661天。
平均日支出按闭区间实际天数计算并按币种精度四舍五入。

| 方法 | 完整路径 | 用途 |
| --- | --- | --- |
| GET | `/api/reports/options` | 本人已使用的标签 |
| GET | `/api/reports/financial` | 全部统计，参数start/end/grouping/accountId/currency/kind/category/tag |
| GET | `/api/reports/export` | 同一筛选，format=csv/xlsx/pdf；附件含完整明细、摘要及条件 |
| PUT | `/api/accounts/{id}` | 修改本人账户名称及类型 |
| GET / POST | `/api/transfers` | 本人内部转账历史 / 创建同币种不同账户的转账 |
| GET / POST | `/api/accounts/{id}/valuations` | 本人账户日终校准历史 / 追加日终余额或投资市值 |

账户类型BANK/CASH/EWALLET/INVESTMENT/LIABILITY分别表示银行、现金、电子钱包、投资、负债。
`POST /api/accounts`支持type/openingBalance/openingDate：期初值为基准日开始时余额，
该日及以后流水加减；之前流水仍用于收支分析，但不再次加到余额，基准前余额标记known=false。
旧客户端/迁移旧账户默认CASH、0、1900-01-01，仅表示从现有记录累加的余额，不是银行核对余额。
负数余额计入总负债，正数计入资产；借贷元本可通过负债账户与现金账户振替，利息另记支出。
日终估值包含当日及以前的全部活动，之后仅累加次日及以后活动；同日最后id生效，原记录保留。
估值变化计入现金流的balanceAdjustment，不当作所得/消费。账户余额是记账结果，不是外部银行实时查询。
内部转账保存独立记录，不计入收入、支出或预算消费；仅支持本人同币种不同账户。
现金流满足期初+外部流入−外部流出+内部流入−内部流出+余额调整=期末。
账户现金流、资产和余额只应用日期/账户/币种，分类/方向/标签过滤仅作用于收支分析，避免余额失真。

交易新增tags数组，每笔最多10个、每个1–30字符；去首尾空白、去重、排序；禁止控制字符和`| , ， ;`。
省略/null为无标签；CSV/XLSX新增tags列，以`|`分隔，原八列文件仍兼容。重复检测包含标准化标签。
多标签交易在各项目分别计入一次，标签合计可能超过总支出，但总收入/支出不重复。
分类六个月趋势以报表end月份为终点，终点月份只算到end，不受报表start截断。
环比/同比将所选区间按日历回移一个月/一年，整月保持比较月首末日并处理闰年。
变化率=(当前−比较期)/abs(比较期)；比较期为零时返回null而不是无穷大。

CSV含UTF8 BOM和公式文本转义；Excel包含精确字符串明细及原生收支折线、分类饼图、资产折线图。
PDF嵌入OFL中文字体，包含矢量趋势/分类图、摘要和分页完整统计。图形坐标使用浮点，财务金额不经浮点计算。
PDF极少数不支持的字形显示问号，原文字在JSON/CSV/XLSX保留；字体来源及许可见`src/main/resources/fonts/README.md`。

已有MySQL在V2–V6完成后执行一次`src/main/resources/db/migration/V7__add_financial_reports_mysql.sql`，再启动新版。
新安装依次执行schema-users.sql、schema-ledger.sql、schema-budgets.sql、schema-reports.sql，勿再执行旧库增量V7。
新增ledger_transfer、account_valuation均有主键、创建/修改时间及用户、is_deleted及所有者复合外键。
local H2自动初始化所有业务表；生产MySQL需手动迁移，当前验证使用H2 MySQL模式。
可直接在本地查看`docs/smart-finance-openapi.json`，它由规范api.yaml生成并与前端路径、参数一致。
