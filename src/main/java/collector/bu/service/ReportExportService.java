package collector.bu.service;

import collector.bu.model.ReportModels.Report;

/** ReportExportService业务接口；Controller及其他业务组件依赖此契约，具体逻辑由impl中的实现提供。 */
public interface ReportExportService {
    /** 选择CSV/XLSX/PDF导出，不支持格式拒绝；解析或字体故障不伪装成成功文件。 */
    byte[] export(Report report,String format);
}
