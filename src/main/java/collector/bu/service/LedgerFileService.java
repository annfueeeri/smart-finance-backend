package collector.bu.service;

import java.time.LocalDate;
import org.springframework.web.multipart.MultipartFile;
import static collector.bu.model.ledger.LedgerModels.*;

/** LedgerFileService业务接口；Controller及其他业务组件依赖此契约，具体逻辑由impl中的实现提供。 */
public interface LedgerFileService {
    /** 返回表头和前五行示例，供前端配置字段映射，不写入数据库。 */
    Inspection inspect(MultipartFile file);

    /** 按映射解析每行，提示字段错误及文件内和数据库重复；预览只读、不生成账户或流水。 */
    Preview preview(String username,MultipartFile file,String mappingJson);

    /** 生成含字段名的 UTF-8 BOM CSV 或 XLSX，可重新映射导入；只导出当前用户筛选数据。 */
    byte[] export(String username,String format,String kind,LocalDate start,LocalDate end,Long accountId);
}
