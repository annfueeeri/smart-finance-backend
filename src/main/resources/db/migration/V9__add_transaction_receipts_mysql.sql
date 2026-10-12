-- MySQL 8：在 V8 后执行一次，先备份数据库并停止应用写入。
ALTER TABLE ledger_transaction ADD CONSTRAINT uq_ledger_transaction_owner UNIQUE(id,book_id);
-- 收据二进制与元数据原子存储，不能通过公共静态 URL 访问。
CREATE TABLE IF NOT EXISTS transaction_receipt (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 book_id BIGINT NOT NULL,
 transaction_id BIGINT NOT NULL,
 file_name VARCHAR(180) NOT NULL,
 content_type VARCHAR(32) NOT NULL,
 file_size BIGINT NOT NULL,
 file_sha256 VARCHAR(64) NOT NULL,
 file_content LONGBLOB NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 created_by VARCHAR(64) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 updated_by VARCHAR(64) NOT NULL,
 is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
 CONSTRAINT fk_receipt_transaction_book FOREIGN KEY(transaction_id,book_id) REFERENCES ledger_transaction(id,book_id),
 CONSTRAINT chk_receipt_size CHECK(file_size>0 AND file_size<=5242880)
);
CREATE INDEX ix_receipt_transaction ON transaction_receipt(book_id,transaction_id,is_deleted,id);
