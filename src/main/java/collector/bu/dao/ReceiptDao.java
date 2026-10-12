package collector.bu.dao;

import static collector.bu.model.ReceiptModels.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 收据模块 SQL，查询和写入都绑定服务端授权的账本和流水。 */
@Repository
public class ReceiptDao {
    private final JdbcTemplate jdbc;
    private static final String COLUMNS="id,transaction_id,file_name,content_type,file_size,created_at,created_by,updated_at,updated_by,is_deleted";
    private static final RowMapper<Receipt> ROW=(rs,row)->new Receipt(rs.getLong("id"),rs.getLong("transaction_id"),rs.getString("file_name"),rs.getString("content_type"),rs.getLong("file_size"),rs.getTimestamp("created_at").toLocalDateTime(),rs.getString("created_by"),rs.getTimestamp("updated_at").toLocalDateTime(),rs.getString("updated_by"),rs.getBoolean("is_deleted"));
    /** 注入数据库组件，二进制和元数据在同一事务中提交。 */
    public ReceiptDao(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** 检查流水及其账户是否在当前账本中且未删除，避免跨账本和孤立附件访问。 */
    public boolean transaction(long book,long transaction) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transaction t JOIN ledger_account a ON a.id=t.account_id AND a.book_id=t.book_id WHERE t.book_id=? AND t.id=? AND t.is_deleted=FALSE AND a.is_deleted=FALSE",Long.class,book,transaction)>0;
    }
    /** 查询有效收据元数据，不读取 BLOB，按上传顺序排列。 */
    public List<Receipt> list(long book,long transaction) { return jdbc.query("SELECT "+COLUMNS+" FROM transaction_receipt WHERE book_id=? AND transaction_id=? AND is_deleted=FALSE ORDER BY id",ROW,book,transaction); }
    /** 在已锁定账本内按文件哈希检查同一流水的重复附件。 */
    public boolean duplicate(long book,long transaction,String hash) { return jdbc.queryForObject("SELECT COUNT(*) FROM transaction_receipt WHERE book_id=? AND transaction_id=? AND file_sha256=? AND is_deleted=FALSE",Long.class,book,transaction,hash)>0; }
    /** 原子保存附件内容、校验结果和真实创建操作者，返回生成主键的元数据。 */
    public Receipt create(long book,long transaction,String name,String type,byte[] bytes,String hash,String actor) {
        var keys=new GeneratedKeyHolder();
        jdbc.update(c->{var s=c.prepareStatement("INSERT INTO transaction_receipt(book_id,transaction_id,file_name,content_type,file_size,file_sha256,file_content,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?)",new String[]{"id"});s.setLong(1,book);s.setLong(2,transaction);s.setString(3,name);s.setString(4,type);s.setLong(5,bytes.length);s.setString(6,hash);s.setBytes(7,bytes);s.setString(8,actor);s.setString(9,actor);return s;},keys);
        long id=keys.getKey().longValue();return list(book,transaction).stream().filter(r->r.id()==id).findFirst().orElseThrow();
    }
    /** 只返回当前流水内未删除的收据，不泄露其他账本附件的存在。 */
    public Optional<Content> content(long book,long transaction,long id) { return jdbc.query("SELECT "+COLUMNS+",file_content FROM transaction_receipt WHERE book_id=? AND transaction_id=? AND id=? AND is_deleted=FALSE",(rs,row)->new Content(ROW.mapRow(rs,row),rs.getBytes("file_content")),book,transaction,id).stream().findFirst(); }
    /** 逻辑删除附件并记录操作者，原始内容保留用于数据库审计但不再允许普通访问。 */
    public boolean delete(long book,long transaction,long id,String actor) { return jdbc.update("UPDATE transaction_receipt SET is_deleted=TRUE,updated_at=CURRENT_TIMESTAMP(6),updated_by=? WHERE book_id=? AND transaction_id=? AND id=? AND is_deleted=FALSE",actor,book,transaction,id)==1; }
}
