package collector.bu.report;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.math.BigDecimal;
import org.apache.commons.csv.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Service;
import static collector.bu.report.ReportModels.*;

/** 从同一报表结果生成完整CSV、含原生图表XLSX、含矢量图及统计明细PDF。 */
@Service
public class ReportExport {
    private static final List<String> HEADERS=List.of("section","currency","date","name","value","count","percentage","account");
    /** 把所有统计拆为统一行，货币字符串不改变精度，所有导出格式使用相同明细。 */
    private List<List<String>> rows(Report report) {
        var rows=new ArrayList<List<String>>();var f=report.filter();
        add(rows,"FILTER","",f.start()+" ~ "+f.end(),"grouping",f.grouping(),"","","");
        add(rows,"FILTER","","","account/currency/kind/category/tag",Objects.toString(f.accountId(),"ALL")+" / "+Objects.toString(f.currency(),"ALL")+" / "+Objects.toString(f.kind(),"ALL")+" / "+Objects.toString(f.category(),"ALL")+" / "+Objects.toString(f.tag(),"ALL"),"","","");
        for(var c : report.currencies()) {
            add(rows,"SUMMARY",c.currency(),"","income",c.summary().income(),""+c.summary().incomeCount(),"","");
            add(rows,"SUMMARY",c.currency(),"","expense",c.summary().expense(),""+c.summary().expenseCount(),"","");
            add(rows,"SUMMARY",c.currency(),"","net",c.summary().net(),"","","");add(rows,"SUMMARY",c.currency(),"","averageDailyExpense",c.summary().averageDailyExpense(),"","","");
            for(var p : c.trend()) {add(rows,"TREND",c.currency(),p.start()+" ~ "+p.end(),"income",p.income(),"","","");add(rows,"TREND",c.currency(),p.start()+" ~ "+p.end(),"expense",p.expense(),"","","");add(rows,"TREND",c.currency(),p.start()+" ~ "+p.end(),"net",p.net(),"","","");}
            for(var p : c.categories())add(rows,"CATEGORY",c.currency(),"",p.category(),p.amount(),""+p.count(),p.percentage(),"");
            for(var p : c.categoryTrend())add(rows,"CATEGORY_TREND",c.currency(),p.month(),p.category(),p.amount(),"","","");
            for(var p : c.merchants())add(rows,"MERCHANT",c.currency(),"",p.name(),p.amount(),""+p.count(),"","");for(var p : c.tags())add(rows,"TAG",c.currency(),"",p.name(),p.amount(),""+p.count(),"","");
            for(var p : c.cashFlow()) {
                var names=List.of("openingBalance","inflow","outflow","internalInflow","internalOutflow","balanceAdjustment","closingBalance");
                var values=List.of(p.openingBalance(),p.inflow(),p.outflow(),p.internalInflow(),p.internalOutflow(),p.balanceAdjustment(),p.closingBalance());
                for(int i=0;i<names.size();i++)add(rows,"CASH_FLOW",c.currency(),"",p.name()+" / "+names.get(i),values.get(i),"","",""+p.accountId());
                add(rows,"BALANCE_KNOWN",c.currency(),"",p.name(),p.openingKnown()+" / "+p.closingKnown(),"","",""+p.accountId());
            }
            for(var p : c.assets()) {add(rows,"ASSET",c.currency(),p.date().toString(),"assets",p.assets(),"","","");add(rows,"ASSET",c.currency(),p.date().toString(),"liabilities",p.liabilities(),"","","");add(rows,"ASSET",c.currency(),p.date().toString(),"netAssets",p.netAssets(),"","","");add(rows,"ASSET_UNKNOWN",c.currency(),p.date().toString(),"unknownAccounts",""+p.unknownAccounts(),"","","");}
            for(var account : c.balances())for(var p : account.history())add(rows,"ACCOUNT_BALANCE",c.currency(),p.date().toString(),account.name()+" / "+account.type(),p.known() ? p.balance() : "UNKNOWN","","",""+account.accountId());
            for(boolean year : List.of(false,true)) {
                var comparison=year ? c.yearOverYear() : c.monthOverMonth();var changes=List.of(comparison.income(),comparison.expense(),comparison.net());var names=List.of("income","expense","net");
                for(int i=0;i<changes.size();i++) {var change=changes.get(i);add(rows,year ? "YOY" : "MOM",c.currency(),comparison.start()+" ~ "+comparison.end(),names.get(i)+" current / previous / difference",change.current()+" / "+change.previous()+" / "+change.difference(),"",change.percentage()==null ? "N/A" : change.percentage(),"");}
            }
        }
        return rows;
    }
    /** 追加标准明细行，保持列数固定，避免CSV错列。 */
    private void add(List<List<String>> rows,String section,String currency,String date,String name,String value,String count,String percentage,String account) { rows.add(List.of(section,currency,date,name,value,count,percentage,account)); }
    /** CSV防止公式执行，用户文字仍在报表JSON和XLSX字符串单元格中完整保留。 */
    private String safe(String value) { return !value.isEmpty() && "'=+-@\t\r".indexOf(value.charAt(0))>=0 ? "'"+value : value; }
    /** 将完整统计写为UTF-8 BOM CSV，包含筛选条件和比较期，不仅导出页面当前可见表格。 */
    private byte[] csv(List<List<String>> rows) throws IOException {
        var out=new ByteArrayOutputStream();out.write(new byte[]{(byte)239,(byte)187,(byte)191});
        try(var printer=new CSVPrinter(new OutputStreamWriter(out,StandardCharsets.UTF_8),CSVFormat.DEFAULT)) {printer.printRecord(HEADERS);for(var row : rows)printer.printRecord(row.stream().map(this::safe).toList());}return out.toByteArray();
    }
    /** 为Excel写入图表数据及原生折线图，双精度仅用于图表展示，精确货币明细保留字符串。 */
    private void lineChart(XSSFSheet sheet,int firstRow,int anchorRow,String title,List<String> labels,List<String> names,List<List<String>> values) {
        if(labels.isEmpty())return;
        for(int i=0;i<labels.size();i++) {var row=sheet.createRow(firstRow+i);row.createCell(0).setCellValue(labels.get(i));for(int j=0;j<values.size();j++)row.createCell(j+1).setCellValue(new BigDecimal(values.get(j).get(i)).doubleValue());}
        var drawing=sheet.createDrawingPatriarch();var chart=drawing.createChart(drawing.createAnchor(0,0,0,0,5,anchorRow,16,anchorRow+15));chart.setTitleText(title);chart.getOrAddLegend().setPosition(LegendPosition.BOTTOM);
        var axis=chart.createCategoryAxis(AxisPosition.BOTTOM);var valueAxis=chart.createValueAxis(AxisPosition.LEFT);var data=(XDDFLineChartData)chart.createData(ChartTypes.LINE,axis,valueAxis);
        var categories=XDDFDataSourcesFactory.fromStringCellRange(sheet,new CellRangeAddress(firstRow,firstRow+labels.size()-1,0,0));
        for(int j=0;j<values.size();j++){var numbers=XDDFDataSourcesFactory.fromNumericCellRange(sheet,new CellRangeAddress(firstRow,firstRow+labels.size()-1,j+1,j+1));var series=(XDDFLineChartData.Series)data.addSeries(categories,numbers);series.setTitle(names.get(j),null);series.setSmooth(false);series.setMarkerStyle(MarkerStyle.NONE);}
        chart.plot(data);
    }
    /** Excel分类占比采用原生饼图，空分类不生成伪造扇区。 */
    private void pieChart(XSSFSheet sheet,int firstRow,List<CategoryShare> categories) {
        if(categories.isEmpty())return;
        for(int i=0;i<categories.size();i++){var row=sheet.createRow(firstRow+i);row.createCell(0).setCellValue(categories.get(i).category());row.createCell(1).setCellValue(new BigDecimal(categories.get(i).amount()).doubleValue());}
        var drawing=sheet.createDrawingPatriarch();var chart=drawing.createChart(drawing.createAnchor(0,0,0,0,5,17,16,32));chart.setTitleText("分类支出占比");chart.getOrAddLegend().setPosition(LegendPosition.RIGHT);
        var data=(XDDFPieChartData)chart.createData(ChartTypes.PIE,null,null);data.addSeries(XDDFDataSourcesFactory.fromStringCellRange(sheet,new CellRangeAddress(firstRow,firstRow+categories.size()-1,0,0)),XDDFDataSourcesFactory.fromNumericCellRange(sheet,new CellRangeAddress(firstRow,firstRow+categories.size()-1,1,1)));chart.plot(data);
    }
    /** 输出完整精确明细和每币种独立的收支、分类及资产图表工作表。 */
    private byte[] excel(Report report,List<List<String>> rows) throws IOException {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var data=book.createSheet("Report Data");var header=data.createRow(0);for(int i=0;i<HEADERS.size();i++)header.createCell(i).setCellValue(HEADERS.get(i));
            int index=1;for(var values : rows){var row=data.createRow(index++);for(int i=0;i<values.size();i++)row.createCell(i).setCellValue(values.get(i));}data.createFreezePane(0,1);for(int i=0;i<8;i++)data.setColumnWidth(i,6500);
            for(var c : report.currencies()) {
                var sheet=book.createSheet("Charts "+c.currency());
                lineChart(sheet,1,1,"收支趋势 "+c.currency(),c.trend().stream().map(p -> p.start().toString()).toList(),List.of("收入","支出"),List.of(c.trend().stream().map(Trend::income).toList(),c.trend().stream().map(Trend::expense).toList()));
                pieChart(sheet,c.trend().size()+5,c.categories());
                lineChart(sheet,c.trend().size()+c.categories().size()+10,34,"资产变化 "+c.currency(),c.assets().stream().map(p -> p.date().toString()).toList(),List.of("资产","负债","净资产"),List.of(c.assets().stream().map(AssetPoint::assets).toList(),c.assets().stream().map(AssetPoint::liabilities).toList(),c.assets().stream().map(AssetPoint::netAssets).toList()));
            }
            book.write(out);return out.toByteArray();
        }
    }
    /** 将PDF不支持的极少数字形换为问号；完整原文保存在JSON/CSV/XLSX，中文日文字形使用嵌入字体。 */
    private String printable(PDType0Font font,String text) throws IOException {
        var result=new StringBuilder();for(var iterator=text.codePoints().iterator();iterator.hasNext();) {int cp=iterator.nextInt();if(cp<32)result.append(' ');else {try {font.encode(new String(Character.toChars(cp)));result.appendCodePoint(cp);}catch(IllegalArgumentException e){result.append('?');}}}return result.toString();
    }
    /** 含自动分页的PDF排版器，明细换行而不裁掉用户商家或标签。 */
    private class Pdf implements AutoCloseable {
        final PDDocument document;final PDType0Font font;PDPageContentStream stream;float y;
        /** 从随应用打包的OFL中文字体初始化可移植PDF，不依赖运行机器字体。 */
        Pdf(PDDocument document) throws IOException { this.document=document;try(var input=ReportExport.class.getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")){font=PDType0Font.load(document,input);}page(); }
        /** 新增A4页并关闭上一页绘制流，确保所有明细均可写入。 */
        void page() throws IOException { if(stream!=null)stream.close();var page=new PDPage(PDRectangle.A4);document.addPage(page);stream=new PDPageContentStream(document,page);y=795; }
        /** 绘制指定位置文字，兼容中文、日文和拉丁字符。 */
        void text(float x,float y,String value,float size) throws IOException {stream.beginText();stream.setFont(font,size);stream.newLineAtOffset(x,y);stream.showText(printable(font,value));stream.endText();}
        /** 按真实字体宽度自动换行，页末不足时新增页面。 */
        void line(String value,float size) throws IOException {
            var text=printable(font,value);var part=new StringBuilder();
            for(var iterator=text.codePoints().iterator();iterator.hasNext();) {int cp=iterator.nextInt();var next=part.toString()+new String(Character.toChars(cp));if(font.getStringWidth(next)/1000*size>510 && !part.isEmpty()){if(y<45)page();text(40,y,part.toString(),size);y-=size+5;part.setLength(0);}part.appendCodePoint(cp);}
            if(y<45)page();text(40,y,part.toString(),size);y-=size+7;
        }
        /** 绘制折线图，金额仅在定位图形时转为double，统计文字保持精确值。 */
        void chart(String title,List<List<String>> values,List<String> names) throws IOException {
            if(y<225)page();line(title,12);float top=y,left=50,width=490,height=95;
            double min=0,max=0;for(var row : values)for(var value : row){double n=Double.parseDouble(value);min=Math.min(min,n);max=Math.max(max,n);}if(max==min)max=min+1;
            stream.setStrokingColor(120/255f,135/255f,140/255f);stream.moveTo(left,top-height);stream.lineTo(left+width,top-height);stream.stroke();
            int[][] colors={{32,128,110},{210,111,75},{70,103,170}};
            for(int j=0;j<values.size();j++){var row=values.get(j);stream.setStrokingColor(colors[j%3][0]/255f,colors[j%3][1]/255f,colors[j%3][2]/255f);stream.setLineWidth(1.4f);for(int i=0;i<row.size();i++){float x=left+(row.size()<2 ? 0 : width*i/(row.size()-1));float p=top-height+(float)((Double.parseDouble(row.get(i))-min)/(max-min)*height);if(i==0)stream.moveTo(x,p);else stream.lineTo(x,p);}if(row.size()==1){stream.lineTo(left+3,top-height+(float)((Double.parseDouble(row.get(0))-min)/(max-min)*height));}if(!row.isEmpty())stream.stroke();}
            y=top-height-15;line(String.join(" / ",names)+" · 纵轴 "+String.format(Locale.ROOT,"%.2f ~ %.2f",min,max),9);
        }
        /** 用金额比例绘制分类横条，所有分类的统计摘要保留在明细页。 */
        void categories(List<CategoryShare> rows) throws IOException {
            line("分类支出占比",12);if(rows.isEmpty()){line("本期没有支出",10);return;}
            for(var row : rows){if(y<65)page();text(40,y,row.category()+"  "+row.percentage()+"%",9);stream.setNonStrokingColor(32/255f,128/255f,110/255f);stream.addRect(245,y-2,270*(float)(Double.parseDouble(row.percentage())/100),8);stream.fill();stream.setNonStrokingColor(0,0,0);y-=17;}
        }
        /** 关闭最后一页绘制流，交给文档写入PDF字节。 */
        public void close() throws IOException {if(stream!=null)stream.close();}
    }
    /** 生成可下载PDF，包含摘要、矢量趋势及分类图，之后分页保存全部统计明细。 */
    private byte[] pdf(Report report,List<List<String>> rows) throws IOException {
        try(var document=new PDDocument();var out=new ByteArrayOutputStream()) {
            try(var pdf=new Pdf(document)) {
                for(int i=0;i<report.currencies().size();i++){var c=report.currencies().get(i);if(i>0)pdf.page();pdf.line("Smart Finance 财务报表 · "+c.currency(),18);pdf.line(report.filter().start()+" ~ "+report.filter().end()+" / "+report.filter().grouping(),10);
                    pdf.line("收入 "+c.summary().income()+" · 支出 "+c.summary().expense()+" · 净结余 "+c.summary().net(),11);pdf.line("平均日支出 "+c.summary().averageDailyExpense()+" · 内部转账不计入收支",10);
                    pdf.chart("收支趋势",List.of(c.trend().stream().map(Trend::income).toList(),c.trend().stream().map(Trend::expense).toList()),List.of("收入（绿）","支出（橙）"));pdf.categories(c.categories());
                    pdf.chart("资产、负债与净资产变化",List.of(c.assets().stream().map(AssetPoint::assets).toList(),c.assets().stream().map(AssetPoint::liabilities).toList(),c.assets().stream().map(AssetPoint::netAssets).toList()),List.of("资产（绿）","负债（橙）","净资产（蓝）"));
                    pdf.line("账户现金流/余额按完整账户流水计算；分类/标签/方向只筛选收支分析。",9);pdf.line("余额基准前未知账户不计入资产；估值为日终值，不是银行实时余额。",9);
                }
                pdf.page();pdf.line("完整统计明细及筛选条件",16);for(var row : rows)pdf.line(String.join(" | ",row),8);
            }
            document.save(out);return out.toByteArray();
        }
    }
    /** 选择CSV/XLSX/PDF导出，不支持格式拒绝；解析或字体故障不伪装成成功文件。 */
    public byte[] export(Report report,String format) {
        var rows=rows(report);
        try {return switch(format){case "csv" -> csv(rows);case "xlsx" -> excel(report,rows);case "pdf" -> pdf(report,rows);default -> throw new collector.bu.ledger.LedgerException(collector.bu.ledger.LedgerException.Reason.INVALID_REQUEST);};}
        catch(IOException e){throw new IllegalStateException("Report export failed",e);}
    }
}
