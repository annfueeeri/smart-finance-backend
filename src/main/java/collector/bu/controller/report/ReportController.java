package collector.bu.controller.report;

import collector.bu.service.ReportExportService;
import collector.bu.service.ReportService;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import static collector.bu.model.report.ReportModels.*;

/** 个人报表完整路径显式列出，查询和导出均复用同一统计条件与算法。 */
@RestController
public class ReportController {
    private final ReportService service;private final ReportExportService exports;
    /** 注入财务统计业务与安全导出组件。 */
    public ReportController(ReportService service,ReportExportService exports) {this.service=service;this.exports=exports;}
    /** 从认证会话获取用户名，不接受任何客户端用户ID。 */
    private String username(){return SecurityContextHolder.getContext().getAuthentication().getName();}
    /** GET /api/reports/options：列出本人真实交易标签，供自定义筛选。 */
    @GetMapping("/api/reports/options")
    public ReportOptions options(){return service.options(username());}
    /** GET /api/reports/financial：指定周期、账户、方向、分类、标签和币种的全部财务统计。 */
    @GetMapping("/api/reports/financial")
    public Report report(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate start,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required=false) String grouping,@RequestParam(required=false) Long accountId,@RequestParam(required=false) String currency,@RequestParam(required=false) String kind,@RequestParam(required=false) String category,@RequestParam(required=false) String tag){return service.report(username(),new Filter(start,end,grouping,accountId,currency,kind,category,tag));}
    /** GET /api/reports/export：下载含统计摘要、图表及完整明细的CSV、XLSX或PDF。 */
    @GetMapping("/api/reports/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue="csv") String format,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate start,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required=false) String grouping,@RequestParam(required=false) Long accountId,@RequestParam(required=false) String currency,@RequestParam(required=false) String kind,@RequestParam(required=false) String category,@RequestParam(required=false) String tag){
        var report=service.report(username(),new Filter(start,end,grouping,accountId,currency,kind,category,tag));var bytes=exports.export(report,format);
        var type=switch(format){case "csv" -> "text/csv;charset=UTF-8";case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";default -> "application/pdf";};
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=financial-report."+format).header("Cache-Control","no-store").contentType(MediaType.parseMediaType(type)).body(bytes);
    }
}
