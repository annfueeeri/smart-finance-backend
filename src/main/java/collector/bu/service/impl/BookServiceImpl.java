package collector.bu.service.impl;

import collector.bu.dao.*;
import collector.bu.model.BookContext;
import collector.bu.service.BookService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 从数据库确定个人或家庭账本，系统管理员也不能越过家庭成员权限。 */
@Service
@Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class BookServiceImpl implements BookService {
    private final UserDao users;
    private final BookDao books;
    private final FamilyDao families;
    /** 注入账号验证、账本和家庭成员访问组件。 */
    public BookServiceImpl(UserDao users,BookDao books,FamilyDao families) { this.users=users;this.books=books;this.families=families; }
    /** 在账号锁保护下验证选择，成员被移除后的下次请求不能继续读取家庭数据。 */
    @Override
    public BookContext resolve(String username) {
        var user=users.findByUsername(username).filter(u->u.enabled() && !u.deleted()).orElseThrow(()->new AccessDeniedException("Active account required"));
        users.lockUser(user.id());
        user=users.findByUsername(username).filter(u->u.enabled() && !u.deleted()).orElseThrow(()->new AccessDeniedException("Active account required"));
        books.ensurePersonal(user);
        if(books.scope(user.id()).equals("FAMILY")) {
            var family=families.membership(user.id());
            if(family==null)throw new AccessDeniedException("Family membership required");
            books.lock(-family);return checked(books.family(family),"FAMILY:"+family);
        }
        books.lock(user.id());return checked(new BookContext(user.id(),user.currency(),user.timezone(),user.monthlyBudget()),"PERSONAL");
    }
    /** 核对浏览器正在显示的账本，阻止另一个标签页切换后误写入不同账本。 */
    private BookContext checked(BookContext context,String scope) {
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
            var expected=servlet.getRequest().getHeader("X-BOOK-SCOPE");
            if(expected!=null && !expected.equals(scope))throw new collector.bu.exception.FamilyException("BOOK_CHANGED");
        }
        return context;
    }

}
