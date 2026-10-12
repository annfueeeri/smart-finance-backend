package collector.bu;

import collector.bu.dao.UserDao;
import collector.bu.entity.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReceiptIntegrationTests {
    @Autowired TestRestTemplate rest;
    @Autowired UserDao users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    private static final String PASSWORD="Receipt-test-password-123";
    private String owner,member,outsider;
    /** 创建独立账号，不影响其他测试流水的清理或家庭关系。 */
    @BeforeEach void setup() { var suffix=UUID.randomUUID().toString().substring(0,8);owner="receipt-owner-"+suffix;member="receipt-member-"+suffix;outsider="receipt-outside-"+suffix;for(var name:List.of(owner,member,outsider))users.insert(name,encoder.encode(PASSWORD),name.equals(outsider)?UserRole.ADMIN:UserRole.USER,"TEST",name,"","","JPY","Asia/Tokyo",null,1); }
    /** 构造可以实际解码的图片，不使用只有扩展名的伪文件。 */
    private byte[] image(String type,int color) throws Exception { var image=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,color);var bytes=new ByteArrayOutputStream();ImageIO.write(image,type,bytes);return bytes.toByteArray(); }
    /** 通过真实接口登记流水，收据始终关联已有记录。 */
    private long transaction(Browser browser) { long account=browser.send("/api/accounts",HttpMethod.POST,Map.of("name","Receipt cash","currency","JPY")).getBody().get("id").asLong();return browser.send("/api/transactions",HttpMethod.POST,Map.of("accountId",account,"kind","EXPENSE","amount","100","date","2026-10-12","category","FOOD","merchant","Receipt shop","note","Purchase")).getBody().get("id").asLong(); }
    /** 返回收据列表与上传的完整路径。 */
    private String path(long transaction) { return "/api/transactions/"+transaction+"/receipts"; }
    /** 校验内容、缓存、重复、逻辑删除与审计，刷新仍能从数据库取得文件。 */
    @Test void uploadDownloadDuplicateAndSoftDeletionPreserveAudit() throws Exception {
        var a=new Browser(owner);long transaction=transaction(a);byte[] png=image("png",100);var uploaded=a.upload(path(transaction),"../日本語\n收据.svg",png);assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);var receipt=uploaded.getBody();long id=receipt.get("id").asLong();
        assertThat(receipt.get("contentType").asText()).isEqualTo("image/png");assertThat(receipt.get("fileName").asText()).isEqualTo("日本語收据.png");assertThat(receipt.get("createdBy").asText()).isEqualTo(owner);
        var bytes=rest.exchange(path(transaction)+"/"+id+"/content",HttpMethod.GET,new HttpEntity<>(a.headers()),byte[].class);assertThat(bytes.getBody()).isEqualTo(png);assertThat(bytes.getHeaders().getFirst("Cache-Control")).contains("no-store");assertThat(bytes.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(a.upload(path(transaction),"again.png",png).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(a.send(path(transaction),HttpMethod.GET,null).getBody().size()).isEqualTo(1);
        assertThat(a.send(path(transaction)+"/"+id,HttpMethod.DELETE,null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(a.send(path(transaction)+"/"+id+"/content",HttpMethod.GET,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);assertThat(jdbc.queryForObject("SELECT is_deleted FROM transaction_receipt WHERE id=?",Boolean.class,id)).isTrue();assertThat(jdbc.queryForObject("SELECT updated_by FROM transaction_receipt WHERE id=?",String.class,id)).isEqualTo(owner);
        assertThat(a.upload(path(transaction),"again.png",png).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT amount FROM ledger_transaction WHERE id=?",java.math.BigDecimal.class,transaction)).isEqualByComparingTo("100");
    }
    /** 真实 JPG、PDF 可上传，伪装扩展名、损坏文件和空文件不写入数据库。 */
    @Test void actualFormatsAndSizeLimitsAreValidated() throws Exception {
        var a=new Browser(owner);long transaction=transaction(a);assertThat(a.upload(path(transaction),"photo.jpg",image("jpg",200)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var bytes=new ByteArrayOutputStream();try(var pdf=new PDDocument()) { pdf.addPage(new PDPage());pdf.save(bytes); }
        assertThat(a.upload(path(transaction),"scan.pdf",bytes.toByteArray()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        for(var file:List.of(new byte[0],"<svg><script>alert(1)</script></svg>".getBytes(),"%PDF-invalid".getBytes()))assertThat(a.upload(path(transaction),"fake.png",file).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(a.upload(path(transaction),"large.png",new byte[5242881]).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(a.send(path(transaction),HttpMethod.GET,null).getBody().size()).isEqualTo(2);
    }
    /** 限制每笔十份有效收据，删除后释放名额，保留审计而不改变流水。 */
    @Test void attachmentLimitIsEnforced() throws Exception { var a=new Browser(owner);long transaction=transaction(a);for(int i=0;i<10;i++)assertThat(a.upload(path(transaction),"receipt.png",image("png",i)).getStatusCode()).isEqualTo(HttpStatus.CREATED);assertThat(a.upload(path(transaction),"receipt.png",image("png",11)).getBody().get("code").asText()).isEqualTo("RECEIPT_LIMIT"); }
    /** 家庭成员能管理同一附件，系统管理员无额外权限，退出后的旧会话不能再访问。 */
    @Test void familySharingAndRevocationApplyToReceiptContentsAndDeletion() throws Exception {
        var a=new Browser(owner);var b=new Browser(member);var x=new Browser(outsider);var family=a.send("/api/family",HttpMethod.POST,Map.of("name","Receipt family","currency","JPY","timezone","Asia/Tokyo")).getBody();b.send("/api/family/join",HttpMethod.POST,Map.of("invitationCode",family.get("invitationCode").asText()));
        long transaction=transaction(a);long receipt=b.upload(path(transaction),"shared.png",image("png",300)).getBody().get("id").asLong();assertThat(a.send(path(transaction),HttpMethod.GET,null).getBody().get(0).get("createdBy").asText()).isEqualTo(member);
        assertThat(x.send(path(transaction),HttpMethod.GET,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);assertThat(x.send(path(transaction)+"/"+receipt+"/content",HttpMethod.GET,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);assertThat(x.send(path(transaction)+"/"+receipt,HttpMethod.DELETE,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        a.send("/api/family/members/"+users.findByUsername(member).orElseThrow().id(),HttpMethod.DELETE,null);
        assertThat(b.send(path(transaction),HttpMethod.GET,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);assertThat(b.send(path(transaction)+"/"+receipt+"/content",HttpMethod.GET,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);assertThat(b.upload(path(transaction),"no.png",image("png",400)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(a.send(path(transaction),HttpMethod.GET,null).getBody().size()).isEqualTo(1);a.token=null;assertThat(a.upload(path(transaction),"no-csrf.png",image("png",500)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    /** 保存独立 Cookie 和当前会话令牌，用真实 HTTP 验证权限。 */
    private class Browser {
        String cookie,token;
        /** 建立真实登录会话并获取登录后的令牌。 */
        Browser(String name) { token=send("/api/auth/csrf",HttpMethod.GET,null).getBody().get("token").asText();assertThat(send("/api/auth/login",HttpMethod.POST,Map.of("username",name,"password",PASSWORD)).getStatusCode()).isEqualTo(HttpStatus.OK);token=send("/api/auth/csrf",HttpMethod.GET,null).getBody().get("token").asText(); }
        /** 组装会话和 CSRF 请求头。 */
        HttpHeaders headers() { var h=new HttpHeaders();if(cookie!=null)h.set("Cookie",cookie);if(token!=null)h.set("X-CSRF-TOKEN",token);return h; }
        /** 更新服务端轮换的会话 Cookie。 */
        void cookies(HttpHeaders headers) { var values=headers.get("Set-Cookie");if(values!=null)values.stream().filter(c->c.startsWith("JSESSIONID=")).forEach(c->cookie=c.split(";",2)[0]); }
        /** 发送 JSON 或查询请求。 */
        ResponseEntity<JsonNode> send(String path,HttpMethod method,Object body) { var h=headers();h.setContentType(MediaType.APPLICATION_JSON);var r=rest.exchange(path,method,new HttpEntity<>(body,h),JsonNode.class);cookies(r.getHeaders());return r; }
        /** 通过真实 multipart 上传文件，声明类型不影响服务器实际内容检查。 */
        ResponseEntity<JsonNode> upload(String path,String name,byte[] bytes) { var data=new LinkedMultiValueMap<String,Object>();data.add("file",new ByteArrayResource(bytes){ /** 将测试文件名作为 multipart 原文件名。 */ @Override public String getFilename() { return name; }});var h=headers();h.setContentType(MediaType.MULTIPART_FORM_DATA);var r=rest.exchange(path,HttpMethod.POST,new HttpEntity<>(data,h),JsonNode.class);cookies(r.getHeaders());return r; }
    }
}
