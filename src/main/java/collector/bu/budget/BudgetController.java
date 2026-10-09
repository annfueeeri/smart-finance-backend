package collector.bu.budget;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import static collector.bu.budget.BudgetModels.*;

/** 预算完整接口路径明确写在每个方法上，身份由认证会话确定，写入受 CSRF 保护。 */
@RestController
public class BudgetController {
    private final BudgetService service;
    /** 注入预算业务组件，Controller 不执行 SQL。 */
    public BudgetController(BudgetService service) { this.service=service; }
    /** 读取认证用户名，不接受客户端传入用户 ID 或管理员身份。 */
    private String username() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
    /** GET /api/budgets：预算执行、剩余额度、超支和预测信息。 */
    @GetMapping("/api/budgets")
    public Overview list(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate end) { return service.overview(username(),start,end); }
    /** POST /api/budgets：新增个人总预算、分类预算或任意支持的周期预算。 */
    @PostMapping("/api/budgets")
    public ResponseEntity<View> create(@Valid @RequestBody Input input) { return ResponseEntity.status(201).body(service.create(username(),input)); }
    /** PUT /api/budgets/{id}：调整金额、阈值和结转规则，保存前后金额与理由。 */
    @PutMapping("/api/budgets/{id}")
    public View adjust(@PathVariable long id,@Valid @RequestBody AdjustmentInput input) { return service.adjust(username(),id,input); }
    /** GET /api/budgets/{id}/adjustments：查询本人预算调整和结转修改记录。 */
    @GetMapping("/api/budgets/{id}/adjustments")
    public List<Adjustment> adjustments(@PathVariable long id) { return service.adjustments(username(),id); }
    /** GET /api/budget-templates：本人保存的常用月度预算方案。 */
    @GetMapping("/api/budget-templates")
    public List<Template> templates() { return service.templates(username()); }
    /** POST /api/budget-templates：保存指定月份的总预算和分类配置为模板。 */
    @PostMapping("/api/budget-templates")
    public ResponseEntity<Template> saveTemplate(@Valid @RequestBody SaveTemplate input) { return ResponseEntity.status(201).body(service.saveTemplate(username(),input)); }
    /** POST /api/budget-templates/{id}/apply：将本人模板应用到新月份，冲突时整批回滚。 */
    @PostMapping("/api/budget-templates/{id}/apply")
    public List<View> apply(@PathVariable long id,@Valid @RequestBody ApplyTemplate input) { return service.applyTemplate(username(),id,input); }
    /** POST /api/budgets/copy-month：复制本人上月或指定月份的预算配置。 */
    @PostMapping("/api/budgets/copy-month")
    public List<View> copy(@Valid @RequestBody CopyMonth input) { return service.copyMonth(username(),input); }
    /** GET /api/budget-notifications：最新个人站内预算阈值与超支提醒。 */
    @GetMapping("/api/budget-notifications")
    public List<Notice> notices() { return service.notices(username()); }
    /** PUT /api/budget-notifications/{id}/read：把本人消息标记已读。 */
    @PutMapping("/api/budget-notifications/{id}/read")
    public ResponseEntity<Void> read(@PathVariable long id) { service.readNotice(username(),id);return ResponseEntity.noContent().build(); }
    /** GET /api/budgets/history：查看历史月度预算执行及长期超支分类，按币种分组。 */
    @GetMapping("/api/budgets/history")
    public History history(@RequestParam(required=false) String from,@RequestParam(required=false) String to) { return service.history(username(),from,to); }
}
