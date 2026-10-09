package collector.bu.service;

import java.time.LocalDate;
import java.util.List;
import static collector.bu.model.BudgetModels.*;

/** BudgetService业务接口；Controller及其他业务组件依赖此契约，具体逻辑由impl中的实现提供。 */
public interface BudgetService {
    /** 刷新个人预算结转与站内预警，在收支提交事务中同步调用；同一阈值每周期只提示一次。 */
    void refresh(String username);

    /** 查询个人指定日期范围预算，刷新结转后给出实时执行状态；不把注册月预算自动当作实际预算。 */
    Overview overview(String username,LocalDate start,LocalDate end);

    /** 新建个人预算，唯一范围冲突时返回 409，历史月度预算可以触发后续月份结转。 */
    View create(String username,Input input);

    /** 调整已有预算额度、阈值及结转策略，保存旧值和理由，并重新核算后续月份。 */
    View adjust(String username,long id,AdjustmentInput input);

    /** 查询本人预算修改记录，先验证预算归属，禁止跨用户读取调整历史。 */
    List<Adjustment> adjustments(String username,long id);

    /** 把指定月份配置保存为本人常用模板，重复名称返回冲突。 */
    Template saveTemplate(String username,SaveTemplate input);

    /** 返回本人月度模板，管理员也不能读取其他人的预算方案。 */
    List<Template> templates(String username);

    /** 将本人模板应用到目标月份，保留已有预算，冲突返回 409。 */
    List<View> applyTemplate(String username,long id,ApplyTemplate input);

    /** 将上月或其他月份的配置复制到新月份，不复制消费或重复增加结转余额。 */
    List<View> copyMonth(String username,CopyMonth input);

    /** 获取本人站内预警并刷新当前预算，保留原始通知时间与已读状态。 */
    List<Notice> notices(String username);

    /** 标记本人消息已读，审计用户为当前登录账号。 */
    void readNotice(String username,long id);

    /** 分析已结束月度预算及分类超支频次，按分类与币种分组，不把总预算重复算入分类合计。 */
    History history(String username,String from,String to);
}
