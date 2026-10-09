package collector.bu.service;

import static collector.bu.model.report.ReportModels.*;

/** ReportService业务接口；Controller及其他业务组件依赖此契约，具体逻辑由impl中的实现提供。 */
public interface ReportService {
    /** 获取当前用户全部真实标签作为自定义报表筛选候选。 */
    ReportOptions options(String username);

    /** 生成完整自定义财务报表，同一计算结果供页面、CSV、Excel和PDF共用。 */
    Report report(String username,Filter input);
}
