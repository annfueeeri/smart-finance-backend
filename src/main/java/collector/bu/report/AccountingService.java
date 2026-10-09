package collector.bu.report;

import collector.bu.ledger.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static collector.bu.report.ReportModels.*;

/** 个人账户资料、日终估值和内部转账业务；这些操作不作为预算消费。 */
@Service
public class AccountingService {
    private final LedgerService service;
    private final LedgerDao ledger;
    private final ReportDao reports;
    /** 注入用户验证、账户访问及辅助财务 SQL。 */
    public AccountingService(LedgerService service,LedgerDao ledger,ReportDao reports) { this.service=service;this.ledger=ledger;this.reports=reports; }
    /** 复查本人账户，其他人的账户统一返回不存在。 */
    private LedgerAccount account(long userId,Long id) { return ledger.account(userId,id==null ? 0 : id).orElseThrow(() -> new LedgerException(LedgerException.Reason.ACCOUNT_NOT_FOUND)); }
    /** 检查带符号或正数金额，不舍入超出币种精度的值。 */
    private void money(String text,String currency,boolean signed) {
        if(text==null || !text.matches((signed ? "-?" : "")+"[0-9]{1,12}(\\.[0-9]{1,4})?") || new BigDecimal(text).scale()>Currency.getInstance(currency).getDefaultFractionDigits()
                || (!signed && new BigDecimal(text).signum()<=0))throw new LedgerException(LedgerException.Reason.INVALID_REQUEST);
    }
    /** 校验日期范围和备注长度，避免数据库截断。 */
    private void date(LocalDate date,String note) { if(date==null || date.getYear()<1900 || date.getYear()>9998 || (note!=null && note.length()>1000))throw new LedgerException(LedgerException.Reason.INVALID_REQUEST); }
    /** 保存账户资料并记录修改用户，同名同币种冲突返回409。 */
    @Transactional
    public LedgerAccount profile(String username,long id,AccountProfile input) {
        var user=service.actor(username);ledger.lockUser(user.id());account(user.id(),id);
        if(input.name()==null || input.name().strip().isEmpty() || input.name().length()>80 || !Set.of("BANK","CASH","EWALLET","INVESTMENT","LIABILITY").contains(input.type()==null ? "" : input.type()))throw new LedgerException(LedgerException.Reason.INVALID_REQUEST);
        try { reports.profile(user.id(),id,username,input);return account(user.id(),id); }catch(DuplicateKeyException e) { throw new LedgerException(LedgerException.Reason.ACCOUNT_DUPLICATE); }
    }
    /** 原子保存本人同币种账户内部转账，拒绝同账户和跨用户转账，不生成收支或预算通知。 */
    @Transactional
    public Transfer transfer(String username,TransferInput input) {
        var user=service.actor(username);ledger.lockUser(user.id());var from=account(user.id(),input.fromAccountId());var to=account(user.id(),input.toAccountId());
        if(from.id()==to.id() || !from.currency().equals(to.currency()))throw new LedgerException(LedgerException.Reason.INVALID_REQUEST);
        money(input.amount(),from.currency(),false);date(input.date(),input.note());return reports.transfer(user.id(),username,from,to,input);
    }
    /** 查询本人内部转账，保留真实转账主键和审计信息。 */
    @Transactional(readOnly=true)
    public List<Transfer> transfers(String username) { return reports.transfers(service.actor(username).id(),LocalDate.of(9998,12,31)); }
    /** 新增本人账户日终余额/投资市值，必须不早于开户余额基准；旧值保留以支持历史曲线。 */
    @Transactional
    public Valuation value(String username,long id,ValuationInput input) {
        var user=service.actor(username);ledger.lockUser(user.id());var account=account(user.id(),id);money(input.balance(),account.currency(),true);date(input.date(),input.note());
        if(input.date().isBefore(account.openingDate()))throw new LedgerException(LedgerException.Reason.INVALID_REQUEST);
        return reports.value(user.id(),username,id,input);
    }
    /** 返回本人某账户的全部余额校准历史，先验证账户归属。 */
    @Transactional(readOnly=true)
    public List<Valuation> values(String username,long id) { var user=service.actor(username);account(user.id(),id);return reports.valuations(user.id(),LocalDate.of(9998,12,31)).stream().filter(v -> v.accountId()==id).toList(); }
}
