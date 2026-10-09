package collector.bu.service.impl;

import collector.bu.service.ReportService;

import collector.bu.entity.ledger.LedgerAccount;
import collector.bu.exception.LedgerException;
import collector.bu.model.ledger.LedgerCategory;
import collector.bu.service.LedgerService;

import collector.bu.dao.AccountDao;
import collector.bu.dao.TransactionDao;
import collector.bu.dao.ReportDao;
import collector.bu.dao.TransferDao;
import collector.bu.dao.AccountValuationDao;
import java.math.*;
import java.time.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static collector.bu.model.report.ReportModels.*;

/** 个人财务报表计算，币种分组、金额精确、收支与内部转账/非现金估值分别统计。 */
@Service
public class ReportServiceImpl implements ReportService {
    private final LedgerService users;
    private final AccountDao accounts;
    private final TransactionDao transactions;
    private final TransferDao transfers;
    private final AccountValuationDao valuations;
    private final ReportDao dao;
    private final Clock clock;
    private record Balance(BigDecimal value,boolean known) { }
    private record Data(List<LedgerAccount> accounts,List<Movement> movements,List<Transfer> transfers,List<Valuation> values) { }
    /** 注入用户验证、真实数据访问和时钟，默认周期按用户时区确定。 */
    public ReportServiceImpl(LedgerService users,AccountDao accounts,TransactionDao transactions,TransferDao transfers,AccountValuationDao valuations,ReportDao dao,Clock clock) { this.users=users;this.accounts=accounts;this.transactions=transactions;this.transfers=transfers;this.valuations=valuations;this.dao=dao;this.clock=clock; }
    /** 将报表参数错误转成通用400，避免泄露实现或其他用户数据。 */
    private LedgerException invalid() { return new LedgerException(LedgerException.Reason.INVALID_REQUEST); }
    /** 完成默认日期、分组及筛选检查，日报最长366天，其他分组最长十年。 */
    private Filter filter(String username,Filter input) {
        var user=users.actor(username);var today=LocalDate.now(clock.withZone(ZoneId.of(user.timezone())));
        var start=input.start()==null ? today.withDayOfMonth(1) : input.start();var end=input.end()==null ? today : input.end();
        var grouping=input.grouping()==null ? "MONTH" : input.grouping();
        if(start.getYear()<1900 || end.getYear()>9998 || start.isAfter(end) || ChronoUnit.DAYS.between(start,end)>3660
                || !Set.of("DAY","WEEK","MONTH","YEAR").contains(grouping) || (grouping.equals("DAY") && ChronoUnit.DAYS.between(start,end)>365))throw invalid();
        if(input.accountId()!=null && accounts.account(user.id(),input.accountId()).isEmpty())throw new LedgerException(LedgerException.Reason.ACCOUNT_NOT_FOUND);
        try {
            if(input.currency()!=null && Currency.getInstance(input.currency()).getDefaultFractionDigits()<0)throw invalid();
            if(input.kind()!=null && !Set.of("INCOME","EXPENSE").contains(input.kind()))throw invalid();
            if(input.category()!=null)LedgerCategory.valueOf(input.category());
            if(input.tag()!=null && (input.tag().isBlank() || input.tag().length()>30 || input.tag().codePoints().anyMatch(ch -> Character.isISOControl(ch) || ch=='|' || ch==',' || ch=='，' || ch==';')))throw invalid();
        }catch(IllegalArgumentException e) { throw invalid(); }
        return new Filter(start,end,grouping,input.accountId(),input.currency(),input.kind(),input.category(),input.tag());
    }
    /** 使用币种精度输出金额字符串，保留净结余、余额和对比差额的负数。 */
    private String money(BigDecimal value,String currency) { return value.setScale(Currency.getInstance(currency).getDefaultFractionDigits(),RoundingMode.HALF_UP).toPlainString(); }
    /** 在同币种内计算占比，基数为零时返回零，避免NaN或除零。 */
    private String percent(BigDecimal value,BigDecimal base) { return base.signum()==0 ? "0.00" : value.multiply(BigDecimal.valueOf(100)).divide(base,2,RoundingMode.HALF_UP).toPlainString(); }
    /** 日期闭区间判断，所有流水只统计用户选择的日期。 */
    private boolean between(LocalDate date,LocalDate start,LocalDate end) { return !date.isBefore(start) && !date.isAfter(end); }
    /** 应用收支方向、分类和标签条件，不把此条件用于账户余额重建。 */
    private boolean matches(Movement movement,Filter filter) { return (filter.kind()==null || movement.kind().equals(filter.kind())) && (filter.category()==null || movement.category().equals(filter.category())) && (filter.tag()==null || movement.tags().contains(filter.tag())); }
    /** 汇总指定集合中满足条件的金额，不通过浮点计算财务数值。 */
    private BigDecimal sum(List<Movement> rows,Predicate<Movement> test) { return rows.stream().filter(test).map(Movement::amount).reduce(BigDecimal.ZERO,BigDecimal::add); }
    /** 计算区间收入、支出、净结余、平均日支出和真实交易次数。 */
    private Summary summary(List<Movement> rows,Filter filter,LocalDate start,LocalDate end,String currency) {
        var selected=rows.stream().filter(m -> between(m.date(),start,end) && matches(m,filter)).toList();
        var income=sum(selected,m -> m.kind().equals("INCOME"));var expense=sum(selected,m -> m.kind().equals("EXPENSE"));
        var daily=expense.divide(BigDecimal.valueOf(ChronoUnit.DAYS.between(start,end)+1),Currency.getInstance(currency).getDefaultFractionDigits(),RoundingMode.HALF_UP);
        return new Summary(money(income,currency),money(expense,currency),money(income.subtract(expense),currency),money(daily,currency),
                selected.stream().filter(m -> m.kind().equals("INCOME")).mapToLong(Movement::count).sum(),selected.stream().filter(m -> m.kind().equals("EXPENSE")).mapToLong(Movement::count).sum());
    }
    /** 根据日、周、月、年定位所属时间段开始，周采用周一。 */
    private LocalDate bucket(LocalDate date,String grouping) { return switch(grouping) { case "DAY" -> date; case "WEEK" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));case "MONTH" -> date.withDayOfMonth(1);default -> date.withDayOfYear(1); }; }
    /** 取得下一分组起点，用于补齐零交易时间段和资产曲线日期。 */
    private LocalDate next(LocalDate date,String grouping) { return switch(grouping) { case "DAY" -> date.plusDays(1);case "WEEK" -> date.plusWeeks(1);case "MONTH" -> date.plusMonths(1);default -> date.plusYears(1); }; }
    /** 生成收支时间序列，无交易的分组也保留，首尾只统计选择区间。 */
    private List<Trend> trend(List<Movement> rows,Filter filter,String currency) {
        var results=new ArrayList<Trend>();
        for(var point=bucket(filter.start(),filter.grouping());!point.isAfter(filter.end());point=next(point,filter.grouping())) {
            var start=point.isBefore(filter.start()) ? filter.start() : point;var end=next(point,filter.grouping()).minusDays(1);if(end.isAfter(filter.end()))end=filter.end();
            var summary=summary(rows,filter,start,end,currency);results.add(new Trend(start,end,summary.income(),summary.expense(),summary.net()));
        }
        return results;
    }
    /** 计算互斥支出分类占比，排序按真实支出金额，不生成演示数据。 */
    private List<CategoryShare> categories(List<Movement> rows,Filter filter,String currency) {
        var expense=rows.stream().filter(m -> m.kind().equals("EXPENSE") && between(m.date(),filter.start(),filter.end()) && matches(m,filter)).toList();
        var total=sum(expense,m -> true);var groups=new TreeMap<String,List<Movement>>();expense.forEach(m -> groups.computeIfAbsent(m.category(),k -> new ArrayList<>()).add(m));
        return groups.entrySet().stream().map(e -> { var amount=sum(e.getValue(),m -> true);return new CategoryShare(e.getKey(),money(amount,currency),percent(amount,total),e.getValue().stream().mapToLong(Movement::count).sum()); })
                .sorted(Comparator.comparing((CategoryShare c) -> new BigDecimal(c.amount())).reversed().thenComparing(CategoryShare::category)).toList();
    }
    /** 展示截至所选终点的最近六个月支出分类变化，尊重账户、方向和标签条件。 */
    private List<CategoryPoint> categoryTrend(List<Movement> rows,Filter filter,String currency) {
        var results=new ArrayList<CategoryPoint>();var last=YearMonth.from(filter.end());
        for(int offset=5;offset>=0;offset--) {
            var month=last.minusMonths(offset);var end=month.atEndOfMonth().isAfter(filter.end()) ? filter.end() : month.atEndOfMonth();
            for(var category : LedgerCategory.values())if(category.kind().equals("EXPENSE") && (filter.category()==null || filter.category().equals(category.name()))) {
                var value=sum(rows,m -> m.kind().equals("EXPENSE") && m.category().equals(category.name()) && between(m.date(),month.atDay(1),end) && matches(m,filter));
                results.add(new CategoryPoint(month.toString(),category.name(),money(value,currency)));
            }
        }
        return results;
    }
    /** 汇总商家前20名或全部项目标签，多标签交易在各标签中分别计数但不影响收支总额。 */
    private List<Ranking> rankings(List<Movement> rows,Filter filter,String currency,boolean tags) {
        var groups=new TreeMap<String,List<Movement>>();
        for(var row : rows)if(row.kind().equals("EXPENSE") && between(row.date(),filter.start(),filter.end()) && matches(row,filter))
            for(var name : tags ? row.tags() : List.of(row.merchant().isBlank() ? "未填写商家" : row.merchant()))groups.computeIfAbsent(name,k -> new ArrayList<>()).add(row);
        return groups.entrySet().stream().map(e -> new Ranking(e.getKey(),money(sum(e.getValue(),m -> true),currency),e.getValue().stream().mapToLong(Movement::count).sum()))
                .sorted(Comparator.comparing((Ranking r) -> new BigDecimal(r.amount())).reversed().thenComparing(Ranking::name)).limit(tags ? Long.MAX_VALUE : 20).toList();
    }
    /** 重建某账户期末余额：期初基准含当日流水，最新日终估值覆盖当日及以前流水，之后继续加减。 */
    private Balance balance(LedgerAccount account,LocalDate date,Data data) {
        if(date.isBefore(account.openingDate()))return new Balance(BigDecimal.ZERO,false);
        var value=account.openingBalance();var baseline=account.openingDate();boolean endOfDay=false;
        for(var valuation : data.values())if(valuation.accountId()==account.id() && !valuation.date().isAfter(date)) { value=valuation.balance();baseline=valuation.date();endOfDay=true; }
        for(var movement : data.movements())if(movement.accountId()==account.id() && !movement.date().isAfter(date) && (endOfDay ? movement.date().isAfter(baseline) : !movement.date().isBefore(baseline)))
            value=value.add(movement.kind().equals("INCOME") ? movement.amount() : movement.amount().negate());
        for(var transfer : data.transfers())if(!transfer.date().isAfter(date) && (endOfDay ? transfer.date().isAfter(baseline) : !transfer.date().isBefore(baseline))) {
            if(transfer.fromAccountId()==account.id())value=value.subtract(transfer.amount());if(transfer.toAccountId()==account.id())value=value.add(transfer.amount());
        }
        return new Balance(value,true);
    }
    /** 计算完整账户现金流与对账差额，内部转账分别列示，不当作外部收入或消费。 */
    private List<CashFlow> cashFlows(Data data,Filter filter,String currency) {
        var result=new ArrayList<CashFlow>();
        for(var account : data.accounts()) {
            var opening=balance(account,filter.start().minusDays(1),data);var closing=balance(account,filter.end(),data);
            var in=sum(data.movements(),m -> m.accountId()==account.id() && m.kind().equals("INCOME") && between(m.date(),filter.start(),filter.end()));
            var out=sum(data.movements(),m -> m.accountId()==account.id() && m.kind().equals("EXPENSE") && between(m.date(),filter.start(),filter.end()));
            var transfers=data.transfers().stream().filter(t -> between(t.date(),filter.start(),filter.end())).toList();
            var internalIn=transfers.stream().filter(t -> t.toAccountId()==account.id()).map(Transfer::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
            var internalOut=transfers.stream().filter(t -> t.fromAccountId()==account.id()).map(Transfer::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
            var adjustment=closing.value().subtract(opening.value()).subtract(in).add(out).subtract(internalIn).add(internalOut);
            result.add(new CashFlow(account.id(),account.name(),account.type(),money(opening.value(),currency),opening.known(),money(in,currency),money(out,currency),money(internalIn,currency),money(internalOut,currency),money(adjustment,currency),money(closing.value(),currency),closing.known()));
        }
        return result;
    }
    /** 资产曲线包含开始日前一日和每个分组期末，所有金额都按记账日期重建。 */
    private List<LocalDate> points(Filter filter) {
        var points=new ArrayList<LocalDate>();points.add(filter.start().minusDays(1));
        for(var point=bucket(filter.start(),filter.grouping());!point.isAfter(filter.end());point=next(point,filter.grouping())) { var end=next(point,filter.grouping()).minusDays(1);points.add(end.isAfter(filter.end()) ? filter.end() : end); }
        return points;
    }
    /** 统计同币种总资产、负债和净资产，负余额算负债，未知基准前账户不计并显式计数。 */
    private List<AssetPoint> assets(Data data,List<LocalDate> points,String currency) {
        var result=new ArrayList<AssetPoint>();
        for(var date : points) {
            var assets=BigDecimal.ZERO;var liabilities=BigDecimal.ZERO;int unknown=0;
            for(var account : data.accounts()) { var balance=balance(account,date,data);if(!balance.known()){unknown++;continue;}if(balance.value().signum()>=0)assets=assets.add(balance.value());else liabilities=liabilities.subtract(balance.value()); }
            result.add(new AssetPoint(date,money(assets,currency),money(liabilities,currency),money(assets.subtract(liabilities),currency),unknown));
        }
        return result;
    }
    /** 给出每个账户当前所选期末余额及历史曲线，显示资产/负债类型和未知基准标记。 */
    private List<AccountBalance> balances(Data data,List<LocalDate> points,String currency) {
        return data.accounts().stream().map(account -> {
            var closing=balance(account,points.get(points.size()-1),data);
            var history=points.stream().map(date -> {var point=balance(account,date,data);return new AccountPoint(date,money(point.value(),currency),point.known());}).toList();
            return new AccountBalance(account.id(),account.name(),account.type(),money(closing.value(),currency),closing.known(),history);
        }).toList();
    }
    /** 计算有符号金额差和变化率，比较基数为零时保留null而非无穷大。 */
    private Change change(String current,String previous,String currency) { var now=new BigDecimal(current);var before=new BigDecimal(previous);var diff=now.subtract(before);return new Change(current,previous,money(diff,currency),before.signum()==0 ? null : percent(diff,before.abs())); }
    /** 构造环比或同比区间；完整月份保持比较月份首末日，其他日期按日历移动并处理闰年。 */
    private Comparison comparison(List<Movement> rows,Filter filter,String currency,boolean year) {
        var start=year ? filter.start().minusYears(1) : filter.start().minusMonths(1);var end=year ? filter.end().minusYears(1) : filter.end().minusMonths(1);
        if(filter.start().getDayOfMonth()==1 && filter.end().equals(YearMonth.from(filter.end()).atEndOfMonth()))end=YearMonth.from(end).atEndOfMonth();
        var current=summary(rows,filter,filter.start(),filter.end(),currency);var previous=summary(rows,filter,start,end,currency);
        return new Comparison(start,end,change(current.income(),previous.income(),currency),change(current.expense(),previous.expense(),currency),change(current.net(),previous.net(),currency));
    }
    /** 获取当前用户全部真实标签作为自定义报表筛选候选。 */
    @Transactional(readOnly=true)
    @Override
    public ReportOptions options(String username) { return new ReportOptions(transactions.tags(users.actor(username).id())); }
    /** 生成完整自定义财务报表，同一计算结果供页面、CSV、Excel和PDF共用。 */
    @Transactional(readOnly=true)
    @Override
    public Report report(String username,Filter input) {
        var user=users.actor(username);var filter=filter(username,input);
        var selectedAccounts=accounts.accounts(user.id()).stream().filter(a -> (filter.accountId()==null || filter.accountId()==a.id()) && (filter.currency()==null || filter.currency().equals(a.currency()))).toList();
        var ids=selectedAccounts.stream().map(LedgerAccount::id).collect(java.util.stream.Collectors.toSet());
        var rows=dao.movements(user.id(),filter.end()).stream().filter(m -> ids.contains(m.accountId())).toList();
        var transferRows=transfers.transfers(user.id(),filter.end());var values=valuations.valuations(user.id(),filter.end());
        var currencies=new TreeSet<String>();selectedAccounts.forEach(a -> currencies.add(a.currency()));if(currencies.isEmpty())currencies.add(filter.currency()==null ? user.currency() : filter.currency());
        var results=new ArrayList<CurrencyReport>();var points=points(filter);
        for(var currency : currencies) {
            var selected=selectedAccounts.stream().filter(a -> a.currency().equals(currency)).toList();var selectedIds=selected.stream().map(LedgerAccount::id).collect(java.util.stream.Collectors.toSet());
            var movements=rows.stream().filter(m -> selectedIds.contains(m.accountId())).toList();var data=new Data(selected,movements,transferRows,values);
            results.add(new CurrencyReport(currency,summary(movements,filter,filter.start(),filter.end(),currency),trend(movements,filter,currency),categories(movements,filter,currency),categoryTrend(movements,filter,currency),rankings(movements,filter,currency,false),rankings(movements,filter,currency,true),cashFlows(data,filter,currency),assets(data,points,currency),balances(data,points,currency),comparison(movements,filter,currency,false),comparison(movements,filter,currency,true)));
        }
        return new Report(filter,results);
    }
}
