package collector.bu.service;

import static collector.bu.model.ReceiptModels.*;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/** 收据业务接口，全部操作使用当前个人或家庭账本权限。 */
public interface ReceiptService {
    /** 查询当前流水的有效附件，不返回文件内容。 */
    List<Receipt> list(String username,long transaction);
    /** 校验 JPG/PNG/PDF、大小、重复和数量限制后原子上传。 */
    Receipt upload(String username,long transaction,MultipartFile file);
    /** 验证实时账本权限和流水归属后返回附件内容。 */
    Content content(String username,long transaction,long receipt);
    /** 逻辑删除当前账本附件并保留实际操作者审计。 */
    void delete(String username,long transaction,long receipt);
}
