-- MySQL 8：在 V2–V7 完成后执行一次。先备份数据库，并停止应用写入；DDL 会隐式提交。
-- 保留全部个人记录及审计。正账本 ID 沿用用户 ID，家庭使用负 ID；不自动分享个人数据。
-- 家庭管理员独立于系统 ADMIN 身份；一个用户最多加入一个有效家庭。
CREATE TABLE IF NOT EXISTS family_group (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(80) NOT NULL,
 currency VARCHAR(3) NOT NULL,
 timezone VARCHAR(64) NOT NULL,
 owner_user_id BIGINT NOT NULL,
 invitation_hash VARCHAR(64) NOT NULL UNIQUE,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 created_by VARCHAR(64) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 updated_by VARCHAR(64) NOT NULL,
 is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
 CONSTRAINT fk_family_owner FOREIGN KEY(owner_user_id) REFERENCES app_user(id)
);
CREATE TABLE IF NOT EXISTS family_member (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 family_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 active_user_id BIGINT UNIQUE,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 created_by VARCHAR(64) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 updated_by VARCHAR(64) NOT NULL,
 is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
 CONSTRAINT fk_family_member_group FOREIGN KEY(family_id) REFERENCES family_group(id),
 CONSTRAINT fk_family_member_user FOREIGN KEY(user_id) REFERENCES app_user(id) ON DELETE CASCADE,
 CONSTRAINT chk_family_member_active CHECK ((is_deleted=TRUE AND active_user_id IS NULL) OR (is_deleted=FALSE AND active_user_id IS NOT NULL AND active_user_id=user_id))
);
-- 正 ID 保留旧个人归属，负 ID 专用于家庭，两个空间永不冲突。
CREATE TABLE IF NOT EXISTS ledger_book (
 id BIGINT PRIMARY KEY,
 owner_user_id BIGINT UNIQUE,
 family_id BIGINT UNIQUE,
 currency VARCHAR(3) NOT NULL,
 timezone VARCHAR(64) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 created_by VARCHAR(64) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 updated_by VARCHAR(64) NOT NULL,
 is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
 CONSTRAINT fk_book_user FOREIGN KEY(owner_user_id) REFERENCES app_user(id) ON DELETE CASCADE,
 CONSTRAINT fk_book_family FOREIGN KEY(family_id) REFERENCES family_group(id),
 CONSTRAINT chk_book_scope CHECK ((owner_user_id IS NOT NULL AND family_id IS NULL AND id=owner_user_id) OR (owner_user_id IS NULL AND family_id IS NOT NULL AND id=-family_id))
);
CREATE TABLE IF NOT EXISTS book_preference (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL UNIQUE,
 scope VARCHAR(16) NOT NULL DEFAULT 'PERSONAL',
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 created_by VARCHAR(64) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 updated_by VARCHAR(64) NOT NULL,
 is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
 CONSTRAINT fk_book_preference_user FOREIGN KEY(user_id) REFERENCES app_user(id) ON DELETE CASCADE,
 CONSTRAINT chk_book_preference_scope CHECK(scope IN('PERSONAL','FAMILY'))
);

INSERT INTO ledger_book(id,owner_user_id,currency,timezone,created_at,created_by,updated_at,updated_by,is_deleted)
 SELECT id,id,currency,timezone,created_at,created_by,updated_at,updated_by,FALSE FROM app_user;

ALTER TABLE ledger_account DROP FOREIGN KEY fk_ledger_account_user;
ALTER TABLE ledger_transaction DROP FOREIGN KEY fk_ledger_transaction_user;
ALTER TABLE ledger_transaction DROP FOREIGN KEY fk_ledger_transaction_account;
ALTER TABLE budget_plan DROP FOREIGN KEY fk_budget_user;
ALTER TABLE budget_plan DROP FOREIGN KEY fk_budget_carry_owner;
ALTER TABLE budget_adjustment DROP FOREIGN KEY fk_budget_adjustment_owner;
ALTER TABLE budget_template DROP FOREIGN KEY fk_budget_template_user;
ALTER TABLE budget_notification DROP FOREIGN KEY fk_budget_notification_owner;
ALTER TABLE ledger_transfer DROP FOREIGN KEY fk_transfer_from_owner;
ALTER TABLE ledger_transfer DROP FOREIGN KEY fk_transfer_to_owner;
ALTER TABLE account_valuation DROP FOREIGN KEY fk_valuation_account_owner;

ALTER TABLE ledger_account RENAME COLUMN user_id TO book_id;
ALTER TABLE ledger_transaction RENAME COLUMN user_id TO book_id;
ALTER TABLE budget_plan RENAME COLUMN user_id TO book_id;
ALTER TABLE budget_adjustment RENAME COLUMN user_id TO book_id;
ALTER TABLE budget_template RENAME COLUMN user_id TO book_id;
ALTER TABLE budget_notification RENAME COLUMN user_id TO book_id;
ALTER TABLE ledger_transfer RENAME COLUMN user_id TO book_id;
ALTER TABLE account_valuation RENAME COLUMN user_id TO book_id;

ALTER TABLE ledger_account ADD CONSTRAINT fk_ledger_account_user FOREIGN KEY (book_id) REFERENCES ledger_book(id);
ALTER TABLE ledger_transaction ADD CONSTRAINT fk_ledger_transaction_user FOREIGN KEY (book_id) REFERENCES ledger_book(id);
ALTER TABLE ledger_transaction ADD CONSTRAINT fk_ledger_transaction_account FOREIGN KEY (account_id, book_id) REFERENCES ledger_account(id, book_id);
ALTER TABLE budget_plan ADD CONSTRAINT fk_budget_user FOREIGN KEY (book_id) REFERENCES ledger_book(id);
ALTER TABLE budget_plan ADD CONSTRAINT fk_budget_carry_owner FOREIGN KEY (carry_from_id,book_id) REFERENCES budget_plan(id,book_id);
ALTER TABLE budget_adjustment ADD CONSTRAINT fk_budget_adjustment_owner FOREIGN KEY (budget_id,book_id) REFERENCES budget_plan(id,book_id);
ALTER TABLE budget_template ADD CONSTRAINT fk_budget_template_user FOREIGN KEY (book_id) REFERENCES ledger_book(id);
ALTER TABLE budget_notification ADD CONSTRAINT fk_budget_notification_owner FOREIGN KEY (budget_id,book_id) REFERENCES budget_plan(id,book_id);
ALTER TABLE ledger_transfer ADD CONSTRAINT fk_transfer_from_owner FOREIGN KEY (from_account_id,book_id) REFERENCES ledger_account(id,book_id);
ALTER TABLE ledger_transfer ADD CONSTRAINT fk_transfer_to_owner FOREIGN KEY (to_account_id,book_id) REFERENCES ledger_account(id,book_id);
ALTER TABLE account_valuation ADD CONSTRAINT fk_valuation_account_owner FOREIGN KEY (account_id,book_id) REFERENCES ledger_account(id,book_id);
