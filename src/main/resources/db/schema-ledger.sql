-- 业务表统一包含主键、创建/修改时间与用户，以及逻辑删除字段。
CREATE TABLE IF NOT EXISTS ledger_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by VARCHAR(64) NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_ledger_account_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT uq_ledger_account_name UNIQUE (user_id, name, currency),
    CONSTRAINT uq_ledger_account_owner UNIQUE (id, user_id)
);
CREATE TABLE IF NOT EXISTS ledger_transaction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    amount DECIMAL(18,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    transaction_date DATE NOT NULL,
    category VARCHAR(32) NOT NULL,
    merchant VARCHAR(120) NOT NULL DEFAULT '',
    note VARCHAR(1000) NOT NULL DEFAULT '',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by VARCHAR(64) NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_ledger_transaction_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_ledger_transaction_account FOREIGN KEY (account_id, user_id) REFERENCES ledger_account(id, user_id),
    CONSTRAINT chk_ledger_transaction_amount CHECK (amount > 0),
    CONSTRAINT chk_ledger_transaction_category CHECK (
        (kind = 'INCOME' AND category IN ('SALARY','BONUS','PART_TIME','INVESTMENT','INTEREST','GIFT','OTHER_INCOME')) OR
        (kind = 'EXPENSE' AND category IN ('FOOD','SHOPPING','TRANSPORT','HOUSING','ENTERTAINMENT','MEDICAL','OTHER_EXPENSE')))
);
CREATE INDEX ix_ledger_transaction_owner_date ON ledger_transaction (user_id, is_deleted, transaction_date, id);
