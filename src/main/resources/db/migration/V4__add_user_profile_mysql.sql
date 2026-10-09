-- 已有 MySQL 在 V2、V3 完成后执行一次；新安装直接使用 schema-users.sql。
-- 旧数据没有姓名，暂以登录账号填充；不覆盖原账号、密码、权限或历史审计信息。
-- 姓名回填时显式保留 updated_at，避免 ON UPDATE 改变历史修改时间。
ALTER TABLE app_user ADD COLUMN display_name VARCHAR(80);
ALTER TABLE app_user ADD COLUMN email VARCHAR(254) NOT NULL DEFAULT '';
ALTER TABLE app_user ADD COLUMN phone VARCHAR(32) NOT NULL DEFAULT '';
UPDATE app_user SET display_name = username, updated_at = updated_at;
ALTER TABLE app_user MODIFY COLUMN display_name VARCHAR(80) NOT NULL;
ALTER TABLE app_user ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'JPY';
ALTER TABLE app_user ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Tokyo';
ALTER TABLE app_user ADD COLUMN monthly_budget DECIMAL(18,4);
ALTER TABLE app_user ADD COLUMN budget_start_day INTEGER NOT NULL DEFAULT 1;
ALTER TABLE app_user ADD CONSTRAINT chk_app_user_budget CHECK (monthly_budget IS NULL OR monthly_budget >= 0);
ALTER TABLE app_user ADD CONSTRAINT chk_app_user_budget_day CHECK (budget_start_day BETWEEN 1 AND 28);
