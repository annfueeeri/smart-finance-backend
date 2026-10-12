package collector.bu.model;

import java.time.LocalDateTime;

/** 收据契约，列表不返回二进制内容，文件下载由专门接口授权。 */
public final class ReceiptModels {
    /** 已上传收据的公开元数据及实际操作者审计。 */
    public record Receipt(long id,long transactionId,String fileName,String contentType,long fileSize,
            LocalDateTime createdAt,String createdBy,LocalDateTime updatedAt,String updatedBy,boolean deleted) { }
    /** 仅供授权后的下载使用，不直接序列化成 JSON。 */
    public record Content(Receipt receipt,byte[] bytes) { }
}
