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
