package collector.bu.controller;

import collector.bu.service.ReceiptService;
import static collector.bu.model.ReceiptModels.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 流水收据接口，内容始终经过会话及当前账本授权；写操作校验 CSRF。 */
@RestController
@RequestMapping("/api/transactions/{transactionId}/receipts")
public class ReceiptController {
    private final ReceiptService service;
    /** 注入收据业务服务。 */
    public ReceiptController(ReceiptService service) { this.service=service; }
    /** GET /api/transactions/{transactionId}/receipts：查询流水的有效收据列表。 */
    @GetMapping public List<Receipt> list(Authentication auth,@PathVariable("transactionId") long transaction) { return service.list(auth.getName(),transaction); }
    /** POST /api/transactions/{transactionId}/receipts：上传单份收据，成功返回201与元数据。 */
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Receipt> upload(Authentication auth,@PathVariable("transactionId") long transaction,@RequestPart("file") MultipartFile file) { return ResponseEntity.status(201).body(service.upload(auth.getName(),transaction,file)); }
    /** GET /api/transactions/{transactionId}/receipts/{receiptId}/content：经授权下载原始文件，禁止缓存和类型猜测。 */
    @GetMapping("/{receiptId}/content")
    public ResponseEntity<byte[]> content(Authentication auth,@PathVariable("transactionId") long transaction,@PathVariable("receiptId") long receipt) {
        var value=service.content(auth.getName(),transaction,receipt);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(value.receipt().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(value.receipt().fileName(),StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL,"no-store, private").header("X-Content-Type-Options","nosniff").body(value.bytes());
    }
    /** DELETE /api/transactions/{transactionId}/receipts/{receiptId}：逻辑删除收据并返回204。 */
    @DeleteMapping("/{receiptId}")
    public ResponseEntity<Void> delete(Authentication auth,@PathVariable("transactionId") long transaction,@PathVariable("receiptId") long receipt) { service.delete(auth.getName(),transaction,receipt);return ResponseEntity.noContent().build(); }
}
