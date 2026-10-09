-- 已有 MySQL 用户表执行一次；先完成 V2 身份字段迁移，再执行本脚本。
-- 新安装直接使用 db/schema-users.sql，无需执行 V2/V3。
-- 原有 id 主键保持不变；原有账号默认未删除。
-- 旧数据没有历史审计信息，因此时间填迁移时间，用户标记 LEGACY。
ALTER TABLE app_user ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_user ADD COLUMN created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
ALTER TABLE app_user ADD COLUMN created_by VARCHAR(64) NOT NULL DEFAULT 'SYSTEM';
ALTER TABLE app_user ADD COLUMN updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6);
ALTER TABLE app_user ADD COLUMN updated_by VARCHAR(64) NOT NULL DEFAULT 'SYSTEM';

UPDATE app_user
SET created_by = 'LEGACY', updated_by = 'LEGACY';
