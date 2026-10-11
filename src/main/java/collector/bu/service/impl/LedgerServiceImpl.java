package collector.bu.service.impl;

import collector.bu.service.BudgetService;

import collector.bu.service.LedgerService;

import collector.bu.entity.LedgerAccount;
import collector.bu.entity.LedgerEntry;
import collector.bu.exception.LedgerException;
import collector.bu.model.LedgerCategory;
import collector.bu.support.TransactionTagCodec;

import collector.bu.dao.AccountDao;
import collector.bu.dao.TransactionDao;
import collector.bu.model.BookContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Currency;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static collector.bu.model.LedgerModels.*;

/** 复查当前账号并处理当前账本记账业务，管理员也不获得其他账本的财务数据权限。 */
@Service
@org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class LedgerServiceImpl implements LedgerService {
    private final collector.bu.service.BookService books;
    private final AccountDao accounts;
    private final TransactionDao transactions;
    private final TransactionTagCodec tags;
    private final collector.bu.service.BudgetService budgets;
    /** 注入数据库账号和个人账本访问组件。 */
    public LedgerServiceImpl(collector.bu.service.BookService books, AccountDao accounts, TransactionDao transactions, TransactionTagCodec tags, collector.bu.service.BudgetService budgets) { this.books=books; this.accounts=accounts; this.transactions=transactions; this.tags=tags; this.budgets=budgets; }
    /** 核验当前账号与家庭成员资格，返回并锁定服务端选定的个人或家庭账本。 */
    @Override
    public BookContext context(String username) { return books.resolve(username); }

    /** 返回当前用户可使用的账户和分类，以用户时区生成默认日期。 */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @Override
    public Options options(String username) {
        var book=context(username);
        return new Options(accounts.accounts(book.id()), Arrays.stream(LedgerCategory.values())
                .map(c -> new CategoryOption(c.name(),c.kind(),c.label())).toList(), book.currency(), book.timezone(),
                LocalDate.now(ZoneId.of(book.timezone())));
    }
    /** 创建真实当前账本收付款账户，同名同币种账户冲突时返回明确业务错误。 */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @Override
    public LedgerAccount createAccount(String username, AccountInput input) {
        var book=context(username);
        var name=input.name()==null ? "" : input.name().strip();
        var currency=input.currency()==null ? book.currency() : input.currency();
        try {
            if (name.isBlank() || name.length()>80 || Currency.getInstance(currency).getDefaultFractionDigits()<0) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) { throw new LedgerException(LedgerException.Reason.INVALID_REQUEST); }
        var type=input.type()==null ? "CASH" : input.type();
        var opening=input.openingBalance()==null ? "0" : input.openingBalance();
        var date=input.openingDate()==null ? LocalDate.of(1900,1,1) : input.openingDate();
        if(!java.util.Set.of("BANK","CASH","EWALLET","INVESTMENT","LIABILITY").contains(type) || !opening.matches("-?[0-9]{1,12}(\\.[0-9]{1,4})?")
                || new BigDecimal(opening).scale()>Currency.getInstance(currency).getDefaultFractionDigits() || date.getYear()<1900 || date.getYear()>9998)throw new LedgerException(LedgerException.Reason.INVALID_REQUEST);

        try { return accounts.createAccount(book.id(), username, name, currency,type,new BigDecimal(opening),date); }
        catch (DuplicateKeyException exception) { throw new LedgerException(LedgerException.Reason.ACCOUNT_DUPLICATE); }
    }
    /** 验证账户归属、类型与分类、正数金额、日期和可选文字；不允许金额被数据库舍入。 */
    @Override
    public LedgerAccount validate(String username, EntryInput input) {
        var book=context(username);
        if (input.accountId()==null) throw new LedgerException(LedgerException.Reason.INVALID_REQUEST);
        var account=accounts.account(book.id(),input.accountId())
                .orElseThrow(() -> new LedgerException(LedgerException.Reason.ACCOUNT_NOT_FOUND));
        try {
            if(input.tags()!=null && (input.tags().size()>10 || input.tags().stream().anyMatch(tag -> tag==null || tag.isBlank() || tag.strip().length()>30 || tag.codePoints().anyMatch(ch -> Character.isISOControl(ch) || ch=='|' || ch==',' || ch=='，' || ch==';'))))throw new IllegalArgumentException();
            var category=LedgerCategory.valueOf(input.category());
            if (!category.kind().equals(input.kind()) || input.amount()==null
                    || !input.amount().matches("[0-9]{1,12}(\\.[0-9]{1,4})?") || input.date()==null
                    || input.date().getYear()<1 || input.date().getYear()>9999
                    || clean(input.merchant()).length()>120 || clean(input.note()).length()>1000) throw new IllegalArgumentException();
            var amount=new BigDecimal(input.amount());
            if (amount.signum()<=0 || amount.scale()>Currency.getInstance(account.currency()).getDefaultFractionDigits()) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException exception) { throw new LedgerException(LedgerException.Reason.INVALID_REQUEST); }
        return account;
    }
    /** 规范可选文字，未填写保存为空字符串，去掉首尾空白。 */
    @Override
    public String clean(String value) { return value==null ? "" : value.strip(); }
    /** 新增当前账本收支记录，币种取自归属账户，创建与修改用户均由当前认证账号记录。 */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @Override
    public LedgerEntry createEntry(String username, EntryInput input) {
        var book=context(username);
        var account=validate(username,input);

        var entry=transactions.createEntry(book.id(),username,account,input.kind(),new BigDecimal(input.amount()),input.date(),
                input.category(),clean(input.merchant()),clean(input.note()),tags.normalizeTags(input.tags()));
        if(input.kind().equals("EXPENSE"))budgets.refresh(username);
        return entry;
    }
    /** 查询当前账本分页记录，日期闭区间筛选，账户筛选也必须属于当前账本。 */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @Override
    public EntryPage list(String username, String kind, LocalDate start, LocalDate end, Long accountId, int page, int size) {
        var book=context(username);
        if ((kind!=null && !kind.equals("INCOME") && !kind.equals("EXPENSE")) || page<0 || size<1 || size>100
                || (start!=null && end!=null && start.isAfter(end))) throw new LedgerException(LedgerException.Reason.INVALID_REQUEST);
        if (accountId!=null && accounts.account(book.id(),accountId).isEmpty()) throw new LedgerException(LedgerException.Reason.ACCOUNT_NOT_FOUND);
        return new EntryPage(transactions.entries(book.id(),kind,start,end,accountId,page,size),
                transactions.count(book.id(),kind,start,end,accountId), page,size);
    }
    /** 原子导入预览确认后的记录；再次验证，默认跳过重复，任何错误令整个批次回滚。 */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @Override
    public ImportResult importEntries(String username, ImportInput input) {
        var book=context(username);

        int imported=0, skipped=0; boolean expense=false;
        for (var entry : input.entries()) {
            var account=validate(username,entry);
            if (!Boolean.FALSE.equals(input.skipDuplicates()) && transactions.duplicate(book.id(),entry)) { skipped++; continue; }
            transactions.createEntry(book.id(),username,account,entry.kind(),new BigDecimal(entry.amount()),entry.date(),entry.category(),clean(entry.merchant()),clean(entry.note()),tags.normalizeTags(entry.tags()));
            imported++; expense=expense || entry.kind().equals("EXPENSE");
        }
        if(expense)budgets.refresh(username);
        return new ImportResult(imported,skipped);
    }
    /** 检测一条当前账本流水是否已存在，供导入预览使用。 */
    @Override
    public boolean duplicate(String username, EntryInput input) { return transactions.duplicate(context(username).id(),input); }
    /** 导出全部筛选记录，最多一万条；超限要求缩小筛选范围，避免静默截断。 */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @Override
    public java.util.List<LedgerEntry> exportEntries(String username,String kind,LocalDate start,LocalDate end,Long accountId) {
        var page=list(username,kind,start,end,accountId,0,1);
        if (page.total()>10000) throw new LedgerException(LedgerException.Reason.INVALID_REQUEST);
        return transactions.entries(context(username).id(),kind,start,end,accountId,0,10000);
    }
}
