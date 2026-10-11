package collector.bu.service.impl;

import collector.bu.service.AccountingService;

import collector.bu.entity.LedgerAccount;
import collector.bu.exception.LedgerException;
import collector.bu.service.LedgerService;

import collector.bu.dao.AccountDao;
import collector.bu.dao.TransferDao;
import collector.bu.dao.AccountValuationDao;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static collector.bu.model.ReportModels.*;

/** 当前账本账户资料、日终估值和内部转账业务；这些操作不作为预算消费。 */
@Service
@org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class AccountingServiceImpl implements AccountingService {
    private final LedgerService service;
    private final AccountDao accounts;

    private final TransferDao transfers;
    private final AccountValuationDao valuations;
    /** 注入用户验证、账户访问及辅助财务 SQL。 */
    public AccountingServiceImpl(LedgerService service,AccountDao accounts,TransferDao transfers,AccountValuationDao valuations) { this.service=service;this.accounts=accounts;this.transfers=transfers;this.valuations=valuations; }
    /** 复查当前账本账户，其他账本的账户统一返回不存在。 */
    private LedgerAccount account(long userId,Long id) { return accounts.account(userId,id==null ? 0 : id).orElseThrow(() -> new LedgerException(LedgerException.Reason.ACCOUNT_NOT_FOUND)); }
    /** 检查带符号或正数金额，不舍入超出币种精度的值。 */
    private void money(String text,String currency,boolean signed) {
        if(text==null || !text.matches((signed ? "-?" : "")+"[0-9]{1,12}(\\.[0-9]{1,4})?") || new BigDecimal(text).scale()>Currency.getInstance(currency).getDefaultFractionDigits()
                || (!signed && new BigDecimal(text).signum()<=0))throw new LedgerException(LedgerException.Reason.INVALID_REQUEST);
    }
    /** 校验日期范围和备注长度，避免数据库截断。 */
    private void date(LocalDate date,String note) { if(date==null || date.getYear()<1900 || date.getYear()>9998 || (note!=null && note.length()>1000))throw new LedgerException(LedgerException.Reason.INVALID_REQUEST); }
    /** 保存账户资料并记录修改用户，同名同币种冲突返回409。 */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @Override
    public LedgerAccount profile(String username,long id,AccountProfile input) {
        var book=service.context(username);account(book.id(),id);
        if(input.name()==null || input.name().strip().isEmpty() || input.name().length()>80 || !Set.of("BANK","CASH","EWALLET","INVESTMENT","LIABILITY").contains(input.type()==null ? "" : input.type()))throw new LedgerException(LedgerException.Reason.INVALID_REQUEST);
        try { accounts.profile(book.id(),id,username,input);return account(book.id(),id); }catch(DuplicateKeyException e) { throw new LedgerException(LedgerException.Reason.ACCOUNT_DUPLICATE); }
    }
    /** 原子保存当前账本同币种账户内部转账，拒绝同账户和跨账本转账，不生成收支或预算通知。 */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @Override
    public Transfer transfer(String username,TransferInput input) {
        var book=service.context(username);var from=account(book.id(),input.fromAccountId());var to=account(book.id(),input.toAccountId());
        if(from.id()==to.id() || !from.currency().equals(to.currency()))throw new LedgerException(LedgerException.Reason.INVALID_REQUEST);
        money(input.amount(),from.currency(),false);date(input.date(),input.note());return transfers.transfer(book.id(),username,from,to,input);
    }
    /** 查询当前账本内部转账，保留真实转账主键和审计信息。 */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @Override
    public List<Transfer> transfers(String username) { return transfers.transfers(service.context(username).id(),LocalDate.of(9998,12,31)); }
    /** 新增当前账本账户日终余额/投资市值，必须不早于开户余额基准；旧值保留以支持历史曲线。 */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @Override
    public Valuation value(String username,long id,ValuationInput input) {
        var book=service.context(username);var account=account(book.id(),id);money(input.balance(),account.currency(),true);date(input.date(),input.note());
        if(input.date().isBefore(account.openingDate()))throw new LedgerException(LedgerException.Reason.INVALID_REQUEST);
        return valuations.value(book.id(),username,id,input);
    }
    /** 返回当前账本某账户的全部余额校准历史，先验证账户归属。 */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @Override
    public List<Valuation> values(String username,long id) { var book=service.context(username);account(book.id(),id);return valuations.valuations(book.id(),LocalDate.of(9998,12,31)).stream().filter(v -> v.accountId()==id).toList(); }
}
