package collector.bu;

import collector.bu.exception.LedgerException;
import collector.bu.service.ledger.LedgerFiles;
import collector.bu.service.ledger.LedgerService;

import collector.bu.dao.UserDao;
import collector.bu.entity.UserRole;
import com.fasterxml.jackson.databind.*;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import static org.assertj.core.api.Assertions.*;
import static collector.bu.model.ledger.LedgerModels.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerIntegrationTests {
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserDao users;
    @Autowired PasswordEncoder encoder;
    @Autowired LedgerService service;
    @Autowired LedgerFiles files;
    @Autowired ObjectMapper mapper;
    private static final String PASSWORD="Ledger-testing-password-123";
    /** 清理本套测试的流水、账户及账号，创建独立管理员和普通用户。 */
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM ledger_transaction WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'ledger-test-%')");
        jdbc.update("DELETE FROM ledger_account WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'ledger-test-%')");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'ledger-test-%'");
        users.insert("ledger-test-user",encoder.encode(PASSWORD));
        users.insert("ledger-test-admin",encoder.encode(PASSWORD),UserRole.ADMIN);
    }
    /** 构造正常支出请求，为隔离、精度及重复测试提供一致字段。 */
    private EntryInput input(long account,String amount) { return new EntryInput(account,"EXPENSE",amount,LocalDate.of(2026,10,9),"FOOD","Store","Note"); }
    /** 验证真实 HTTP 收支流程、金额字符串、审计字段、账户隔离与筛选。 */
    @Test void realRequestsKeepPersonalAccountsAndRecordsIsolated() {
        var user=new Browser("ledger-test-user"); var admin=new Browser("ledger-test-admin");
        var account=user.post("/api/accounts",Map.of("name","Cash","currency","CNY"));
        assertThat(account.getStatusCode()).isEqualTo(HttpStatus.CREATED); long id=account.getBody().get("id").asLong();
        assertThat(account.getBody().get("createdBy").asText()).isEqualTo("ledger-test-user");
        assertThat(account.getBody().get("deleted").asBoolean()).isFalse();
        var entry=user.post("/api/transactions",input(id,"12.34"));
        assertThat(entry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(entry.getBody().get("amount").isTextual()).isTrue();
        assertThat(entry.getBody().get("updatedBy").asText()).isEqualTo("ledger-test-user");
        assertThat(user.get("/api/transactions?kind=EXPENSE&start=2026-10-09&end=2026-10-09&size=1").getBody().get("total").asInt()).isEqualTo(1);
        assertThat(user.get("/api/transactions?kind=INCOME").getBody().get("total").asInt()).isZero();
        assertThat(admin.get("/api/transactions").getBody().get("total").asInt()).isZero();
        assertThat(admin.post("/api/transactions",input(id,"1")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(admin.get("/api/transactions?accountId="+id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(user.get("/api/transactions?start=not-a-date").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(user.get("/api/transactions?start=2026-12-01&end=2026-01-01").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/transactions",String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var noCsrf=new HttpHeaders(); noCsrf.set("Cookie",user.cookie);
        assertThat(rest.postForEntity("/api/accounts",new HttpEntity<>(Map.of("name","unsafe"),noCsrf),String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    /** 验证金额正数、币种精度、分类方向与账户名称重复都在后端拒绝。 */
    @Test void invalidMoneyCategoriesAndDuplicateAccountsAreRejected() {
        var user=new Browser("ledger-test-user"); long account=service.createAccount("ledger-test-user",new AccountInput("JPY cash","JPY")).id();
        for (var amount : List.of("0","-1","1.01","1000000000000","1e3"))
            assertThat(user.post("/api/transactions",input(account,amount)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var wrong=new EntryInput(account,"INCOME","100",LocalDate.now(),"FOOD","","");
        assertThat(user.post("/api/transactions",wrong).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(user.post("/api/accounts",Map.of("name","JPY cash","currency","JPY")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(user.post("/api/accounts",Map.of("name","Bad","currency","ZZZ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    /** 验证 CSV 映射预览不写入、逐行错误、文件内及数据库重复，以及默认去重和事务回滚。 */
    @Test void csvPreviewDetectsDuplicatesAndErrorsBeforeAtomicImport() throws Exception {
        long account=service.createAccount("ledger-test-user",new AccountInput("Cash","CNY")).id();
        var csv=new MockMultipartFile("file","bank.csv","text/csv",("\uFEFF金额,日期,备注\n12.34,2026/10/9,hello\n12.34,2026/10/9,hello\nabc,2026-10-09,bad\n1,2026-02-30,bad date\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var mapping=new Mapping(Map.of("amount",0,"date",1,"note",2),"EXPENSE",account,"FOOD");
        assertThat(files.inspect(csv).totalRows()).isEqualTo(4);
        var preview=files.preview("ledger-test-user",csv,mapper.writeValueAsString(mapping));
        assertThat(preview.valid()).isEqualTo(2); assertThat(preview.duplicates()).isEqualTo(1); assertThat(preview.invalid()).isEqualTo(2);
        assertThat(service.list("ledger-test-user",null,null,null,null,0,20).total()).isZero();
        var entries=preview.rows().stream().filter(r -> r.errors().isEmpty()).map(PreviewRow::entry).toList();
        var imported=service.importEntries("ledger-test-user",new ImportInput(entries,null));
        assertThat(imported.imported()).isEqualTo(1); assertThat(imported.skipped()).isEqualTo(1);
        assertThat(files.preview("ledger-test-user",csv,mapper.writeValueAsString(mapping)).duplicates()).isEqualTo(2);
        var bad=input(account,"0"); var good=input(account,"99");
        assertThatThrownBy(() -> service.importEntries("ledger-test-user",new ImportInput(List.of(good,bad),true))).isInstanceOf(LedgerException.class);
        assertThat(service.list("ledger-test-user",null,null,null,null,0,20).total()).isEqualTo(1);
        var http=new Browser("ledger-test-user");
        assertThat(http.post("/api/transactions/import",new ImportInput(entries,true)).getBody().get("skipped").asInt()).isEqualTo(2);
    }
    /** 验证两种 Excel 格式读取真实日期单元格，并拒绝公式及不合法映射。 */
    @Test void excelFormatsReadDatesAndRejectFormulas() throws Exception {
        long account=service.createAccount("ledger-test-user",new AccountInput("Cash","CNY")).id();
        for (boolean xlsx : List.of(true,false)) {
            try (Workbook workbook=xlsx ? new XSSFWorkbook() : new HSSFWorkbook()) {
                var sheet=workbook.createSheet(); var header=sheet.createRow(0); header.createCell(0).setCellValue("amount"); header.createCell(1).setCellValue("date");
                var row=sheet.createRow(1); row.createCell(0).setCellValue(42.5); var cell=row.createCell(1); cell.setCellValue(LocalDate.of(2026,10,8));
                var style=workbook.createCellStyle(); style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd")); cell.setCellStyle(style);
                var output=new ByteArrayOutputStream(); workbook.write(output);
                var file=new MockMultipartFile("file",xlsx ? "bank.xlsx" : "bank.xls","application/octet-stream",output.toByteArray());
                var mapping=mapper.writeValueAsString(new Mapping(Map.of("amount",0,"date",1),"INCOME",account,"SALARY"));
                var preview=files.preview("ledger-test-user",file,mapping);
                assertThat(preview.valid()).isEqualTo(1); assertThat(preview.rows().get(0).entry().date()).isEqualTo(LocalDate.of(2026,10,8));
                row.getCell(0).setCellFormula("1+2"); output.reset(); workbook.write(output);
                var formula=new MockMultipartFile("file",xlsx ? "bad.xlsx" : "bad.xls","application/octet-stream",output.toByteArray());
                assertThatThrownBy(() -> files.inspect(formula)).isInstanceOf(LedgerException.class);
                assertThatThrownBy(() -> files.preview("ledger-test-user",file,"{}" )).isInstanceOf(LedgerException.class);
            }
        }
    }
    /** 验证筛选导出只包含本人记录，CSV 公式文本转义且重新导入可正确识别重复，XLSX 不生成公式。 */
    @Test void exportsFilterOwnerAndRoundTripWithoutExecutingFormulaText() throws Exception {
        long account=service.createAccount("ledger-test-user",new AccountInput("Cash","CNY")).id();
        service.createEntry("ledger-test-user",new EntryInput(account,"EXPENSE","12.34",LocalDate.of(2026,10,9),"FOOD","=1+2","quote,\"hello\"\nline"));
        long foreign=service.createAccount("ledger-test-admin",new AccountInput("Secret","CNY")).id(); service.createEntry("ledger-test-admin",input(foreign,"999"));
        var bytes=files.export("ledger-test-user","csv","EXPENSE",null,null,null);
        var text=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).contains("'=1+2").doesNotContain("Secret","999");
        var mapping=new Mapping(Map.of("kind",0,"amount",1,"date",2,"account",3,"category",4,"merchant",5,"note",6,"currency",7),null,null,null);
        var preview=files.preview("ledger-test-user",new MockMultipartFile("file","export.csv","text/csv",bytes),mapper.writeValueAsString(mapping));
        assertThat(preview.valid()).isEqualTo(1); assertThat(preview.duplicates()).isEqualTo(1);
        var xlsx=files.export("ledger-test-user","xlsx",null,null,null,null);
        try (var workbook=WorkbookFactory.create(new java.io.ByteArrayInputStream(xlsx))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(5).getCellType()).isEqualTo(CellType.STRING);
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(5).getStringCellValue()).isEqualTo("=1+2");
        }
        var user=new Browser("ledger-test-user"); var response=rest.exchange("/api/transactions/export?format=xlsx",HttpMethod.GET,new HttpEntity<>(null,user.headers()),byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("transactions.xlsx");
    }
    /** 验证相同用户并发导入不会互相穿透去重，同时禁止导入别人拥有的账户。 */
    @Test void concurrentImportsSerializeAndRejectForeignAccounts() throws Exception {
        long account=service.createAccount("ledger-test-user",new AccountInput("Cash","CNY")).id();
        var batch=new ImportInput(List.of(input(account,"10.5")),true);
        var one=java.util.concurrent.CompletableFuture.supplyAsync(() -> service.importEntries("ledger-test-user",batch));
        var two=java.util.concurrent.CompletableFuture.supplyAsync(() -> service.importEntries("ledger-test-user",batch));
        var first=one.get(10,java.util.concurrent.TimeUnit.SECONDS); var second=two.get(10,java.util.concurrent.TimeUnit.SECONDS);
        assertThat(first.imported()+second.imported()).isEqualTo(1); assertThat(first.skipped()+second.skipped()).isEqualTo(1);
        assertThatThrownBy(() -> service.importEntries("ledger-test-admin",batch)).isInstanceOf(LedgerException.class);
        assertThat(service.list("ledger-test-admin",null,null,null,null,0,20).total()).isZero();
        var http=new Browser("ledger-test-user");
        assertThat(http.post("/api/transactions/import",Map.of("entries",Collections.singletonList(null))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    /** 验证以单引号开头的账户与备注不会被公式转义还原误改，CSV 导出再导入保留原值。 */
    @Test void csvEscapingPreservesOriginalLeadingApostrophes() throws Exception {
        long account=service.createAccount("ledger-test-user",new AccountInput("'=bank","CNY")).id();
        for (var note : List.of("'=original","''double","'plain","@formula","+formula","-formula"))
            service.createEntry("ledger-test-user",new EntryInput(account,"EXPENSE","1",LocalDate.of(2026,10,9),"FOOD","",note));
        var bytes=files.export("ledger-test-user","csv",null,null,null,null);
        var mapping=new Mapping(Map.of("kind",0,"amount",1,"date",2,"account",3,"category",4,"merchant",5,"note",6,"currency",7),null,null,null);
        var preview=files.preview("ledger-test-user",new MockMultipartFile("file","export.csv","text/csv",bytes),mapper.writeValueAsString(mapping));
        assertThat(preview.invalid()).isZero(); assertThat(preview.duplicates()).isEqualTo(6);
        assertThat(preview.rows()).extracting(r -> r.entry().note()).containsExactlyInAnyOrder("'=original","''double","'plain","@formula","+formula","-formula");
    }
    /** 测试用独立 Cookie 会话，不把数据库用户名作为请求身份参数。 */
    private class Browser {
        String cookie,token;
        /** 通过真实 CSRF、登录和令牌刷新建立会话。 */
        Browser(String username) { csrf(); assertThat(post("/api/auth/login",Map.of("username",username,"password",PASSWORD)).getStatusCode()).isEqualTo(HttpStatus.OK); csrf(); }
        /** 请求令牌，登录后同步刷新。 */
        void csrf() { token=get("/api/auth/csrf").getBody().get("token").asText(); }
        /** 组装测试会话 Cookie 和 CSRF 请求头。 */
        HttpHeaders headers() { var headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); if(cookie!=null)headers.set("Cookie",cookie); if(token!=null)headers.set("X-CSRF-TOKEN",token); return headers; }
        /** 发送本人会话的查询请求。 */
        ResponseEntity<JsonNode> get(String path) { return exchange(path,HttpMethod.GET,null); }
        /** 发送本人会话的写入请求。 */
        ResponseEntity<JsonNode> post(String path,Object body) { return exchange(path,HttpMethod.POST,body); }
        /** 执行真实 HTTP 请求并更新服务端会话 Cookie。 */
        ResponseEntity<JsonNode> exchange(String path,HttpMethod method,Object body) {
            var response=rest.exchange(path,method,new HttpEntity<>(body,headers()),JsonNode.class);
            var cookies=response.getHeaders().get("Set-Cookie"); if(cookies!=null)cookies.stream().filter(c -> c.startsWith("JSESSIONID=")).forEach(c -> cookie=c.split(";",2)[0]); return response;
        }
    }
}
