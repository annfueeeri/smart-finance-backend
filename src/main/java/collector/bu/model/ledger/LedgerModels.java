package collector.bu.model.ledger;

import collector.bu.entity.ledger.LedgerAccount;
import collector.bu.entity.ledger.LedgerEntry;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 收支模块对外数据结构，金额统一使用十进制字符串，审计信息由后端生成。 */
public final class LedgerModels {
    /** 创建账户请求，币种不填时采用用户默认币种。 */
    public record AccountInput(@NotBlank @Size(max=80) String name, @Pattern(regexp="[A-Z]{3}") String currency,
            String type,String openingBalance,LocalDate openingDate) {
        /** 兼容已有 Java 调用，未提供余额时使用零余额和历史起点。 */
        public AccountInput(String name,String currency) { this(name,currency,null,null,null); }
    }
    /** 创建收支请求，不接受用户 ID、身份或审计操作者。 */
    public record EntryInput(@NotNull @Min(1) Long accountId, @NotBlank @Pattern(regexp="INCOME|EXPENSE") String kind,
            @NotBlank @Pattern(regexp="[0-9]{1,12}(\\.[0-9]{1,4})?") String amount,
            @NotNull LocalDate date, @NotBlank String category, @Size(max=120) String merchant, @Size(max=1000) String note,@Size(max=10) List<@NotBlank @Size(max=30) String> tags) {
        /** 兼容已有无标签的导入、测试和客户端，标签默认空列表。 */
        public EntryInput(Long accountId,String kind,String amount,LocalDate date,String category,String merchant,String note) {
            this(accountId,kind,amount,date,category,merchant,note,List.of());
        }
    }
    /** 分页结果，total 是当前用户在当前筛选条件下的条数。 */
    public record EntryPage(List<LedgerEntry> items, long total, int page, int size) { }
    /** 分类选项，按类型分组显示。 */
    public record CategoryOption(String code, String kind, String name) { }
    /** 当前用户的个人账户、默认币种、时区和今天日期及支持的分类。 */
    public record Options(List<LedgerAccount> accounts, List<CategoryOption> categories, String currency, String timezone, LocalDate today) { }
    /** 导入映射按零起始列索引指定字段，可用默认类型、账户和分类补充文件缺失信息。 */
    public record Mapping(Map<String,Integer> columns, String defaultKind, Long defaultAccountId, String defaultCategory) { }
    /** 文件表头和少量原始行，供选择字段映射；没有数据库写入。 */
    public record Inspection(List<String> headers, List<List<String>> sampleRows, int totalRows) { }
    /** 逐行预览结果；错误行不可提交，重复行默认跳过。 */
    public record PreviewRow(int rowNumber, EntryInput entry, boolean duplicate, List<String> errors) { }
    /** 文件预览结果，含有效、重复和异常记录数量。 */
    public record Preview(List<PreviewRow> rows, int valid, int duplicates, int invalid) { }
    /** 导入确认请求，服务端重新校验所有数据，整个批次以事务提交。 */
    public record ImportInput(@NotNull @Size(min=1,max=500) List<@NotNull @jakarta.validation.Valid EntryInput> entries, Boolean skipDuplicates) { }
    /** 实际导入数量及因重复跳过的数量。 */
    public record ImportResult(int imported, int skipped) { }
}
