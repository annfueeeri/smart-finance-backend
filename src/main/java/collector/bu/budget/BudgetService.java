package collector.bu.budget;

import collector.bu.dao.UserDao;
import collector.bu.entity.UserAccount;
import collector.bu.ledger.LedgerCategory;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static collector.bu.budget.BudgetModels.*;

/** 计算个人预算执行、历史对比、结转及站内预警；所有金额按币种精确计算。 */
@Service
public class BudgetService {
    private final UserDao users;
    private final BudgetDao dao;
    private final Clock clock;
    /** 注入用户访问、预算数据库组件，采用可替换时钟以验证跨月和时区边界。 */
    public BudgetService(UserDao users,BudgetDao dao,Clock clock) { this.users=users;this.dao=dao;this.clock=clock; }
    /** 从数据库复查当前认证用户，不允许前端选择查询归属。 */
    private UserAccount actor(String username) { return users.findByUsername(username).filter(u -> u.enabled() && !u.deleted()).orElseThrow(() -> new AccessDeniedException("Active account required")); }
    /** 使用用户注册的时区读取今天，月底按用户当地日期结算。 */
    private LocalDate today(UserAccount user) { return LocalDate.now(clock.withZone(ZoneId.of(user.timezone()))); }
    /** 统一生成非法预算参数业务错误。 */
    private BudgetException invalid() { return new BudgetException("INVALID_BUDGET"); }
    /** 校验正数额度和币种精度，不容许数据库默默四舍五入。 */
    private BigDecimal amount(String text,String currency) {
        try {
            int digits=Currency.getInstance(currency).getDefaultFractionDigits();
            if(digits<0 || digits>4 || text==null || !text.matches("[0-9]{1,12}(\\.[0-9]{1,4})?"))throw invalid();
            var value=new BigDecimal(text); if(value.signum()<=0 || value.scale()>digits)throw invalid(); return value;
        } catch(IllegalArgumentException | NullPointerException e) { throw invalid(); }
    }
    /** 检查阈值为一到十个不重复的百分比，结转仅适用于月预算。 */
    private void settings(List<Integer> thresholds,String rollover,String period) {
        if(thresholds==null || thresholds.isEmpty() || thresholds.size()>10 || thresholds.stream().anyMatch(v -> v==null || v<1 || v>100)
                || new HashSet<>(thresholds).size()!=thresholds.size() || !Set.of("NONE","ONCE","CUMULATIVE").contains(rollover==null ? "" : rollover)
                || (!period.equals("MONTH") && !rollover.equals("NONE")))throw invalid();
    }
    /** 校验总预算或支出分类及标准周期边界；自定义周期最长十年，起始日期限制在前后十年内。 */
    private void validate(UserAccount user,Input input) {
        amount(input.amount(),input.currency());settings(input.thresholds(),input.rolloverMode(),input.period()==null ? "" : input.period());
        try {
            if(input.name()==null || input.name().strip().isEmpty() || input.name().strip().length()>80 || input.start()==null || input.end()==null
                    || input.end().isBefore(input.start()) || input.start().isBefore(today(user).minusYears(10))
                    || input.end().isAfter(today(user).plusYears(10)) || ChronoUnit.DAYS.between(input.start(),input.end())>3660)throw invalid();
            if(!input.category().equals("TOTAL") && !LedgerCategory.valueOf(input.category()).kind().equals("EXPENSE"))throw invalid();
            var start=input.start();var end=input.end();
            switch(input.period()) {
                case "MONTH" -> { if(start.getDayOfMonth()!=1 || !end.equals(YearMonth.from(start).atEndOfMonth()))throw invalid(); }
                case "WEEK" -> { if(start.getDayOfWeek()!=DayOfWeek.MONDAY || !end.equals(start.plusDays(6)))throw invalid(); }
                case "QUARTER" -> { if(start.getDayOfMonth()!=1 || (start.getMonthValue()-1)%3!=0 || !end.equals(start.plusMonths(3).minusDays(1)))throw invalid(); }
                case "YEAR" -> { if(start.getDayOfYear()!=1 || !end.equals(start.plusYears(1).minusDays(1)))throw invalid(); }
                case "CUSTOM" -> { }
                default -> throw invalid();
            }
        } catch(IllegalArgumentException | NullPointerException e) { throw invalid(); }
    }
    /** 将金额格式化为无指数的字符串，小数位遵循币种，零金额也返回合法货币精度。 */
    private String money(BigDecimal value,String currency) { return value.setScale(Currency.getInstance(currency).getDefaultFractionDigits(),RoundingMode.HALF_UP).toPlainString(); }
    /** 精确计算百分比，允许执行率超过百分之百。 */
    private String percent(BigDecimal value,BigDecimal base) { return value.multiply(BigDecimal.valueOf(100)).divide(base,2,RoundingMode.HALF_UP).toPlainString(); }
    /** 计算预算已用、剩余、超支和线性趋势，历史周期预测归为实际，未来登记支出作为预测下限。 */
    private View view(Plan plan,LocalDate today) {
        var effective=plan.amount().add(plan.carryIn());var spent=dao.spent(plan,plan.end());var remaining=effective.subtract(spent);
        int total=(int)ChronoUnit.DAYS.between(plan.start(),plan.end())+1;
        int elapsed=(int)Math.max(0,Math.min(total,ChronoUnit.DAYS.between(plan.start(),today)+1));
        var soFar=dao.spent(plan,today);
        var forecast=elapsed==0 ? spent : soFar.multiply(BigDecimal.valueOf(total)).divide(BigDecimal.valueOf(elapsed),4,RoundingMode.HALF_UP).max(spent);
        var overage=spent.subtract(effective).max(BigDecimal.ZERO);
        var projected=forecast.subtract(effective).max(BigDecimal.ZERO);
        var status=today.isBefore(plan.start()) ? "NOT_STARTED" : overage.signum()>0 ? "OVERSPENT" : today.isAfter(plan.end()) ? "COMPLETED" : "ACTIVE";
        return new View(plan.id(),plan.name(),plan.category(),plan.currency(),plan.period(),plan.start(),plan.end(),money(plan.amount(),plan.currency()),
                plan.thresholds(),plan.rolloverMode(),money(plan.carryIn(),plan.currency()),plan.carryFromId(),money(effective,plan.currency()),
                money(spent,plan.currency()),money(remaining,plan.currency()),money(overage,plan.currency()),percent(spent,effective),percent(overage,effective),
                money(forecast,plan.currency()),money(projected,plan.currency()),elapsed,total-elapsed,status,
                plan.createdAt(),plan.createdBy(),plan.updatedAt(),plan.updatedBy(),plan.deleted());
    }
    /** 找到下一月份的相同分类和币种预算，仅接续标准月度周期。 */
    private Plan successor(List<Plan> plans,Plan source,LocalDate start,LocalDate end) {
        return plans.stream().filter(p -> p.period().equals("MONTH") && p.category().equals(source.category()) && p.currency().equals(source.currency())
                && p.start().equals(start) && p.end().equals(end)).findFirst().orElse(null);
    }
    /** 按月份顺序修正结转链，可补建新月份；只结转一次不再次传递来源月份的余额，累计模式保留余额。 */
    private void rollover(UserAccount user,LocalDate today) {
        var plans=new ArrayList<>(dao.plans(user.id()));
        for(int i=0;i<plans.size();i++) {
            var source=dao.plan(user.id(),plans.get(i).id());
            if(!source.period().equals("MONTH") || !source.end().isBefore(today))continue;
            var start=source.end().plusDays(1);var end=YearMonth.from(start).atEndOfMonth();var target=successor(plans,source,start,end);
            if(source.rolloverMode().equals("NONE") && (target==null || !Objects.equals(target.carryFromId(),source.id())))continue;
            var available=source.amount().add(source.rolloverMode().equals("CUMULATIVE") ? source.carryIn() : BigDecimal.ZERO);
            var carry=source.rolloverMode().equals("NONE") ? BigDecimal.ZERO : available.subtract(dao.spent(source,source.end())).max(BigDecimal.ZERO);
            if(target==null) {
                var input=new Input(source.name(),source.category(),source.currency(),"MONTH",start,end,money(source.amount(),source.currency()),source.thresholds(),source.rolloverMode());
                target=dao.create(user.id(),"SYSTEM",input);plans.add(target);
                plans.sort(Comparator.comparing(Plan::start).thenComparingLong(Plan::id));
            }
            if(target.carryFromId()!=null && !Objects.equals(target.carryFromId(),source.id()))throw new BudgetException("BUDGET_CONFLICT");
            dao.carry(dao.plan(user.id(),target.id()),source,carry,"SYSTEM");
        }
    }
    /** 刷新个人预算结转与站内预警，在收支提交事务中同步调用；同一阈值每周期只提示一次。 */
    @Transactional
    public void refresh(String username) {
        var user=actor(username);dao.lock(user.id());var today=today(user);rollover(user,today);
        for(var plan : dao.plans(user.id())) {
            if(today.isBefore(plan.start()) || today.isAfter(plan.end()))continue;
            var effective=plan.amount().add(plan.carryIn());var spent=dao.spent(plan,plan.end());
            for(var threshold : plan.thresholds()) if(spent.multiply(BigDecimal.valueOf(100)).compareTo(effective.multiply(BigDecimal.valueOf(threshold)))>=0)
                dao.notify(plan,"THRESHOLD_"+threshold,"预算「"+plan.name()+"」已达到 "+threshold+"%，已使用 "+money(spent,plan.currency())+" "+plan.currency()+"，可用额度 "+money(effective,plan.currency())+" "+plan.currency(),"SYSTEM");
            if(spent.compareTo(effective)>0)dao.notify(plan,"OVERSPENT","预算「"+plan.name()+"」已超支 "+money(spent.subtract(effective),plan.currency())+" "+plan.currency(),"SYSTEM");
        }
    }
    /** 查询个人指定日期范围预算，刷新结转后给出实时执行状态；不把注册月预算自动当作实际预算。 */
    @Transactional
    public Overview overview(String username,LocalDate start,LocalDate end) {
        var user=actor(username);if(start!=null && end!=null && start.isAfter(end))throw invalid();refresh(username);var today=today(user);
        var items=dao.plans(user.id()).stream().filter(p -> (start==null || !p.end().isBefore(start)) && (end==null || !p.start().isAfter(end)))
                .map(p -> view(p,today)).toList();
        return new Overview(items,today,user.currency(),user.monthlyBudget()==null ? "" : money(user.monthlyBudget(),user.currency()),dao.unread(user.id()));
    }
    /** 新建个人预算，唯一范围冲突时返回 409，历史月度预算可以触发后续月份结转。 */
    @Transactional
    public View create(String username,Input input) {
        var user=actor(username);validate(user,input);dao.lock(user.id());
        try { var plan=dao.create(user.id(),username,input);refresh(username);return view(dao.plan(user.id(),plan.id()),today(user)); }
        catch(DuplicateKeyException e) { throw new BudgetException("BUDGET_CONFLICT"); }
    }
    /** 调整已有预算额度、阈值及结转策略，保存旧值和理由，并重新核算后续月份。 */
    @Transactional
    public View adjust(String username,long id,AdjustmentInput input) {
        var user=actor(username);dao.lock(user.id());var plan=dao.plan(user.id(),id);amount(input.amount(),plan.currency());settings(input.thresholds(),input.rolloverMode(),plan.period());
        if(input.reason()==null || input.reason().strip().isEmpty() || input.reason().length()>300)throw invalid();
        dao.adjust(plan,input,username);refresh(username);return view(dao.plan(user.id(),id),today(user));
    }
    /** 查询本人预算修改记录，先验证预算归属，禁止跨用户读取调整历史。 */
    @Transactional(readOnly=true)
    public List<Adjustment> adjustments(String username,long id) { var user=actor(username);dao.plan(user.id(),id);return dao.adjustments(user.id(),id); }
    /** 严格解析 YYYY-MM，拒绝无效年月与超出前后十年范围的月份。 */
    private YearMonth month(UserAccount user,String text) {
        try {
            if(text==null || !text.matches("[0-9]{4}-[0-9]{2}"))throw invalid();var value=YearMonth.parse(text);
            if(value.atDay(1).isBefore(today(user).minusYears(10).withDayOfMonth(1)) || value.atEndOfMonth().isAfter(today(user).plusYears(10).withDayOfMonth(1).plusMonths(1).minusDays(1)))throw invalid();return value;
        } catch(java.time.DateTimeException e) { throw invalid(); }
    }
    /** 提取本人指定月份的全部月度配置，排除实际消费和结转数据。 */
    private List<TemplateItem> items(UserAccount user,YearMonth month) {
        var items=dao.plans(user.id()).stream().filter(p -> p.period().equals("MONTH") && YearMonth.from(p.start()).equals(month))
                .map(p -> new TemplateItem(p.name(),p.category(),p.currency(),money(p.amount(),p.currency()),p.thresholds(),p.rolloverMode())).toList();
        if(items.isEmpty() || items.size()>50)throw invalid();return items;
    }
    /** 把指定月份配置保存为本人常用模板，重复名称返回冲突。 */
    @Transactional
    public Template saveTemplate(String username,SaveTemplate input) {
        var user=actor(username);dao.lock(user.id());if(input.name()==null || input.name().strip().isEmpty() || input.name().length()>80)throw invalid();
        try { return dao.saveTemplate(user.id(),username,input.name(),items(user,month(user,input.sourceMonth()))); }
        catch(DuplicateKeyException e) { throw new BudgetException("BUDGET_CONFLICT"); }
    }
    /** 返回本人月度模板，管理员也不能读取其他人的预算方案。 */
    @Transactional(readOnly=true)
    public List<Template> templates(String username) { return dao.templates(actor(username).id()); }
    /** 原子应用一组月度配置，任何冲突或不合法配置都不允许留下部分预算。 */
    private List<View> apply(UserAccount user,String username,YearMonth month,List<TemplateItem> items) {
        dao.lock(user.id());var created=new ArrayList<Long>();
        try {
            for(var item : items) {
                var input=new Input(item.name(),item.category(),item.currency(),"MONTH",month.atDay(1),month.atEndOfMonth(),item.amount(),item.thresholds(),item.rolloverMode());
                validate(user,input);created.add(dao.create(user.id(),username,input).id());
            }
        } catch(DuplicateKeyException e) { throw new BudgetException("BUDGET_CONFLICT"); }
        refresh(username);return created.stream().map(id -> view(dao.plan(user.id(),id),today(user))).toList();
    }
    /** 将本人模板应用到目标月份，保留已有预算，冲突返回 409。 */
    @Transactional
    public List<View> applyTemplate(String username,long id,ApplyTemplate input) { var user=actor(username);return apply(user,username,month(user,input.month()),dao.template(user.id(),id).items()); }
    /** 将上月或其他月份的配置复制到新月份，不复制消费或重复增加结转余额。 */
    @Transactional
    public List<View> copyMonth(String username,CopyMonth input) {
        var user=actor(username);var source=month(user,input.sourceMonth());var target=month(user,input.targetMonth());if(source.equals(target))throw invalid();
        dao.lock(user.id());return apply(user,username,target,items(user,source));
    }
    /** 获取本人站内预警并刷新当前预算，保留原始通知时间与已读状态。 */
    @Transactional
    public List<Notice> notices(String username) { refresh(username);return dao.notices(actor(username).id()); }
    /** 标记本人消息已读，审计用户为当前登录账号。 */
    @Transactional
    public void readNotice(String username,long id) { var user=actor(username);dao.readNotice(user.id(),id,username); }
    /** 分析已结束月度预算及分类超支频次，按分类与币种分组，不把总预算重复算入分类合计。 */
    @Transactional
    public History history(String username,String from,String to) {
        var user=actor(username);refresh(username);var today=today(user);
        var first=from==null ? YearMonth.from(today).minusMonths(12) : month(user,from);var last=to==null ? YearMonth.from(today).minusMonths(1) : month(user,to);
        if(first.isAfter(last))throw invalid();
        var views=dao.plans(user.id()).stream().filter(p -> p.period().equals("MONTH") && p.end().isBefore(today)
                && !YearMonth.from(p.start()).isBefore(first) && !YearMonth.from(p.start()).isAfter(last)).map(p -> view(p,today)).toList();
        var groups=new TreeMap<String,List<View>>();for(var view : views)if(!view.category().equals("TOTAL"))groups.computeIfAbsent(view.category()+":"+view.currency(),k -> new ArrayList<>()).add(view);
        var categories=new ArrayList<CategoryHistory>();
        for(var group : groups.values()) {
            var budget=group.stream().map(v -> new BigDecimal(v.effectiveAmount())).reduce(BigDecimal.ZERO,BigDecimal::add);
            var spent=group.stream().map(v -> new BigDecimal(v.spent())).reduce(BigDecimal.ZERO,BigDecimal::add);
            var overage=group.stream().map(v -> new BigDecimal(v.overage())).reduce(BigDecimal.ZERO,BigDecimal::add);var sample=group.get(0);
            categories.add(new CategoryHistory(sample.category(),sample.currency(),group.size(),(int)group.stream().filter(v -> new BigDecimal(v.overage()).signum()>0).count(),money(budget,sample.currency()),money(spent,sample.currency()),money(overage,sample.currency()),percent(spent,budget)));
        }
        return new History(views,categories);
    }
}
