package collector.bu.controller.report;

import collector.bu.service.AccountingService;

import collector.bu.entity.ledger.LedgerAccount;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import static collector.bu.model.report.ReportModels.*;

/** 账户和内部转账辅助接口，写入会话必须通过CSRF且只能操作本人账户。 */
@RestController
public class AccountingController {
    private final AccountingService service;
    /** 注入账户资料、转账及日终估值业务。 */
    public AccountingController(AccountingService service) { this.service=service; }
    /** 读取认证会话用户名，不允许前端传入报表用户身份。 */
    private String username() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
    /** PUT /api/accounts/{id}：修改本人账户名称、资产或负债类别，留下更新审计。 */
    @PutMapping("/api/accounts/{id}")
    public LedgerAccount profile(@PathVariable long id,@Valid @RequestBody AccountProfile body) { return service.profile(username(),id,body); }
    /** POST /api/transfers：记录本人同币种账户之间的内部转账，不计入预算消费。 */
    @PostMapping("/api/transfers")
    public ResponseEntity<Transfer> transfer(@Valid @RequestBody TransferInput body) { return ResponseEntity.status(201).body(service.transfer(username(),body)); }
    /** GET /api/transfers：读取本人内部转账及其审计信息。 */
    @GetMapping("/api/transfers")
    public List<Transfer> transfers() { return service.transfers(username()); }
    /** POST /api/accounts/{id}/valuations：保存本人账户日终余额或投资估值，不伪造成收入支出。 */
    @PostMapping("/api/accounts/{id}/valuations")
    public ResponseEntity<Valuation> value(@PathVariable long id,@Valid @RequestBody ValuationInput body) { return ResponseEntity.status(201).body(service.value(username(),id,body)); }
    /** GET /api/accounts/{id}/valuations：读取本人账户日期估值记录，同日最后一条为有效值。 */
    @GetMapping("/api/accounts/{id}/valuations")
    public List<Valuation> values(@PathVariable long id) { return service.values(username(),id); }
}
