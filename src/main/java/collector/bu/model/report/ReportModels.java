package collector.bu.model.report;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** 财务报表与账户基础数据结构，金额统一使用十进制字符串并按币种分别统计。 */
public final class ReportModels {
    /** 查询条件，现金流和余额仅应用账户/币种/日期，分类/标签/方向作用于收支分析。 */
    public record Filter(LocalDate start,LocalDate end,String grouping,Long accountId,String currency,String kind,String category,String tag) { }
    /** 每日按账户、方向、分类、商家和标签预聚合的真实流水，不限制到前一万条。 */
    public record Movement(long accountId,LocalDate date,String kind,String category,String merchant,List<String> tags,BigDecimal amount,long count) { }
    /** 当前账户基础资料编辑；币种和余额基准不通过资料修改改变。 */
    public record AccountProfile(@NotBlank @Size(max=80) String name,@NotBlank String type) { }
    /** 本人同币种账户间转账，不生成收入/支出流水，不影响预算。 */
    public record TransferInput(@NotNull @Min(1) Long fromAccountId,@NotNull @Min(1) Long toAccountId,@NotBlank String amount,@NotNull LocalDate date,@Size(max=1000) String note) { }
    /** 内部转账及审计，方向账户由服务器校验，币种从账户取得。 */
    public record Transfer(long id,long fromAccountId,long toAccountId,String currency,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount,LocalDate date,String note,
            LocalDateTime createdAt,String createdBy,LocalDateTime updatedAt,String updatedBy,boolean deleted) { }
    /** 记录日终余额或投资市值，负数表示负债，非现金余额校准与真实消费分开保存。 */
    public record ValuationInput(@NotNull LocalDate date,@NotBlank String balance,@Size(max=1000) String note) { }
    /** 有日期的余额校准记录；同一天多次记录以最后主键为准，原记录仍可追踪。 */
    public record Valuation(long id,long accountId,LocalDate date,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal balance,String note,
            LocalDateTime createdAt,String createdBy,LocalDateTime updatedAt,String updatedBy,boolean deleted) { }
    /** 核心指标，平均日支出按统计日期闭区间天数计算。 */
    public record Summary(String income,String expense,String net,String averageDailyExpense,long incomeCount,long expenseCount) { }
    /** 零支出分类不会伪造消费占比；百分比只在同币种内计算。 */
    public record CategoryShare(String category,String amount,String percentage,long count) { }
    /** 按日/周/月/年补齐无流水的时间段，金额不是图表演示数据。 */
    public record Trend(LocalDate start,LocalDate end,String income,String expense,String net) { }
    /** 最近六个月的分类消费变化，以报表终点月份为基准。 */
    public record CategoryPoint(String month,String category,String amount) { }
    /** 商家或标签排行；多标签消费可计入多个项目，标签占比不互斥。 */
    public record Ranking(String name,String amount,long count) { }
    /** 账户完整现金流，余额调整列用于余额基准、日终估值及筛选前历史差异的对账。 */
    public record CashFlow(long accountId,String name,String type,String openingBalance,boolean openingKnown,
            String inflow,String outflow,String internalInflow,String internalOutflow,String balanceAdjustment,
            String closingBalance,boolean closingKnown) { }
    /** 账户余额或净资产历史点，未知基准前账户排除并显示 unknownAccounts。 */
    public record AssetPoint(LocalDate date,String assets,String liabilities,String netAssets,int unknownAccounts) { }
    /** 账户自身余额历史，日期是所选分组段末，初始点为统计开始日前一天。 */
    public record AccountPoint(LocalDate date,String balance,boolean known) { }
    /** 各账户期末余额与历史，负余额计入负债，正余额计入资产。 */
    public record AccountBalance(long accountId,String name,String type,String balance,boolean known,List<AccountPoint> history) { }
    /** 对比变化率以对比期绝对值为分母，对比期为零时返回 null。 */
    public record Change(String current,String previous,String difference,String percentage) { }
    /** 环比/同比与比较期完整日期，保留净结余负数的改善方向。 */
    public record Comparison(LocalDate start,LocalDate end,Change income,Change expense,Change net) { }
    /** 同币种报表组，禁止把不同币种无汇率地加总。 */
    public record CurrencyReport(String currency,Summary summary,List<Trend> trend,List<CategoryShare> categories,
            List<CategoryPoint> categoryTrend,List<Ranking> merchants,List<Ranking> tags,List<CashFlow> cashFlow,
            List<AssetPoint> assets,List<AccountBalance> balances,Comparison monthOverMonth,Comparison yearOverYear) { }
    /** 自定义条件及全部币种统计结果，页面和导出使用同一服务计算。 */
    public record Report(Filter filter,List<CurrencyReport> currencies) { }
    /** 可选的真实标签，允许输入新标签后在交易录入保存。 */
    public record ReportOptions(List<String> tags) { }
}
