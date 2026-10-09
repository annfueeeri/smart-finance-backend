package collector.bu.entity;

import java.time.LocalDateTime;

/** 用户自己的收付款账户及完整审计信息，余额基准来自用户记账，不表示银行实时余额。 */
public record LedgerAccount(long id, String name, String currency, LocalDateTime createdAt, String createdBy,
        LocalDateTime updatedAt, String updatedBy, boolean deleted,String type,
        @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) java.math.BigDecimal openingBalance,
        java.time.LocalDate openingDate) { }
