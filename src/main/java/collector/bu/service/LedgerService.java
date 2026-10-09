package collector.bu.service;

import collector.bu.entity.ledger.LedgerAccount;
import collector.bu.entity.ledger.LedgerEntry;
import collector.bu.entity.UserAccount;
import java.time.LocalDate;
import java.util.List;
import static collector.bu.model.ledger.LedgerModels.*;

/** LedgerService业务接口；Controller及其他业务组件依赖此契约，具体逻辑由impl中的实现提供。 */
public interface LedgerService {
    /** 从数据库验证当前账号存在、未删除且启用，禁止使用客户端用户 ID。 */
    UserAccount actor(String username);

    /** 返回当前用户可使用的账户和分类，以用户时区生成默认日期。 */
    Options options(String username);

    /** 创建真实个人收付款账户，同名同币种账户冲突时返回明确业务错误。 */
    LedgerAccount createAccount(String username, AccountInput input);

    /** 验证账户归属、类型与分类、正数金额、日期和可选文字；不允许金额被数据库舍入。 */
    LedgerAccount validate(String username, EntryInput input);

    /** 规范可选文字，未填写保存为空字符串，去掉首尾空白。 */
    String clean(String value);

    /** 新增个人收支记录，币种取自归属账户，创建与修改用户均由当前认证账号记录。 */
    LedgerEntry createEntry(String username, EntryInput input);

    /** 查询个人分页记录，日期闭区间筛选，账户筛选也必须属于本人。 */
    EntryPage list(String username, String kind, LocalDate start, LocalDate end, Long accountId, int page, int size);

    /** 原子导入预览确认后的记录；再次验证，默认跳过重复，任何错误令整个批次回滚。 */
    ImportResult importEntries(String username, ImportInput input);

    /** 检测一条当前用户流水是否已存在，供导入预览使用。 */
    boolean duplicate(String username, EntryInput input);

    /** 导出全部筛选记录，最多一万条；超限要求缩小筛选范围，避免静默截断。 */
    List<LedgerEntry> exportEntries(String username,String kind,LocalDate start,LocalDate end,Long accountId);
}
