package collector.bu.model.budget;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** 预算请求和响应，所有金额使用十进制字符串，不让前端浮点运算改变金额。 */
public final class BudgetModels {
    /** 新建预算：TOTAL 为总支出预算，其他分类仅允许支出分类，日期闭区间。 */
    public record Input(@NotBlank @Size(max=80) String name,@NotBlank String category,@NotBlank String currency,
            @NotBlank String period,@NotNull LocalDate start,@NotNull LocalDate end,
            @NotBlank String amount,@NotNull @Size(min=1,max=10) List<@NotNull @Min(1) @Max(100) Integer> thresholds,
            @NotBlank String rolloverMode) { }
    /** 内部数据库预算，金额保持精确数值，计算后再转换为对外字符串。 */
    public record Plan(long id,long userId,String name,String category,String currency,String period,LocalDate start,
            LocalDate end,BigDecimal amount,List<Integer> thresholds,String rolloverMode,BigDecimal carryIn,Long carryFromId,
            LocalDateTime createdAt,String createdBy,LocalDateTime updatedAt,String updatedBy,boolean deleted) { }
    /** 预算执行信息，剩余可为负数；预测为线性外推，无支出天数不产生虚构消费。 */
    public record View(long id,String name,String category,String currency,String period,LocalDate start,LocalDate end,
            String amount,List<Integer> thresholds,String rolloverMode,String carryIn,Long carryFromId,String effectiveAmount,
            String spent,String remaining,String overage,String executionRate,String overrunRate,String forecast,
            String projectedOverage,int elapsedDays,int remainingDays,String status,
            LocalDateTime createdAt,String createdBy,LocalDateTime updatedAt,String updatedBy,boolean deleted) { }
    /** 列表提供用户当地今天日期、默认币种、注册预算建议和提醒数量。 */
    public record Overview(List<View> items,LocalDate today,String currency,String suggestedAmount,int unreadCount) { }
    /** 在周期中调整金额和预警/结转配置，理由必须填写，不能改归属、币种或周期。 */
    public record AdjustmentInput(@NotBlank String amount,@NotBlank @Size(max=300) String reason,
            @NotNull @Size(min=1,max=10) List<@NotNull @Min(1) @Max(100) Integer> thresholds,@NotBlank String rolloverMode) { }
    /** 金额及结转变化历史，后台结转修改也记录独立修改理由和操作者。 */
    public record Adjustment(long id,long budgetId,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal oldAmount,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal newAmount,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal oldCarry,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal newCarry,
            String reason,LocalDateTime createdAt,String createdBy,LocalDateTime updatedAt,String updatedBy,boolean deleted) { }
    /** 月度模板只保存配置，不保存实际消费或结转余额。 */
    public record TemplateItem(String name,String category,String currency,String amount,List<Integer> thresholds,String rolloverMode) { }
    /** 常用月度方案，模板项涵盖总预算和任意分类预算。 */
    public record Template(long id,String name,List<TemplateItem> items,LocalDateTime createdAt,String createdBy,
            LocalDateTime updatedAt,String updatedBy,boolean deleted) { }
    /** 把本人某月全部月度预算保存为模板。 */
    public record SaveTemplate(@NotBlank @Size(max=80) String name,@NotBlank String sourceMonth) { }
    /** 将模板应用到目标月份，冲突时整批回滚，不覆盖已有预算。 */
    public record ApplyTemplate(@NotBlank String month) { }
    /** 复制上个月或其他月份配置，实际消费和结转不直接复制，结转另外重新计算。 */
    public record CopyMonth(@NotBlank String sourceMonth,@NotBlank String targetMonth) { }
    /** 站内预警，唯一事件键避免刷新或导入触发同一预算阈值重复通知。 */
    public record Notice(long id,long budgetId,String eventKey,String message,boolean read,LocalDateTime createdAt,
            String createdBy,LocalDateTime updatedAt,String updatedBy,boolean deleted) { }
    /** 按分类与币种统计历史月度预算的超支频率，避免把总预算和分类预算混加。 */
    public record CategoryHistory(String category,String currency,int periods,int overspentPeriods,String totalBudget,
            String totalSpent,String totalOverage,String executionRate) { }
    /** 历史仅分析已结束的月度预算，逐月明细与分类统计按币种分别显示。 */
    public record History(List<View> months,List<CategoryHistory> categories) { }
}
