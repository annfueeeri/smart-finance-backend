package collector.bu;

import collector.bu.dao.UserDao;
import collector.bu.entity.UserRole;
import collector.bu.service.BudgetService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class FamilyIntegrationTests {
    @Autowired TestRestTemplate rest;
    @Autowired UserDao users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired BudgetService budgets;
    @Autowired collector.bu.service.LedgerFileService files;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    private static final String PASSWORD="Family-testing-password-123";
    private String owner,member,outsider;
    /** 每项测试创建独立账号，避免家庭记录或历史审计影响其他测试。 */
    @BeforeEach void setup() {
        var suffix=UUID.randomUUID().toString().substring(0,8);owner="family-owner-"+suffix;member="family-member-"+suffix;outsider="family-outside-"+suffix;
        for(var name:List.of(owner,member,outsider))users.insert(name,encoder.encode(PASSWORD),name.equals(outsider)?UserRole.ADMIN:UserRole.USER,"TEST",name,"","","JPY","Asia/Tokyo",null,1);
    }
    /** 创建共享账本，返回一次性邀请码；个人账本中原有账户不迁移。 */
    private JsonNode create(Browser browser) {
        var response=browser.send("/api/family",HttpMethod.POST,Map.of("name","Our family","currency","JPY","timezone","Asia/Tokyo"));assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);return response.getBody();
    }
    /** 以实际登录会话和邀请码加入家庭。 */
    private void join(Browser browser,JsonNode created) { assertThat(browser.send("/api/family/join",HttpMethod.POST,Map.of("invitationCode",created.get("invitationCode").asText())).getStatusCode()).isEqualTo(HttpStatus.OK); }
    /** 创建当前账本中的收付款账户，返回真实账户主键。 */
    private long account(Browser browser,String name) { var r=browser.send("/api/accounts",HttpMethod.POST,Map.of("name",name,"currency","JPY"));assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);return r.getBody().get("id").asLong(); }
    /** 构造一笔正数支出，不在请求中指定用户或账本归属。 */
    private Map<String,Object> entry(long id,String amount) { return Map.of("accountId",id,"kind","EXPENSE","amount",amount,"date",LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")).toString(),"category","FOOD","merchant","Family store","note","Shared dinner","tags",List.of("家庭")); }
    /** 切换服务器持久化的当前账本。 */
    private void scope(Browser browser,String scope) { assertThat(browser.send("/api/family/scope",HttpMethod.PUT,Map.of("scope",scope)).getStatusCode()).isEqualTo(HttpStatus.OK); }

    /** 验证成员共用账户、收支、预算预警、转账、模板及报表，审计仍记录真正的操作者。 */
    @Test void membersShareTheWholeLedgerButKeepIndependentPersonalBooks() {
        var a=new Browser(owner);var b=new Browser(member);long personalA=account(a,"Private A"),personalB=account(b,"Private B");var created=create(a);
        assertThat(a.get("/api/ledger/options").getBody().get("accounts").size()).isZero();join(b,created);
        long first=account(a,"Shared cash"),second=account(b,"Shared bank");
        assertThat(a.get("/api/ledger/options").getBody().get("accounts").size()).isEqualTo(2);
        var today=LocalDate.now(java.time.ZoneId.of("Asia/Tokyo"));var budget=Map.of("name","Family food","category","FOOD","currency","JPY","period","MONTH","start",today.withDayOfMonth(1).toString(),"end",today.withDayOfMonth(today.lengthOfMonth()).toString(),"amount","100","thresholds",List.of(50,80,100),"rolloverMode","NONE");
        assertThat(a.send("/api/budgets",HttpMethod.POST,budget).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var expense=b.send("/api/transactions",HttpMethod.POST,entry(first,"120"));assertThat(expense.getStatusCode()).isEqualTo(HttpStatus.CREATED);assertThat(expense.getBody().get("createdBy").asText()).isEqualTo(member);
        var list=a.get("/api/transactions").getBody();assertThat(list.get("total").asInt()).isEqualTo(1);assertThat(list.get("items").get(0).get("createdBy").asText()).isEqualTo(member);
        assertThat(a.get("/api/budgets").getBody().get("items").get(0).get("spent").asText()).isEqualTo("120");
        assertThat(b.get("/api/budget-notifications").getBody().size()).isEqualTo(4);
        assertThat(b.send("/api/transfers",HttpMethod.POST,Map.of("fromAccountId",first,"toAccountId",second,"amount","10","date",today.toString(),"note","Shared transfer")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(a.get("/api/transfers").getBody().size()).isEqualTo(1);
        assertThat(b.send("/api/accounts/"+second+"/valuations",HttpMethod.POST,Map.of("balance","200","date",today.toString(),"note","Actual balance")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(a.get("/api/accounts/"+second+"/valuations").getBody().size()).isEqualTo(1);
        var report=a.get("/api/reports/financial?start="+today+"&end="+today+"&currency=JPY").getBody();assertThat(report.toString()).contains("120","Family store");
        assertThat(b.send("/api/budget-templates",HttpMethod.POST,Map.of("name","Shared template","sourceMonth",today.toString().substring(0,7))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(a.get("/api/budget-templates").getBody().size()).isEqualTo(1);
        scope(a,"PERSONAL");assertThat(a.get("/api/ledger/options").getBody().get("accounts").get(0).get("id").asLong()).isEqualTo(personalA);
        assertThat(a.send("/api/transactions",HttpMethod.POST,entry(first,"1")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(b.send("/api/transactions",HttpMethod.POST,entry(personalA,"1")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        scope(b,"PERSONAL");assertThat(b.get("/api/ledger/options").getBody().get("accounts").get(0).get("id").asLong()).isEqualTo(personalB);
        scope(b,"FAMILY");assertThat(b.get("/api/transactions").getBody().get("total").asInt()).isEqualTo(1);
    }
    /** 验证系统管理员不能越过家庭权限，成员不能管理他人或伪造家庭选择。 */
    @Test void invitationAndOwnerPermissionsAreIndependentOfSystemRoles() {
        var a=new Browser(owner);var b=new Browser(member);var x=new Browser(outsider);var created=create(a);join(b,created);long account=account(a,"Family cash");
        assertThat(x.get("/api/family").getBody().get("family").isNull()).isTrue();
        assertThat(x.send("/api/family/scope",HttpMethod.PUT,Map.of("scope","FAMILY")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(x.send("/api/transactions",HttpMethod.POST,entry(account,"5")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(x.send("/api/family/join",HttpMethod.POST,Map.of("invitationCode",created.get("status").get("family").get("id").asText())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(b.send("/api/family/invitation",HttpMethod.POST,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(b.send("/api/family/members/"+id(owner),HttpMethod.DELETE,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(b.send("/api/family/members/999999999",HttpMethod.DELETE,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(a.send("/api/family/members/"+id(owner),HttpMethod.DELETE,null).getBody().get("code").asText()).isEqualTo("FAMILY_OWNER_REQUIRED");
        assertThat(a.send("/api/family/owner",HttpMethod.PUT,Map.of("userId",id(outsider))).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(a.send("/api/family/owner",HttpMethod.PUT,Map.of("userId",id(member))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(a.send("/api/family/invitation",HttpMethod.POST,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(a.send("/api/family/members/"+id(owner),HttpMethod.DELETE,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(a.get("/api/family").getBody().get("family").isNull()).isTrue();assertThat(b.get("/api/ledger/options").getBody().get("accounts").size()).isEqualTo(1);
    }
    /** 移除成员后现有会话失去共享范围，原家庭流水和审计保留；重新加入创建新审计关系。 */
    @Test void removedMemberCannotReadOrWriteWithExistingSessionAndCanRejoinByInvitation() {
        var a=new Browser(owner);var b=new Browser(member);var created=create(a);join(b,created);long shared=account(b,"Member account");
        b.send("/api/transactions",HttpMethod.POST,entry(shared,"50"));
        assertThat(a.send("/api/family/members/"+id(member),HttpMethod.DELETE,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.get("/api/ledger/options").getBody().get("accounts").size()).isZero();
        assertThat(b.send("/api/transactions",HttpMethod.POST,entry(shared,"1")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(b.get("/api/transactions").getBody().get("total").asInt()).isZero();
        assertThat(a.get("/api/transactions").getBody().get("total").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM family_member WHERE user_id=?",Boolean.class,id(member))).isTrue();
        join(b,created);assertThat(b.get("/api/transactions").getBody().get("total").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM family_member WHERE user_id=?",Integer.class,id(member))).isEqualTo(2);
    }
    /** 旧邀请在轮换后失效，一个账号不能同时加入两个家庭；邀请哈希不会通过查询泄露。 */
    @Test void invitationsRotateAndCannotBeUsedToJoinMultipleFamilies() {
        var a=new Browser(owner);var b=new Browser(member);var created=create(a);var rotated=a.send("/api/family/invitation",HttpMethod.POST,null).getBody();
        assertThat(b.send("/api/family/join",HttpMethod.POST,Map.of("invitationCode",created.get("invitationCode").asText())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        join(b,rotated);assertThat(b.get("/api/family").getBody().toString()).doesNotContain("invitation", "password", "email");
        assertThat(b.send("/api/family/join",HttpMethod.POST,Map.of("invitationCode",rotated.get("invitationCode").asText())).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(b.send("/api/family",HttpMethod.POST,Map.of("name","Another","currency","JPY","timezone","UTC")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
    /** 不同标签页的旧账本输入被拒绝，避免悄悄写入后来选中的账本；无 CSRF 写请求也拒绝。 */
    @Test void staleBookHeaderAndMissingCsrfNeverWriteIntoAnotherBook() {
        var a=new Browser(owner);var created=create(a);long shared=account(a,"Shared");a.expectedScope="FAMILY:"+created.get("status").get("family").get("id").asLong();
        scope(a,"PERSONAL");var rejected=a.send("/api/accounts",HttpMethod.POST,Map.of("name","Wrong place","currency","JPY"));assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(rejected.getBody().get("code").asText()).isEqualTo("BOOK_CHANGED");
        a.expectedScope=null;assertThat(a.get("/api/ledger/options").getBody().get("accounts").size()).isZero();scope(a,"FAMILY");assertThat(a.get("/api/ledger/options").getBody().get("accounts").get(0).get("id").asLong()).isEqualTo(shared);
        a.token=null;assertThat(a.send("/api/family/invitation",HttpMethod.POST,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    /** 家庭成员并发记账不会丢失预算支出，去重预警只保存一次；后台不依赖当前选择。 */
    @Test void concurrentMemberExpensesAndScheduledRefreshShareOneBudget() throws Exception {
        var a=new Browser(owner);var b=new Browser(member);var created=create(a);join(b,created);long cash=account(a,"Shared cash");var today=LocalDate.now(java.time.ZoneId.of("Asia/Tokyo"));
        a.send("/api/budgets",HttpMethod.POST,Map.of("name","Concurrent budget","category","TOTAL","currency","JPY","period","MONTH","start",today.withDayOfMonth(1).toString(),"end",today.withDayOfMonth(today.lengthOfMonth()).toString(),"amount","100","thresholds",List.of(50),"rolloverMode","NONE"));
        var executor=Executors.newFixedThreadPool(2);
        try { var one=executor.submit(()->a.send("/api/transactions",HttpMethod.POST,entry(cash,"30")));var two=executor.submit(()->b.send("/api/transactions",HttpMethod.POST,entry(cash,"30")));assertThat(one.get(15,TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);assertThat(two.get(15,TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED); }
        finally { executor.shutdownNow(); }
        scope(a,"PERSONAL");scope(b,"PERSONAL");long bookId=-created.get("status").get("family").get("id").asLong();budgets.refreshBook(bookId);
        scope(a,"FAMILY");assertThat(a.get("/api/budgets").getBody().get("items").get(0).get("spent").asText()).isEqualTo("60");assertThat(a.get("/api/budget-notifications").getBody().size()).isEqualTo(1);
    }
    /** 成员能导入和导出共享流水，重复检测覆盖其他成员记录，个人账本导出不包含家庭交易。 */
    @Test void sharedImportExportAndDuplicateDetectionUseTheFamilyBook() throws Exception {
        var a=new Browser(owner);var b=new Browser(member);var created=create(a);join(b,created);long cash=account(a,"Shared cash");
        assertThat(a.send("/api/transactions",HttpMethod.POST,entry(cash,"70")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var result=b.send("/api/transactions/import",HttpMethod.POST,Map.of("entries",List.of(entry(cash,"70"),entry(cash,"80")),"skipDuplicates",true));
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(result.getBody().get("imported").asInt()).isEqualTo(1);assertThat(result.getBody().get("skipped").asInt()).isEqualTo(1);
        byte[] csv=files.export(owner,"csv",null,null,null,null);
        var mapping=new collector.bu.model.LedgerModels.Mapping(Map.of("kind",0,"amount",1,"date",2,"account",3,"category",4,"merchant",5,"note",6,"currency",7,"tags",8),null,null,null);
        var preview=files.preview(member,new org.springframework.mock.web.MockMultipartFile("file","shared.csv","text/csv",csv),mapper.writeValueAsString(mapping));
        assertThat(preview.invalid()).isZero();assertThat(preview.duplicates()).isEqualTo(2);
        byte[] excel=files.export(member,"xlsx",null,null,null,null);assertThat(excel[0]).isEqualTo((byte)'P');
        scope(b,"PERSONAL");assertThat(new String(files.export(member,"csv",null,null,null,null),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("Shared cash","Shared dinner");
    }
    /** 不合法家庭配置返回400且不创建数据；系统管理员加入后也只有普通成员管理权限。 */
    @Test void invalidPreferencesAreRejectedAndSystemAdminCannotManageAJoinedFamily() {
        var a=new Browser(owner);var x=new Browser(outsider);
        for(var input:List.of(Map.of("name","Home","currency","JPY","timezone","Mars/Olympus"),Map.of("name","Home","currency","ZZZ","timezone","UTC"),Map.of("name","Line\nbreak","currency","JPY","timezone","UTC")))
            assertThat(a.send("/api/family",HttpMethod.POST,input).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM family_group WHERE owner_user_id=?",Integer.class,id(owner))).isZero();
        var created=create(a);join(x,created);
        assertThat(x.send("/api/family/invitation",HttpMethod.POST,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(x.send("/api/family/owner",HttpMethod.PUT,Map.of("userId",id(outsider))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(a.send("/api/family/members/"+id(outsider),HttpMethod.DELETE,null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    /** 查出测试账号真实主键，身份不能从请求正文指定。 */
    private long id(String name) { return users.findByUsername(name).orElseThrow().id(); }
    /** 为测试建立独立浏览器 Cookie 会话。 */
    private class Browser {
        String cookie,token,expectedScope;
        /** 通过真实登录建立会话并刷新登录后的 CSRF。 */
        Browser(String name) { csrf();assertThat(send("/api/auth/login",HttpMethod.POST,Map.of("username",name,"password",PASSWORD)).getStatusCode()).isEqualTo(HttpStatus.OK);csrf(); }
        /** 取得本会话 CSRF 令牌。 */
        void csrf() { token=get("/api/auth/csrf").getBody().get("token").asText(); }
        /** 使用实际会话发送查询请求。 */
        ResponseEntity<JsonNode> get(String path) { return send(path,HttpMethod.GET,null); }
        /** 使用实际 Cookie 和令牌发送请求，可模拟过期页面的账本校验头。 */
        ResponseEntity<JsonNode> send(String path,HttpMethod method,Object body) {
            var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(cookie!=null)headers.set("Cookie",cookie);if(token!=null)headers.set("X-CSRF-TOKEN",token);if(expectedScope!=null)headers.set("X-BOOK-SCOPE",expectedScope);
            var response=rest.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class);var cookies=response.getHeaders().get("Set-Cookie");if(cookies!=null)cookies.stream().filter(c->c.startsWith("JSESSIONID=")).forEach(c->cookie=c.split(";",2)[0]);return response;
        }
    }
}
