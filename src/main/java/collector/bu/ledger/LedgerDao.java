package collector.bu.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 所有收支 SQL 均在此执行，以服务端用户 ID 限定范围并使用参数绑定。 */
@Repository
public class LedgerDao {
    private final JdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private static final RowMapper<LedgerAccount> ACCOUNT = (rs, row) -> new LedgerAccount(rs.getLong("id"),
            rs.getString("name"), rs.getString("currency"), rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getString("created_by"), rs.getTimestamp("updated_at").toLocalDateTime(), rs.getString("updated_by"),
            rs.getBoolean("is_deleted"),rs.getString("account_type"),rs.getBigDecimal("opening_balance"),rs.getDate("opening_date").toLocalDate());
    private final RowMapper<LedgerEntry> ENTRY = (rs, row) -> new LedgerEntry(rs.getLong("id"),
            rs.getString("kind"), rs.getBigDecimal("amount"), rs.getString("currency"),
            rs.getDate("transaction_date").toLocalDate(), rs.getLong("account_id"), rs.getString("account_name"),
            rs.getString("category"), rs.getString("merchant"), rs.getString("note"),
            rs.getTimestamp("created_at").toLocalDateTime(), rs.getString("created_by"),
            rs.getTimestamp("updated_at").toLocalDateTime(), rs.getString("updated_by"), rs.getBoolean("is_deleted"),decodeTags(rs.getString("tags_json")));
    private static final String SELECT_ENTRY = "SELECT t.*, a.name AS account_name FROM ledger_transaction t "
            + "JOIN ledger_account a ON a.id=t.account_id AND a.user_id=t.user_id ";
    /** 注入 JDBC 组件，供参数化查询及写入使用。 */
    public LedgerDao(JdbcTemplate jdbc,com.fasterxml.jackson.databind.ObjectMapper mapper) { this.jdbc = jdbc; this.mapper=mapper; }
    /** 返回指定用户自己的未删除账户，按创建顺序排列。 */
    public List<LedgerAccount> accounts(long userId) {
        return jdbc.query("SELECT * FROM ledger_account WHERE user_id=? AND is_deleted=FALSE ORDER BY id", ACCOUNT, userId);
    }
    /** 查询用户拥有的未删除账户；其他用户的账户也返回空，避免泄露其存在状态。 */
    public Optional<LedgerAccount> account(long userId, long id) {
        return jdbc.query("SELECT * FROM ledger_account WHERE user_id=? AND id=? AND is_deleted=FALSE", ACCOUNT,
                userId, id).stream().findFirst();
    }
    /** 创建个人账户并记录当前操作者，使用 JDBC 生成主键以兼容 MySQL 和 H2。 */
    public LedgerAccount createAccount(long userId, String actor, String name, String currency,String type,BigDecimal openingBalance,LocalDate openingDate) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO ledger_account "
                    + "(user_id,name,currency,created_by,updated_by,account_type,opening_balance,opening_date) VALUES (?,?,?,?,?,?,?,?)", new String[]{"id"});
            statement.setLong(1, userId); statement.setString(2, name); statement.setString(3, currency);
            statement.setString(4, actor); statement.setString(5, actor); statement.setString(6,type);statement.setBigDecimal(7,openingBalance);statement.setObject(8,openingDate);return statement;
        }, keys);
        return account(userId, keys.getKey().longValue()).orElseThrow();
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
            statement.setString(9, note); statement.setString(10, actor); statement.setString(11, actor);statement.setString(12,encodeTags(tags));return statement;
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
    /** 锁定当前用户行，使同一用户并发导入的重复检测和入库按顺序执行。 */
    public void lockUser(long userId) { jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", Long.class,userId); }
    /** 比较完整业务字段判断重复，金额按数据库数值比较，不将不同日期或备注的流水合并。 */
    public boolean duplicate(long userId, LedgerModels.EntryInput input) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transaction WHERE user_id=? AND is_deleted=FALSE "
                + "AND account_id=? AND kind=? AND amount=? AND transaction_date=? AND category=? AND merchant=? AND note=? AND CAST(tags_json AS BINARY(4096))=CAST(? AS BINARY(4096))",
                Long.class,userId,input.accountId(),input.kind(),new BigDecimal(input.amount()),input.date(),input.category(),
                input.merchant()==null ? "" : input.merchant().strip(),input.note()==null ? "" : input.note().strip(),encodeTags(normalizeTags(input.tags())))>0;
    }
    /** 统一排序和去重标签，保存与查重使用同一 JSON，标签顺序不改变重复判断。 */
    public List<String> normalizeTags(List<String> tags) { return tags==null ? List.of() : tags.stream().map(String::strip).distinct().sorted().toList(); }
    /** 将规范标签编码为可移植 JSON 文本，不依赖数据库专用 JSON 语法。 */
    public String encodeTags(List<String> tags) { try { return mapper.writeValueAsString(normalizeTags(tags)); } catch(java.io.IOException e) { throw new IllegalStateException("Tag encoding failed"); } }
    /** 解析数据库标签列表，损坏数据不会被静默忽略。 */
    public List<String> decodeTags(String json) { try { return mapper.readValue(json,new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}); } catch(java.io.IOException e) { throw new IllegalStateException("Tag data invalid"); } }
}
