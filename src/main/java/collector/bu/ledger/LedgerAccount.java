package collector.bu.ledger;

import java.time.LocalDateTime;

/** 用户自己的收付款账户及完整审计信息，不表示银行实时余额。 */
public record LedgerAccount(long id, String name, String currency, LocalDateTime createdAt, String createdBy,
        LocalDateTime updatedAt, String updatedBy, boolean deleted) { }
