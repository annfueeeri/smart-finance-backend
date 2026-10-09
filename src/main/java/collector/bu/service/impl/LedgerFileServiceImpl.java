package collector.bu.service.impl;

import collector.bu.service.LedgerService;

import collector.bu.service.LedgerFileService;

import collector.bu.entity.ledger.LedgerEntry;
import collector.bu.exception.LedgerException;
import collector.bu.model.ledger.LedgerCategory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.*;
import java.time.LocalDate;
import java.time.format.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import static collector.bu.model.ledger.LedgerModels.*;

/** 解析 CSV、XLS 和 XLSX，提供无写入预览及安全导出，限制文件大小与行列数量。 */
@Service
public class LedgerFileServiceImpl implements LedgerFileService {
    private final LedgerService service;
    private final ObjectMapper mapper;
    private static final Set<String> FIELDS=Set.of("amount","date","kind","account","category","merchant","note","currency","tags");
    private record Table(List<String> headers,List<List<String>> rows,List<Integer> numbers) { }
    /** 注入账本验证服务和 JSON 映射组件。 */
    public LedgerFileServiceImpl(LedgerService service,ObjectMapper mapper) { this.service=service; this.mapper=mapper; }
    /** 将文件格式、映射或文件内容错误转成统一业务错误，避免暴露解析堆栈。 */
    private LedgerException invalid() { return new LedgerException(LedgerException.Reason.INVALID_REQUEST); }
    /** 读取 UTF-8 CSV 或首张 Excel 工作表，拒绝公式、不支持格式、过大文件及超过 500 条记录。 */
    private Table read(MultipartFile file) {
        if (file.isEmpty() || file.getSize()>5*1024*1024) throw invalid();
        var name=Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        var rows=new ArrayList<List<String>>(); var numbers=new ArrayList<Integer>();
        try {
            if (name.endsWith(".csv")) {
                var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                var text=decoder.decode(java.nio.ByteBuffer.wrap(file.getBytes())).toString();
                if (text.startsWith("\uFEFF")) text=text.substring(1);
                try (var parser=CSVFormat.DEFAULT.parse(new StringReader(text))) {
                    for (var record : parser) {
                        var cells=new ArrayList<String>(); record.forEach(cells::add);
                        add(rows,numbers,cells,(int)record.getRecordNumber());
                    }
                }
            } else if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
                try (var workbook=WorkbookFactory.create(file.getInputStream())) {
                    if (workbook.getNumberOfSheets()==0) throw invalid();
                    var sheet=workbook.getSheetAt(0); var format=new DataFormatter(Locale.ROOT);
                    for (var row : sheet) {
                        if (row.getLastCellNum()>30) throw invalid();
                        var cells=new ArrayList<String>();
                        for (int i=0;i<Math.max(0,row.getLastCellNum());i++) {
                            var cell=row.getCell(i);
                            if (cell!=null && cell.getCellType()==CellType.FORMULA) throw invalid();
                            if (cell!=null && cell.getCellType()==CellType.NUMERIC && DateUtil.isCellDateFormatted(cell))
                                cells.add(cell.getLocalDateTimeCellValue().toLocalDate().toString());
                            else cells.add(cell==null ? "" : format.formatCellValue(cell));
                        }
                        add(rows,numbers,cells,row.getRowNum()+1);
                    }
                }
            } else throw invalid();
        } catch (IOException | RuntimeException exception) { throw invalid(); }
        if (rows.size()<2) throw invalid();
        var headers=rows.remove(0); numbers.remove(0);
        return new Table(headers,rows,numbers);
    }
    /** 保留非空行和源行号，限制列数与单元格长度，避免巨量或异常内容进入预览。 */
    private void add(List<List<String>> rows,List<Integer> numbers,List<String> cells,int number) {
        if (cells.size()>30 || cells.stream().anyMatch(c -> c.length()>2048)) throw invalid();
        if (cells.stream().allMatch(String::isBlank)) return;
        if (rows.size()>=501) throw invalid();
        rows.add(cells.stream().map(String::strip).toList()); numbers.add(number);
    }
    /** 返回表头和前五行示例，供前端配置字段映射，不写入数据库。 */
    @Override
    public Inspection inspect(MultipartFile file) {
        var table=read(file); return new Inspection(table.headers(),table.rows().stream().limit(5).toList(),table.rows().size());
    }
    /** 读取映射字段值，并还原本系统 CSV 导出为防公式执行而添加的转义前缀。 */
    private String value(List<String> row,Mapping mapping,String field) {
        var index=mapping.columns().get(field);
        var value=index==null || index>=row.size() ? "" : row.get(index).strip();
        if (value.length()>1 && value.charAt(0)=='\'' && "\'=+-@\t\r".indexOf(value.charAt(1))>=0) value=value.substring(1);
        return value;
    }
    /** 将中日文或机器值的收入支出方向转换为固定代码。 */
    private String kind(String value) {
        return switch(value.toUpperCase(Locale.ROOT)) {
            case "INCOME","收入","収入" -> "INCOME";
            case "EXPENSE","支出" -> "EXPENSE";
            default -> throw invalid();
        };
    }
    /** 接受标准日期和常见斜线日期，严格拒绝不存在的日期。 */
    private LocalDate date(String value) {
        for (var pattern : List.of("uuuu-MM-dd","uuuu/M/d","uuuu-M-d")) {
            try { return LocalDate.parse(value,DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)); }
            catch (DateTimeParseException ignored) { }
        }
        throw invalid();
    }
    /** 将分类代码或中日文名称映射到支持的分类，并以收支方向区分其他分类。 */
    private String category(String value,String kind) {
        if (value.isBlank() || value.equals("其他") || value.equals("その他")) return kind.equals("INCOME") ? "OTHER_INCOME" : "OTHER_EXPENSE";
        for (var category : LedgerCategory.values()) if (category.name().equalsIgnoreCase(value) || category.label().equals(value)) return category.name();
        var names=Map.ofEntries(Map.entry("工资","SALARY"),Map.entry("奖金","BONUS"),Map.entry("兼职","PART_TIME"),
                Map.entry("投资收益","INVESTMENT"),Map.entry("利息","INTEREST"),Map.entry("红包","GIFT"),Map.entry("其他收入","OTHER_INCOME"),
                Map.entry("餐饮","FOOD"),Map.entry("购物","SHOPPING"),Map.entry("交通","TRANSPORT"),Map.entry("住房","HOUSING"),
                Map.entry("娱乐","ENTERTAINMENT"),Map.entry("医疗","MEDICAL"),Map.entry("其他支出","OTHER_EXPENSE"));
        if (!names.containsKey(value)) throw invalid(); return names.get(value);
    }
    /** 检查映射列范围及必需字段；账户和方向允许用用户指定的默认值补充。 */
    private void mapping(Mapping mapping,Table table) {
        if (mapping==null || mapping.columns()==null || !mapping.columns().keySet().stream().allMatch(FIELDS::contains)
                || mapping.columns().values().stream().anyMatch(i -> i==null || i<0 || i>=table.headers().size())
                || !mapping.columns().containsKey("amount") || !mapping.columns().containsKey("date")
                || (!mapping.columns().containsKey("kind") && mapping.defaultKind()==null)
                || (!mapping.columns().containsKey("account") && mapping.defaultAccountId()==null)) throw invalid();
    }
    /** 按映射解析每行，提示字段错误及文件内和数据库重复；预览只读、不生成账户或流水。 */
    @Override
    public Preview preview(String username,MultipartFile file,String mappingJson) {
        final Mapping mapping;
        try { mapping=mapper.readValue(mappingJson,Mapping.class); } catch (IOException exception) { throw invalid(); }
        var table=read(file); mapping(mapping,table); var options=service.options(username);
        var results=new ArrayList<PreviewRow>(); var seen=new HashSet<List<Object>>();
        int valid=0,duplicates=0,invalid=0;
        for (int i=0;i<table.rows().size();i++) {
            var row=table.rows().get(i); EntryInput entry=null; boolean duplicate=false;
            var errors=new ArrayList<String>(); String stage="数据";
            try {
                stage="收支类型"; var type=value(row,mapping,"kind"); var kind=kind(type.isBlank() ? mapping.defaultKind()==null ? "" : mapping.defaultKind() : type);
                stage="账户/币种"; var name=value(row,mapping,"account"); var currency=value(row,mapping,"currency");
                var accounts=options.accounts().stream().filter(a -> name.isBlank() ? Objects.equals(a.id(),mapping.defaultAccountId()) : a.name().equals(name))
                        .filter(a -> currency.isBlank() || a.currency().equals(currency)).toList();
                if (accounts.size()!=1) throw invalid(); var account=accounts.get(0);
                stage="金额"; var amount=value(row,mapping,"amount");
                if (amount.contains(",")) { if (!amount.matches("[0-9]{1,3}(,[0-9]{3})+(\\.[0-9]+)?")) throw invalid(); amount=amount.replace(",",""); }
                if (!amount.matches("[0-9]{1,12}(\\.[0-9]{1,4})?")) throw invalid();
                stage="日期"; var date=date(value(row,mapping,"date"));
                stage="分类"; var category=value(row,mapping,"category");
                category=category(category.isBlank() ? Optional.ofNullable(mapping.defaultCategory()).orElse("") : category,kind);
                entry=new EntryInput(account.id(),kind,amount,date,category,value(row,mapping,"merchant"),value(row,mapping,"note"),value(row,mapping,"tags").isBlank() ? List.of() : Arrays.stream(value(row,mapping,"tags").split("[|,，]")).map(String::strip).toList());
                stage="金额精度、分类方向或文字长度"; service.validate(username,entry);
                var key=List.<Object>of(entry.accountId(),kind,new java.math.BigDecimal(amount).stripTrailingZeros(),date,category,entry.merchant(),entry.note(),entry.tags().stream().sorted().distinct().toList());
                duplicate=!seen.add(key) || service.duplicate(username,entry);
                valid++; if (duplicate) duplicates++;
            } catch (LedgerException | IllegalArgumentException exception) { errors.add(stage+"不合法，请检查格式和字段映射"); invalid++; }
            results.add(new PreviewRow(table.numbers().get(i),entry,duplicate,errors));
        }
        return new Preview(results,valid,duplicates,invalid);
    }
    /** 对 CSV 文本增加公式转义；Excel 导出使用字符串单元格，不执行用户文字。 */
    private String safe(String value) {
        return !value.isEmpty() && "\'=+-@\t\r".indexOf(value.charAt(0))>=0 ? "'"+value : value;
    }
    /** 生成含字段名的 UTF-8 BOM CSV 或 XLSX，可重新映射导入；只导出当前用户筛选数据。 */
    @Override
    public byte[] export(String username,String format,String kind,LocalDate start,LocalDate end,Long accountId) {
        if (!format.equals("csv") && !format.equals("xlsx")) throw invalid();
        var entries=service.exportEntries(username,kind,start,end,accountId);
        var headers=List.of("kind","amount","date","account","category","merchant","note","currency","tags");
        try {
            var output=new ByteArrayOutputStream();
            if (format.equals("csv")) {
                output.write(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf});
                try (var printer=new CSVPrinter(new OutputStreamWriter(output,StandardCharsets.UTF_8),CSVFormat.DEFAULT)) {
                    printer.printRecord(headers);
                    for (var entry : entries) printer.printRecord(cells(entry).stream().map(this::safe).toList());
                }
            } else {
                try (var workbook=new XSSFWorkbook()) {
                    var sheet=workbook.createSheet("Transactions"); var row=sheet.createRow(0);
                    for (int i=0;i<headers.size();i++) row.createCell(i).setCellValue(headers.get(i));
                    int index=1;
                    for (var entry : entries) { row=sheet.createRow(index++); var cells=cells(entry);
                        for (int i=0;i<cells.size();i++) row.createCell(i).setCellValue(cells.get(i)); }
                    workbook.write(output);
                }
            }
            return output.toByteArray();
        } catch (IOException exception) { throw invalid(); }
    }
    /** 按固定导出列顺序提供原始业务值，金额保持十进制字符串且避免无意义的末尾零。 */
    private List<String> cells(LedgerEntry entry) {
        return List.of(entry.kind(),entry.amount().stripTrailingZeros().toPlainString(),entry.date().toString(),entry.accountName(),
                entry.category(),entry.merchant(),entry.note(),entry.currency(),String.join("|",entry.tags()));
    }
}
