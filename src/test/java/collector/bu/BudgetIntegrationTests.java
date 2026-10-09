package collector.bu;

import collector.bu.exception.BudgetException;
import collector.bu.model.ledger.LedgerModels;
import collector.bu.service.budget.BudgetService;
import collector.bu.service.ledger.LedgerService;

import collector.bu.dao.UserDao;
import collector.bu.entity.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static collector.bu.model.budget.BudgetModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:budgettests;MODE=MySQL;DB_CLOSE_DELAY=-1","budget.refresh-delay-ms=3600000"})
class BudgetIntegrationTests {
    @Autowired BudgetService budgets;
    @Autowired LedgerService ledger;
    @Autowired UserDao users;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate rest;
    @Autowired PasswordEncoder encoder;
    @MockBean Clock clock;
    private static final String USER="budget-test-user",ADMIN="budget-test-admin",PASSWORD="Budget-testing-password-123";
    /** 清理独立预算测试数据库的业务数据，创建测试用户并冻结时间，避免跨月用例依赖真实日期。 */
    @BeforeEach void setup() {
        for(var table : List.of("budget_notification","budget_adjustment","budget_template"))jdbc.update("DELETE FROM "+table+" WHERE user_id IN(SELECT id FROM app_user WHERE username LIKE 'budget-test-%')");
        jdbc.update("UPDATE budget_plan SET carry_from_id=NULL WHERE user_id IN(SELECT id FROM app_user WHERE username LIKE 'budget-test-%')");
        for(var table : List.of("budget_plan","ledger_transaction","ledger_account"))jdbc.update("DELETE FROM "+table+" WHERE user_id IN(SELECT id FROM app_user WHERE username LIKE 'budget-test-%')");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'budget-test-%'");
        users.insert(USER,encoder.encode(PASSWORD));users.insert(ADMIN,encoder.encode(PASSWORD),UserRole.ADMIN);
        time("2026-10-09T00:00:00Z");
    }
    /** 设置服务器时间，业务仍以账号时区决定当天及结转边界。 */
    private void time(String instant) { when(clock.withZone(any(ZoneId.class))).thenAnswer(i -> Clock.fixed(Instant.parse(instant),i.getArgument(0))); }
    /** 构造月度预算，兼容总预算和分类预算测试。 */
    private Input monthly(String month,String category,String currency,String amount,String rollover) {
        var date=YearMonth.parse(month);return new Input(category+" "+month,category,currency,"MONTH",date.atDay(1),date.atEndOfMonth(),amount,List.of(50,80,100),rollover);
    }
    /** 使用真实收支业务插入工资或支出，以验证预算实时计算与事务调用。 */
    private void entry(String username,long account,String kind,String amount,String date,String category) {
        ledger.createEntry(username,new LedgerModels.EntryInput(account,kind,amount,LocalDate.parse(date),category,"",""));
    }
    /** 从个人执行列表取得一个预算，辅助比较业务计算结果。 */
    private View view(String username,long id) { return budgets.overview(username,null,null).items().stream().filter(v -> v.id()==id).findFirst().orElseThrow(); }
    /** 验证总预算、分类预算按真实支出及币种分别计算，不混入收入、其他用户或其他类别消费。 */
    @Test void actualExpenseProgressForecastAndOverageRespectScopeAndCurrency() {
        var cash=ledger.createAccount(USER,new LedgerModels.AccountInput("JPY","JPY"));
        var yuan=ledger.createAccount(USER,new LedgerModels.AccountInput("CNY","CNY"));
        var total=budgets.create(USER,monthly("2026-10","TOTAL","JPY","200000","NONE"));
        var food=budgets.create(USER,monthly("2026-10","FOOD","JPY","10000","NONE"));
        entry(USER,cash.id(),"INCOME","999999","2026-10-01","SALARY");
        entry(USER,cash.id(),"EXPENSE","8000","2026-10-02","FOOD");
        entry(USER,cash.id(),"EXPENSE","2000","2026-10-03","SHOPPING");
        entry(USER,yuan.id(),"EXPENSE","999.99","2026-10-03","FOOD");
        assertThat(view(USER,total.id()).spent()).isEqualTo("10000");assertThat(view(USER,total.id()).remaining()).isEqualTo("190000");
        var current=view(USER,food.id());assertThat(current.executionRate()).isEqualTo("80.00");assertThat(current.remainingDays()).isEqualTo(22);
        assertThat(current.forecast()).isEqualTo("27556");assertThat(current.projectedOverage()).isEqualTo("17556");
        entry(USER,cash.id(),"EXPENSE","3000","2026-10-09","FOOD");
        var exceeded=view(USER,food.id());assertThat(exceeded.spent()).isEqualTo("11000");assertThat(exceeded.remaining()).isEqualTo("-1000");
        assertThat(exceeded.overage()).isEqualTo("1000");assertThat(exceeded.executionRate()).isEqualTo("110.00");assertThat(exceeded.overrunRate()).isEqualTo("10.00");
        assertThat(budgets.overview(ADMIN,null,null).items()).isEmpty();
    }
    /** 验证阈值和超支提醒实时写入、刷新去重、已读持久化，并禁止跨用户访问预算和通知。 */
    @Test void httpPermissionsAndNotificationDeduplicationPersist() {
        var account=ledger.createAccount(USER,new LedgerModels.AccountInput("Cash","JPY"));
        var user=new Browser(USER);var admin=new Browser(ADMIN);
        var created=user.post("/api/budgets",monthly("2026-10","TOTAL","JPY","100","NONE"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);long id=created.getBody().get("id").asLong();
        assertThat(created.getBody().get("amount").isTextual()).isTrue();assertThat(created.getBody().get("createdBy").asText()).isEqualTo(USER);
        entry(USER,account.id(),"EXPENSE","110","2026-10-09","FOOD");
        var notices=budgets.notices(USER);assertThat(notices).hasSize(4);assertThat(budgets.notices(USER)).hasSize(4);
        assertThat(user.get("/api/budgets").getBody().get("unreadCount").asInt()).isEqualTo(4);
        assertThat(user.put("/api/budget-notifications/"+notices.get(0).id()+"/read",null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(user.get("/api/budgets").getBody().get("unreadCount").asInt()).isEqualTo(3);
        assertThat(admin.get("/api/budgets/"+id+"/adjustments").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(admin.put("/api/budgets/"+id,new AdjustmentInput("999","fake",List.of(50),"NONE")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(admin.put("/api/budget-notifications/"+notices.get(0).id()+"/read",null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(admin.get("/api/budgets").getBody().get("items").size()).isZero();
        assertThat(rest.getForEntity("/api/budgets",String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var headers=new HttpHeaders();headers.set("Cookie",user.cookie);headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(rest.postForEntity("/api/budgets",new HttpEntity<>(monthly("2026-10","FOOD","JPY","50","NONE"),headers),String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    /** 验证标准周、季度、年度及自定义日期边界，拒绝不合法金额精度、阈值和收入分类。 */
    @Test void customAndCalendarPeriodsValidateStrictly() {
        for(var input : List.of(new Input("Week","TOTAL","JPY","WEEK",LocalDate.of(2026,10,5),LocalDate.of(2026,10,11),"100",List.of(25,75),"NONE"),
                new Input("Quarter","TOTAL","JPY","QUARTER",LocalDate.of(2026,10,1),LocalDate.of(2026,12,31),"100",List.of(50),"NONE"),
                new Input("Year","TOTAL","JPY","YEAR",LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),"100",List.of(80),"NONE"),
                new Input("Custom","FOOD","CNY","CUSTOM",LocalDate.of(2026,10,3),LocalDate.of(2026,10,19),"12.34",List.of(50),"NONE")))assertThat(budgets.create(USER,input).id()).isPositive();
        for(var input : List.of(monthly("2026-10","SALARY","JPY","100","NONE"),monthly("2026-10","FOOD","JPY","1.01","NONE"),
                monthly("2026-10","FOOD","JPY","0","NONE"),monthly("2026-10","FOOD","ZZZ","100","NONE"),
                new Input("Bad","TOTAL","JPY","MONTH",LocalDate.of(2026,10,2),LocalDate.of(2026,10,31),"100",List.of(50),"NONE"),
                new Input("Bad","TOTAL","JPY","WEEK",LocalDate.of(2026,10,5),LocalDate.of(2026,10,11),"100",List.of(50),"ONCE"),
                new Input("Bad","TOTAL","JPY","MONTH",LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"100",List.of(50,50),"NONE")))
            assertThatThrownBy(() -> budgets.create(USER,input)).isInstanceOf(BudgetException.class);
    }
    /** 验证累积和仅一次结转的差异，跨月重复刷新不重复增加，补录过去支出和调整会修正结转链。 */
    @Test void rolloverModesReconcileLateExpensesAndAdjustments() {
        var account=ledger.createAccount(USER,new LedgerModels.AccountInput("Cash","JPY"));
        entry(USER,account.id(),"EXPENSE","40","2026-09-09","FOOD");
        var cumulative=budgets.create(USER,monthly("2026-09","FOOD","JPY","100","CUMULATIVE"));
        var once=budgets.create(USER,monthly("2026-09","SHOPPING","JPY","100","ONCE"));
        var october=budgets.overview(USER,LocalDate.of(2026,10,1),LocalDate.of(2026,10,31)).items();
        assertThat(october.stream().filter(v -> v.category().equals("FOOD")).findFirst().orElseThrow().carryIn()).isEqualTo("60");
        assertThat(october.stream().filter(v -> v.category().equals("SHOPPING")).findFirst().orElseThrow().carryIn()).isEqualTo("100");
        time("2026-11-01T00:00:00Z");budgets.refresh(USER);
        var november=budgets.overview(USER,LocalDate.of(2026,11,1),LocalDate.of(2026,11,30)).items();
        assertThat(november.stream().filter(v -> v.category().equals("FOOD")).findFirst().orElseThrow().carryIn()).isEqualTo("160");
        assertThat(november.stream().filter(v -> v.category().equals("SHOPPING")).findFirst().orElseThrow().carryIn()).isEqualTo("100");
        entry(USER,account.id(),"EXPENSE","30","2026-09-10","FOOD");
        var food=budgets.overview(USER,LocalDate.of(2026,11,1),LocalDate.of(2026,11,30)).items().stream().filter(v -> v.category().equals("FOOD")).findFirst().orElseThrow();
        assertThat(food.carryIn()).isEqualTo("130");budgets.refresh(USER);assertThat(view(USER,food.id()).carryIn()).isEqualTo("130");
        budgets.adjust(USER,cumulative.id(),new AdjustmentInput("200","additional budget",List.of(50,80,100),"CUMULATIVE"));
        assertThat(view(USER,food.id()).carryIn()).isEqualTo("230");
        budgets.adjust(USER,once.id(),new AdjustmentInput("100","stop carry",List.of(50),"NONE"));
        var octoberOnce=budgets.overview(USER,LocalDate.of(2026,10,1),LocalDate.of(2026,10,31)).items().stream().filter(v -> v.category().equals("SHOPPING")).findFirst().orElseThrow();
        assertThat(octoberOnce.carryIn()).isEqualTo("0");
    }
    /** 验证用户时区的月底边界，未来预算不通知，按当地日期开启和结转。 */
    @Test void timezoneAndNotStartedBudgetsUseLocalCalendar() {
        time("2026-10-31T16:00:00Z");
        var source=budgets.create(USER,monthly("2026-10","TOTAL","JPY","100","CUMULATIVE"));
        assertThat(budgets.overview(USER,null,null).today()).isEqualTo(LocalDate.of(2026,11,1));
        assertThat(budgets.overview(USER,null,null).items()).anyMatch(v -> v.start().equals(LocalDate.of(2026,11,1)) && v.carryIn().equals("100"));
        var future=budgets.create(USER,monthly("2026-12","FOOD","JPY","50","NONE"));
        var account=ledger.createAccount(USER,new LedgerModels.AccountInput("Cash","JPY"));entry(USER,account.id(),"EXPENSE","60","2026-12-02","FOOD");
        assertThat(view(USER,future.id()).status()).isEqualTo("NOT_STARTED");assertThat(budgets.notices(USER)).noneMatch(n -> n.budgetId()==future.id());
        assertThat(view(USER,source.id()).forecast()).isEqualTo("0");
    }
    /** 验证预算调整审计和历史分类对比，金额与执行率正确且按币种分组。 */
    @Test void adjustmentAuditAndMonthlyCategoryHistoryKeepExactAmounts() {
        var account=ledger.createAccount(USER,new LedgerModels.AccountInput("Cash","CNY"));
        var september=budgets.create(USER,monthly("2026-09","FOOD","CNY","100.00","NONE"));
        entry(USER,account.id(),"EXPENSE","120.50","2026-09-15","FOOD");
        var updated=budgets.adjust(USER,september.id(),new AdjustmentInput("110.00","medical plan change",List.of(60,90,100),"NONE"));
        assertThat(updated.updatedBy()).isEqualTo(USER);assertThat(updated.createdAt()).isEqualTo(september.createdAt());
        var audit=budgets.adjustments(USER,september.id()).get(0);assertThat(audit.oldAmount()).isEqualByComparingTo("100");assertThat(audit.newAmount()).isEqualByComparingTo("110");assertThat(audit.createdBy()).isEqualTo(USER);
        var history=budgets.history(USER,"2026-09","2026-09");assertThat(history.months()).hasSize(1);assertThat(history.categories()).hasSize(1);
        var category=history.categories().get(0);assertThat(category.overspentPeriods()).isEqualTo(1);assertThat(category.totalOverage()).isEqualTo("10.50");assertThat(category.executionRate()).isEqualTo("109.55");
    }
    /** 验证模板及月份复制原子提交、配置内容准确、冲突不覆盖或留下部分预算，模板归属隔离。 */
    @Test void templatesAndCopyApplyAtomicallyWithoutCopyingConsumption() {
        budgets.create(USER,monthly("2026-10","TOTAL","JPY","200000","NONE"));budgets.create(USER,monthly("2026-10","FOOD","JPY","50000","NONE"));
        var template=budgets.saveTemplate(USER,new SaveTemplate("Normal month","2026-10"));assertThat(template.items()).hasSize(2);
        var applied=budgets.applyTemplate(USER,template.id(),new ApplyTemplate("2026-11"));assertThat(applied).hasSize(2);assertThat(applied).allMatch(v -> v.spent().equals("0") && v.carryIn().equals("0"));
        assertThat(budgets.copyMonth(USER,new CopyMonth("2026-11","2026-12"))).hasSize(2);
        budgets.create(USER,monthly("2027-01","FOOD","JPY","500","NONE"));
        assertThatThrownBy(() -> budgets.applyTemplate(USER,template.id(),new ApplyTemplate("2027-01"))).isInstanceOf(BudgetException.class);
        assertThat(budgets.overview(USER,LocalDate.of(2027,1,1),LocalDate.of(2027,1,31)).items()).hasSize(1);
        assertThat(budgets.templates(ADMIN)).isEmpty();assertThatThrownBy(() -> budgets.applyTemplate(ADMIN,template.id(),new ApplyTemplate("2027-02"))).isInstanceOf(BudgetException.class);
        var browser=new Browser(USER);assertThat(browser.post("/api/budget-templates/"+template.id()+"/apply",new ApplyTemplate("2027-03")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    /** 验证同一用户并发刷新结转和预警时只产生一个目标月份和一组消息。 */
    @Test void concurrentRefreshDoesNotDuplicateCarryOrAlerts() throws Exception {
        var source=budgets.create(USER,monthly("2026-09","TOTAL","JPY","100","CUMULATIVE"));
        var account=ledger.createAccount(USER,new LedgerModels.AccountInput("Cash","JPY"));entry(USER,account.id(),"EXPENSE","180","2026-10-09","FOOD");
        var first=CompletableFuture.runAsync(() -> budgets.refresh(USER));var second=CompletableFuture.runAsync(() -> budgets.refresh(USER));
        CompletableFuture.allOf(first,second).get(15,TimeUnit.SECONDS);
        var october=budgets.overview(USER,LocalDate.of(2026,10,1),LocalDate.of(2026,10,31)).items();assertThat(october).hasSize(1);assertThat(october.get(0).carryIn()).isEqualTo("100");
        assertThat(budgets.notices(USER)).hasSize(2);assertThat(source.id()).isPositive();
    }
    /** 验证相同日期的自定义周期可以独立存在，不会阻断标准月预算结转和真实收支入库。 */
    @Test void customPeriodDoesNotBlockMonthlyRollover() {
        budgets.create(USER,monthly("2026-10","TOTAL","JPY","100","CUMULATIVE"));
        budgets.create(USER,new Input("Custom month","TOTAL","JPY","CUSTOM",LocalDate.of(2026,11,1),LocalDate.of(2026,11,30),"80",List.of(50),"NONE"));
        time("2026-11-01T00:00:00Z");var account=ledger.createAccount(USER,new LedgerModels.AccountInput("Cash","JPY"));
        entry(USER,account.id(),"EXPENSE","70","2026-11-01","FOOD");
        var targets=budgets.overview(USER,LocalDate.of(2026,11,1),LocalDate.of(2026,11,30)).items();assertThat(targets).hasSize(2);
        assertThat(targets.stream().filter(v -> v.period().equals("MONTH")).findFirst().orElseThrow().carryIn()).isEqualTo("100");
        assertThat(targets.stream().filter(v -> v.period().equals("CUSTOM")).findFirst().orElseThrow().carryIn()).isEqualTo("0");
    }
    /** 独立测试浏览器，会话和 CSRF 与真实前端相同，不使用伪造用户 ID。 */
    private class Browser {
        String cookie,token;
        /** 获取令牌、登录、刷新令牌以准备预算接口测试。 */
        Browser(String username) { csrf();assertThat(post("/api/auth/login",Map.of("username",username,"password",PASSWORD)).getStatusCode()).isEqualTo(HttpStatus.OK);csrf(); }
        /** 刷新当前会话 CSRF 令牌。 */
        void csrf() { token=get("/api/auth/csrf").getBody().get("token").asText(); }
        /** 发送认证 GET 请求并解析 JSON。 */
        ResponseEntity<JsonNode> get(String path) { return exchange(path,HttpMethod.GET,null); }
        /** 发送带 CSRF 的 POST 请求。 */
        ResponseEntity<JsonNode> post(String path,Object body) { return exchange(path,HttpMethod.POST,body); }
        /** 发送带 CSRF 的 PUT 请求。 */
        ResponseEntity<JsonNode> put(String path,Object body) { return exchange(path,HttpMethod.PUT,body); }
        /** 保留服务端 Cookie 并执行真实 HTTP 调用。 */
        ResponseEntity<JsonNode> exchange(String path,HttpMethod method,Object body) {
            var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(cookie!=null)headers.set("Cookie",cookie);if(token!=null)headers.set("X-CSRF-TOKEN",token);
            var response=rest.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class);var cookies=response.getHeaders().get("Set-Cookie");if(cookies!=null)cookies.stream().filter(c -> c.startsWith("JSESSIONID=")).forEach(c -> cookie=c.split(";",2)[0]);return response;
        }
    }
}
