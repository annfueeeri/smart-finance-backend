package collector.bu;

import collector.bu.exception.LedgerException;
import collector.bu.service.LedgerFileService;
import collector.bu.service.LedgerService;
import collector.bu.service.AccountingService;
import collector.bu.service.ReportExportService;
import collector.bu.service.ReportService;

import collector.bu.dao.UserDao;
import collector.bu.entity.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import static org.assertj.core.api.Assertions.*;
import static collector.bu.model.ledger.LedgerModels.*;
import static collector.bu.model.report.ReportModels.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:reporttests;MODE=MySQL;DB_CLOSE_DELAY=-1","app.budget.scheduler-delay-ms=3600000"})
class ReportIntegrationTests {
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserDao users;
    @Autowired PasswordEncoder encoder;
    @Autowired LedgerService ledger;
    @Autowired AccountingService accounting;
    @Autowired ReportService reports;
    @Autowired ReportExportService exports;
    @Autowired LedgerFileService files;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired collector.bu.service.BudgetService budgets;
    private static final String USER="report-test-user",ADMIN="report-test-admin",PASSWORD="Report-testing-password-123";
    /** 按外键依赖清理本人财务测试数据，管理员保留独立身份验证隔离。 */
    @BeforeEach void setup(){
        for(var table:List.of("account_valuation","ledger_transfer","ledger_transaction","ledger_account","budget_notification","budget_adjustment","budget_plan","budget_template"))
            jdbc.update("DELETE FROM "+table+" WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'report-test-%')");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'report-test-%'");users.insert(USER,encoder.encode(PASSWORD));users.insert(ADMIN,encoder.encode(PASSWORD),UserRole.ADMIN);
    }
    /** 创建具有明确期初余额的日元账户。 */
    private long account(String name,String type,String balance){return ledger.createAccount(USER,new AccountInput(name,"JPY",type,balance,LocalDate.of(2026,10,1))).id();}
    /** 新增带标签的真实收入或支出流水。 */
    private void entry(long id,String kind,String amount,String date,String category,String merchant,String...tags){ledger.createEntry(USER,new EntryInput(id,kind,amount,LocalDate.parse(date),category,merchant,"",List.of(tags)));}
    /** 指定报表闭区间，默认按日并包含所有账户。 */
    private Filter filter(String start,String end){return new Filter(LocalDate.parse(start),LocalDate.parse(end),"DAY",null,null,null,null,null);}
    /** 验证期初余额、内部转账、消费与负债分别计算，内部转账不抬高收入或支出。 */
    @Test void cashFlowAndAssetsReconcileWithoutCountingTransfersAsIncome(){
        long bank=account("Bank","BANK","1000"),cash=account("Cash","CASH","100"),debt=account("Loan","LIABILITY","-300");
        entry(bank,"INCOME","500","2026-10-02","SALARY","");entry(cash,"EXPENSE","50","2026-10-03","FOOD","超市","家庭","旅游");
        accounting.transfer(USER,new TransferInput(bank,cash,"200",LocalDate.of(2026,10,4),"Internal"));
        var r=reports.report(USER,filter("2026-10-01","2026-10-04")).currencies().get(0);
        assertThat(r.summary()).isEqualTo(new Summary("500","50","450","13",1,1));
        assertThat(r.cashFlow().stream().filter(c->c.accountId()==bank).findFirst().orElseThrow().closingBalance()).isEqualTo("1300");
        var cf=r.cashFlow().stream().filter(c->c.accountId()==cash).findFirst().orElseThrow();
        assertThat(cf.internalInflow()).isEqualTo("200");assertThat(cf.inflow()).isEqualTo("0");assertThat(cf.closingBalance()).isEqualTo("250");
        assertThat(r.assets().get(0).unknownAccounts()).isEqualTo(3);
        var last=r.assets().get(r.assets().size()-1);assertThat(last.assets()).isEqualTo("1550");assertThat(last.liabilities()).isEqualTo("300");assertThat(last.netAssets()).isEqualTo("1250");
        assertThat(r.tags()).extracting(Ranking::name).containsExactlyInAnyOrder("家庭","旅游");assertThat(r.categories().get(0).percentage()).isEqualTo("100.00");
        assertThat(debt).isPositive();
    }
    /** 验证日终估值覆盖当日流水、同日最后校准生效，并以余额调整列保持现金流等式。 */
    @Test void endOfDayValuationPreservesHistoryAndFutureMovements(){
        long bank=account("Fund","INVESTMENT","1000");entry(bank,"EXPENSE","50","2026-10-02","FOOD","");
        accounting.value(USER,bank,new ValuationInput(LocalDate.of(2026,10,2),"1200","Market"));
        accounting.value(USER,bank,new ValuationInput(LocalDate.of(2026,10,2),"1250","Corrected"));
        entry(bank,"EXPENSE","30","2026-10-03","TRANSPORT","");
        var r=reports.report(USER,filter("2026-10-01","2026-10-03")).currencies().get(0);var cf=r.cashFlow().get(0);
        assertThat(cf.closingBalance()).isEqualTo("1220");assertThat(cf.balanceAdjustment()).isEqualTo("1300");
        assertThat(r.balances().get(0).history()).extracting(AccountPoint::balance).containsExactly("0","1000","1250","1220");
        assertThat(accounting.values(USER,bank)).hasSize(2);
        assertThatThrownBy(()->accounting.value(USER,bank,new ValuationInput(LocalDate.of(2026,9,30),"1",""))).isInstanceOf(LedgerException.class);
    }
    /** 验证标签及分类筛选只作用于收支分析，完整账户现金流和余额不会被标签截断。 */
    @Test void customFiltersAndSeparateCurrenciesKeepCorrectTotals(){
        long bank=account("Bank","BANK","1000");entry(bank,"EXPENSE","100","2026-10-02","FOOD","A","家庭");entry(bank,"EXPENSE","200","2026-10-03","SHOPPING","B","旅游");
        long usd=ledger.createAccount(USER,new AccountInput("USD","USD")).id();entry(usd,"INCOME","1.23","2026-10-03","SALARY","");
        var r=reports.report(USER,new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,4),"DAY",bank,null,"EXPENSE","FOOD","家庭")).currencies().get(0);
        assertThat(r.summary().expense()).isEqualTo("100");assertThat(r.cashFlow().get(0).outflow()).isEqualTo("300");assertThat(r.balances().get(0).balance()).isEqualTo("700");
        assertThat(reports.report(USER,filter("2026-10-01","2026-10-04")).currencies()).extracting(CurrencyReport::currency).containsExactlyInAnyOrder("JPY","USD");
    }
    /** 验证完整月份环比补齐月末、同比闰年边界、比较基数为零返回null和零流水时间段。 */
    @Test void calendarComparisonsAndGroupingAreExplicit(){
        long id=account("Cash","CASH","0");entry(id,"INCOME","100","2026-01-31","SALARY","");entry(id,"INCOME","200","2026-02-28","SALARY","");
        var r=reports.report(USER,new Filter(LocalDate.of(2026,2,1),LocalDate.of(2026,2,28),"DAY",null,null,null,null,null)).currencies().get(0);
        assertThat(r.monthOverMonth().end()).isEqualTo(LocalDate.of(2026,1,31));assertThat(r.monthOverMonth().income().percentage()).isEqualTo("100.00");assertThat(r.yearOverYear().income().percentage()).isNull();assertThat(r.trend()).hasSize(28);assertThat(r.trend().get(0).income()).isEqualTo("0");
        var leap=reports.report(USER,new Filter(LocalDate.of(2024,2,1),LocalDate.of(2024,2,29),"MONTH",null,null,null,null,null)).currencies().get(0);assertThat(leap.yearOverYear().end()).isEqualTo(LocalDate.of(2023,2,28));
        for(var grouping:List.of("WEEK","MONTH","YEAR"))assertThat(reports.report(USER,new Filter(LocalDate.of(2026,1,1),LocalDate.of(2026,3,31),grouping,null,null,null,null,null)).currencies().get(0).trend()).isNotEmpty();
    }
    /** 验证汇总不会复用流水导出的10000条限制，预聚合保留完整金额和交易次数。 */
    @Test void aggregateIncludesMoreThanTenThousandTransactions(){
        long id=account("Cash","CASH","0"),userId=users.findByUsername(USER).orElseThrow().id();
        var rows=new ArrayList<Object[]>();for(int i=0;i<10001;i++)rows.add(new Object[]{userId,id,"JPY","EXPENSE",1,LocalDate.of(2026,10,1),"FOOD",USER,USER});
        jdbc.batchUpdate("INSERT INTO ledger_transaction(user_id,account_id,currency,kind,amount,transaction_date,category,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?)",rows);
        var summary=reports.report(USER,filter("2026-10-01","2026-10-01")).currencies().get(0).summary();assertThat(summary.expense()).isEqualTo("10001");assertThat(summary.expenseCount()).isEqualTo(10001);
    }
    /** 实际解码CSV、Excel和PDF，检查精确金额、原生图表、中文字体及完整分页明细。 */
    @Test void exportsContainSummariesChartsAndChineseText() throws Exception {
        long id=account("银行","BANK","1000");entry(id,"EXPENSE","123","2026-10-02","FOOD","=1+2","家庭");
        var r=reports.report(USER,filter("2026-10-01","2026-10-03"));
        var csv=new String(exports.export(r,"csv"),java.nio.charset.StandardCharsets.UTF_8);assertThat(csv).contains("SUMMARY","123","'=1+2","家庭","ACCOUNT_BALANCE","YOY");
        try(var book=new XSSFWorkbook(new java.io.ByteArrayInputStream(exports.export(r,"xlsx")))){assertThat(book.getSheet("Charts JPY").getDrawingPatriarch().getCharts()).hasSize(3);assertThat(book.getSheet("Report Data").getRow(1).getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);}
        try(var pdf=Loader.loadPDF(exports.export(r,"pdf"))){assertThat(pdf.getNumberOfPages()).isGreaterThan(1);assertThat(java.text.Normalizer.normalize(new PDFTextStripper().getText(pdf),java.text.Normalizer.Form.NFKC)).contains("财务报表","收入","123","家庭","银行");}
        assertThatThrownBy(()->exports.export(r,"exe")).isInstanceOf(LedgerException.class);
    }
    /** 验证真实会话、CSRF、管理员不能查看他人财务、非法转账和完整接口参数。 */
    @Test void realEndpointsEnforceOwnershipValidationAndCsrf(){
        var user=new Browser(USER);var admin=new Browser(ADMIN);long id=account("Cash","CASH","10"),other=account("Bank","BANK","20");
        assertThat(user.get("/api/reports/financial?start=2026-10-01&end=2026-10-03&grouping=DAY").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(admin.get("/api/reports/financial?accountId="+id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(admin.get("/api/accounts/"+id+"/valuations").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(admin.post("/api/transfers",new TransferInput(id,other,"1",LocalDate.of(2026,10,2),"")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(user.post("/api/transfers",new TransferInput(id,id,"1",LocalDate.of(2026,10,2),"")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(user.post("/api/transfers",new TransferInput(id,other,"1.01",LocalDate.of(2026,10,2),"")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var made=user.post("/api/transfers",new TransferInput(id,other,"1",LocalDate.of(2026,10,2),""));assertThat(made.getStatusCode()).isEqualTo(HttpStatus.CREATED);assertThat(made.getBody().get("createdBy").asText()).isEqualTo(USER);assertThat(made.getBody().get("deleted").asBoolean()).isFalse();
        assertThat(user.post("/api/accounts/"+id+"/valuations",new ValuationInput(LocalDate.of(2026,10,2),"9","")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(user.exchange("/api/accounts/"+id,HttpMethod.PUT,new AccountProfile("Updated","EWALLET")).getBody().get("updatedBy").asText()).isEqualTo(USER);
        for(var query:List.of("start=2026-10-04&end=2026-10-01","grouping=BAD","currency=ZZZ","kind=TRANSFER","category=BAD","tag=bad|tag","start=2025-01-01&end=2026-10-01&grouping=DAY"))assertThat(user.get("/api/reports/financial?"+query).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/reports/financial",String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var noCsrf=new HttpHeaders();noCsrf.set("Cookie",user.cookie);assertThat(rest.postForEntity("/api/transfers",new HttpEntity<>(new TransferInput(id,other,"1",LocalDate.of(2026,10,2),""),noCsrf),String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var response=rest.exchange("/api/reports/export?format=csv",HttpMethod.GET,new HttpEntity<>(null,user.headers()),byte[].class);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("financial-report.csv");
    }
    /** 验证带标签CSV可往返并识别标准化重复，转账不消耗预算，日均金额仅舍入一次。 */
    @Test void taggedImportRoundTripAndTransfersLeaveBudgetsUnchanged() throws Exception {
        long id=account("Bank","BANK","0"),other=account("Cash","CASH","0");
        var input=new collector.bu.model.budget.BudgetModels.Input("Monthly","TOTAL","JPY","MONTH",LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"100",List.of(50,80,100),"NONE");
        budgets.create(USER,input);accounting.transfer(USER,new TransferInput(id,other,"999",LocalDate.of(2026,10,2),""));
        assertThat(budgets.overview(USER,LocalDate.of(2026,10,1),LocalDate.of(2026,10,31)).items().get(0).spent()).isEqualTo("0");
        entry(id,"EXPENSE","10","2026-10-02","FOOD","Store"," 家庭 ","旅行","家庭");
        var mapping=new Mapping(Map.of("kind",0,"amount",1,"date",2,"account",3,"category",4,"merchant",5,"note",6,"currency",7,"tags",8),null,null,null);
        var file=new org.springframework.mock.web.MockMultipartFile("file","tagged.csv","text/csv",files.export(USER,"csv",null,null,null,null));
        var preview=files.preview(USER,file,mapper.writeValueAsString(mapping));assertThat(preview.invalid()).isZero();assertThat(preview.duplicates()).isEqualTo(1);assertThat(preview.rows().get(0).entry().tags()).containsExactly("家庭","旅行");
        long usd=ledger.createAccount(USER,new AccountInput("USD","USD")).id();
        entry(usd,"EXPENSE","0.50","2026-10-01","FOOD","");
        var r=reports.report(USER,new Filter(LocalDate.of(2026,10,1),LocalDate.of(2027,1,9),"DAY",usd,null,null,null,null));
        assertThat(r.currencies().get(0).summary().averageDailyExpense()).isEqualTo("0.00");
        entry(id,"EXPENSE","1","2026-10-03","FOOD","","Trip");
        var lowercase=new EntryInput(id,"EXPENSE","1",LocalDate.of(2026,10,3),"FOOD","","",List.of("trip"));
        assertThat(ledger.importEntries(USER,new ImportInput(List.of(lowercase),true)).imported()).isEqualTo(1);
        assertThat(reports.options(USER).tags()).contains("Trip","trip");
        assertThat(reports.report(USER,new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,4),"DAY",id,null,null,null,"Trip")).currencies().get(0).summary().expense()).isEqualTo("1");
        for(var bad:List.of("bad|tag","bad,tag","bad，tag","bad\n\nline"))assertThatThrownBy(()->ledger.createEntry(USER,new EntryInput(id,"EXPENSE","1",LocalDate.of(2026,10,2),"FOOD","","",List.of(bad)))).isInstanceOf(LedgerException.class);
    }
    /** 使用真实HTTP登录与Cookie令牌，避免测试绕过认证过滤器。 */
    private class Browser{
        String cookie,token;
        /** 获取令牌、登录并刷新登录后令牌。 */
        Browser(String username){token=get("/api/auth/csrf").getBody().get("token").asText();assertThat(post("/api/auth/login",Map.of("username",username,"password",PASSWORD)).getStatusCode()).isEqualTo(HttpStatus.OK);token=get("/api/auth/csrf").getBody().get("token").asText();}
        /** 创建本次会话请求头。 */
        HttpHeaders headers(){var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(cookie!=null)h.set("Cookie",cookie);if(token!=null)h.set("X-CSRF-TOKEN",token);return h;}
        /** 发起查询。 */
        ResponseEntity<JsonNode> get(String path){return exchange(path,HttpMethod.GET,null);}
        /** 发起新增。 */
        ResponseEntity<JsonNode> post(String path,Object body){return exchange(path,HttpMethod.POST,body);}
        /** 发起完整请求并保存服务端会话Cookie。 */
        ResponseEntity<JsonNode> exchange(String path,HttpMethod method,Object body){var r=rest.exchange(path,method,new HttpEntity<>(body,headers()),JsonNode.class);var cookies=r.getHeaders().get("Set-Cookie");if(cookies!=null)cookies.stream().filter(c->c.startsWith("JSESSIONID=")).forEach(c->cookie=c.split(";",2)[0]);return r;}
    }
}
