package collector.bu.dao;

import collector.bu.entity.ledger.LedgerAccount;
import collector.bu.entity.ledger.LedgerEntry;
import collector.bu.model.ledger.LedgerModels;
import collector.bu.support.ledger.TransactionTagCodec;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 收支流水模块 SQL：新增、分页、查重、标签及真实支出汇总。 */
@Repository
public class TransactionDao {
    private final JdbcTemplate jdbc;
    private final TransactionTagCodec tags;
    private final RowMapper<LedgerEntry> ENTRY;
    private static final String SELECT_ENTRY = "SELECT t.*, a.name AS account_name FROM ledger_transaction t "
            + "JOIN ledger_account a ON a.id=t.account_id AND a.user_id=t.user_id ";
    /** 注入JDBC和统一标签编解码器，保持录入、导入及查重使用相同标签格式。 */
    public TransactionDao(JdbcTemplate jdbc, TransactionTagCodec tags) {
        this.jdbc=jdbc; this.tags=tags;
        ENTRY = (rs, row) -> new LedgerEntry(rs.getLong("id"),
            rs.getString("kind"), rs.getBigDecimal("amount"), rs.getString("currency"),
            rs.getDate("transaction_date").toLocalDate(), rs.getLong("account_id"), rs.getString("account_name"),
            rs.getString("category"), rs.getString("merchant"), rs.getString("note"),
            rs.getTimestamp("created_at").toLocalDateTime(), rs.getString("created_by"),
            rs.getTimestamp("updated_at").toLocalDateTime(), rs.getString("updated_by"), rs.getBoolean("is_deleted"),tags.decodeTags(rs.getString("tags_json")));
    }

    /** 创建收支记录；账户及币种已经由业务层验证，操作者由认证信息确定。 */
    public LedgerEntry createEntry(long userId, String actor, LedgerAccount account, String kind, BigDecimal amount,
            LocalDate date, String category, String merchant, String note,List<String> tags) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO ledger_transaction "
                    + "(user_id,account_id,kind,amount,currency,transaction_date,category,merchant,note,created_by,updated_by,tags_json) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", new String[]{"id"});
            statement.setLong(1, userId); statement.setLong(2, account.id()); statement.setString(3, kind);
            statement.setBigDecimal(4, amount); statement.setString(5, account.currency());
            statement.setObject(6, date); statement.setString(7, category); statement.setString(8, merchant);
            statement.setString(9, note); statement.setString(10, actor); statement.setString(11, actor);statement.setString(12,this.tags.encodeTags(tags));return statement;
        }, keys);
        return jdbc.query(SELECT_ENTRY + "WHERE t.user_id=? AND t.id=?", ENTRY, userId, keys.getKey().longValue()).get(0);
    }
    /** 组合固定允许的筛选条件，所有外部输入均通过占位符绑定。 */
    private String conditions(long userId, String kind, LocalDate start, LocalDate end, Long accountId, List<Object> args) {
        args.add(userId);
        var sql = new StringBuilder("WHERE t.user_id=? AND t.is_deleted=FALSE AND a.is_deleted=FALSE");
        if (kind != null) { sql.append(" AND t.kind=?"); args.add(kind); }
        if (start != null) { sql.append(" AND t.transaction_date>=?"); args.add(start); }
        if (end != null) { sql.append(" AND t.transaction_date<=?"); args.add(end); }
        if (accountId != null) { sql.append(" AND t.account_id=?"); args.add(accountId); }
        return sql.toString();
    }
    /** 按当前用户及筛选条件分页查询记录，日期和主键倒序保证稳定排序。 */
    public List<LedgerEntry> entries(long userId, String kind, LocalDate start, LocalDate end, Long accountId, int page, int size) {
        var args = new ArrayList<Object>();
        var where = conditions(userId, kind, start, end, accountId, args);
        args.add(size); args.add((long) page * size);
        return jdbc.query(SELECT_ENTRY + where + " ORDER BY t.transaction_date DESC,t.id DESC LIMIT ? OFFSET ?", ENTRY,
                args.toArray());
    }
    /** 计算当前用户筛选后的总条数，用于分页，不混合不同币种计算金额。 */
    public long count(long userId, String kind, LocalDate start, LocalDate end, Long accountId) {
        var args = new ArrayList<Object>();
        var where = conditions(userId, kind, start, end, accountId, args);
        return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transaction t JOIN ledger_account a "
                + "ON a.id=t.account_id AND a.user_id=t.user_id " + where, Long.class, args.toArray());
    }
    /** 比较完整业务字段判断重复，金额按数据库数值比较，不将不同日期或备注的流水合并。 */
    public boolean duplicate(long userId, LedgerModels.EntryInput input) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transaction WHERE user_id=? AND is_deleted=FALSE "
                + "AND account_id=? AND kind=? AND amount=? AND transaction_date=? AND category=? AND merchant=? AND note=? AND CAST(tags_json AS BINARY(4096))=CAST(? AS BINARY(4096))",
                Long.class,userId,input.accountId(),input.kind(),new BigDecimal(input.amount()),input.date(),input.category(),
                input.merchant()==null ? "" : input.merchant().strip(),input.note()==null ? "" : input.note().strip(),tags.encodeTags(tags.normalizeTags(input.tags())))>0;
    }
    /** 提取真实标签列表，去重排序后供报表筛选。 */
    public List<String> tags(long userId) { return jdbc.queryForList("SELECT tags_json FROM ledger_transaction WHERE user_id=? AND is_deleted=FALSE GROUP BY tags_json,CAST(tags_json AS BINARY(4096))",String.class,userId).stream().flatMap(s -> tags.decodeTags(s).stream()).distinct().sorted().toList(); }
    /** 按币种、日期及可选支出分类汇总真实个人支出，不混入收入、删除记录或他人的记录。 */
    public BigDecimal spent(long userId,String currency,LocalDate start,LocalDate end,String expenseCategory) {
        if (end.isBefore(start)) return BigDecimal.ZERO;
        var args=new ArrayList<Object>(List.of(userId,currency,start,end));
        var category=expenseCategory==null ? "" : " AND t.category=?"; if(!category.isEmpty())args.add(expenseCategory);
        return jdbc.queryForObject("SELECT COALESCE(SUM(t.amount),0) FROM ledger_transaction t JOIN ledger_account a ON a.id=t.account_id AND a.user_id=t.user_id WHERE t.user_id=? AND t.currency=? AND t.transaction_date BETWEEN ? AND ? AND t.kind='EXPENSE' AND t.is_deleted=FALSE AND a.is_deleted=FALSE"+category,BigDecimal.class,args.toArray());
    }
}
