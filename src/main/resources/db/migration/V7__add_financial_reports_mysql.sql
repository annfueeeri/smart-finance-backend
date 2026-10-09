-- 在 V2-V6 完成后执行一次；原有账户以零余额、1900年起点兼容历史流水。
ALTER TABLE ledger_account ADD COLUMN account_type VARCHAR(16) NOT NULL DEFAULT 'CASH',
    ADD COLUMN opening_balance DECIMAL(22,4) NOT NULL DEFAULT 0,
    ADD COLUMN opening_date DATE NOT NULL DEFAULT '1900-01-01';
ALTER TABLE ledger_transaction ADD COLUMN tags_json VARCHAR(1024) NOT NULL DEFAULT '[]';
-- 所有新增业务表保留统一审计和逻辑删除字段。
CREATE TABLE IF NOT EXISTS ledger_transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    from_account_id BIGINT NOT NULL,
    to_account_id BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount DECIMAL(18,4) NOT NULL,
    transfer_date DATE NOT NULL,
    note VARCHAR(1000) NOT NULL DEFAULT '',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by VARCHAR(64) NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_transfer_from_owner FOREIGN KEY (from_account_id,user_id) REFERENCES ledger_account(id,user_id),
    CONSTRAINT fk_transfer_to_owner FOREIGN KEY (to_account_id,user_id) REFERENCES ledger_account(id,user_id),
    CONSTRAINT chk_transfer_accounts CHECK (from_account_id<>to_account_id AND amount>0)
);
CREATE TABLE IF NOT EXISTS account_valuation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    valuation_date DATE NOT NULL,
    balance DECIMAL(22,4) NOT NULL,
    note VARCHAR(1000) NOT NULL DEFAULT '',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by VARCHAR(64) NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_valuation_account_owner FOREIGN KEY (account_id,user_id) REFERENCES ledger_account(id,user_id)
);
