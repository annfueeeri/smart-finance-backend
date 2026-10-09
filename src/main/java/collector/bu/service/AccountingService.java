package collector.bu.service;

import collector.bu.entity.LedgerAccount;
import java.util.List;
import static collector.bu.model.ReportModels.*;

/** AccountingService业务接口；Controller及其他业务组件依赖此契约，具体逻辑由impl中的实现提供。 */
public interface AccountingService {
    /** 保存账户资料并记录修改用户，同名同币种冲突返回409。 */
    LedgerAccount profile(String username,long id,AccountProfile input);

    /** 原子保存本人同币种账户内部转账，拒绝同账户和跨用户转账，不生成收支或预算通知。 */
    Transfer transfer(String username,TransferInput input);

    /** 查询本人内部转账，保留真实转账主键和审计信息。 */
    List<Transfer> transfers(String username);

    /** 新增本人账户日终余额/投资市值，必须不早于开户余额基准；旧值保留以支持历史曲线。 */
    Valuation value(String username,long id,ValuationInput input);

    /** 返回本人某账户的全部余额校准历史，先验证账户归属。 */
    List<Valuation> values(String username,long id);
}
