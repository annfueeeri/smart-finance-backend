package collector.bu.ledger;

import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import static collector.bu.ledger.LedgerModels.*;

/** 个人收支接口，完整路径显式声明，写入请求由安全过滤器验证 CSRF。 */
@RestController
public class LedgerController {
    private final LedgerService service;
    private final LedgerFiles files;
    /** 注入个人收支业务服务。 */
    public LedgerController(LedgerService service,LedgerFiles files) { this.service=service; this.files=files; }
    /** 读取当前服务端认证用户名，不接受前端指定身份或用户 ID。 */
    private String username() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
    /** GET /api/ledger/options：加载个人账户、收入支出分类及默认记账日期。 */
    @GetMapping("/api/ledger/options")
    public Options options() { return service.options(username()); }
    /** POST /api/accounts：创建个人收付款账户，成功返回 201 与审计信息。 */
    @PostMapping("/api/accounts")
    public ResponseEntity<LedgerAccount> createAccount(@Valid @RequestBody AccountInput body) {
        return ResponseEntity.status(201).body(service.createAccount(username(),body));
    }
    /** POST /api/transactions：新增工资、消费等个人收支，不接受其他人的账户。 */
    @PostMapping("/api/transactions")
    public ResponseEntity<LedgerEntry> createEntry(@Valid @RequestBody EntryInput body) {
        return ResponseEntity.status(201).body(service.createEntry(username(),body));
    }
    /** GET /api/transactions：按收支类型、日期和账户分页查询当前用户的记录。 */
    @GetMapping("/api/transactions")
    public EntryPage list(@RequestParam(required=false) String kind,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required=false) Long accountId, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) { return service.list(username(),kind,start,end,accountId,page,size); }
    /** POST /api/transactions/import/inspect：读取文件列名和样例，供用户设置映射。 */
    @PostMapping(value="/api/transactions/import/inspect",consumes="multipart/form-data")
    public Inspection inspect(@RequestPart("file") org.springframework.web.multipart.MultipartFile file) { return files.inspect(file); }
    /** POST /api/transactions/import/preview：按映射预览所有行及异常、重复提示，不写入数据库。 */
    @PostMapping(value="/api/transactions/import/preview",consumes="multipart/form-data")
    public Preview preview(@RequestPart("file") org.springframework.web.multipart.MultipartFile file,@RequestParam("mapping") String mapping) {
        return files.preview(username(),file,mapping);
    }
    /** POST /api/transactions/import：确认导入，后端再次校验全部行，默认跳过重复，事务保证原子提交。 */
    @PostMapping("/api/transactions/import")
    public ImportResult importEntries(@Valid @RequestBody ImportInput body) { return service.importEntries(username(),body); }
    /** GET /api/transactions/export：按当前筛选条件下载个人 CSV 或 XLSX 流水。 */
    @GetMapping("/api/transactions/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue="csv") String format,@RequestParam(required=false) String kind,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate end,@RequestParam(required=false) Long accountId) {
        var bytes=files.export(username(),format,kind,start,end,accountId);
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=transactions."+format)
                .header("Cache-Control","no-store")
                .contentType(org.springframework.http.MediaType.parseMediaType(format.equals("csv") ? "text/csv;charset=UTF-8" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}
