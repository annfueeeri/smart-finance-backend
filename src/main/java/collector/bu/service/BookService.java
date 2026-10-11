package collector.bu.service;

import collector.bu.model.BookContext;

/** 所有记账模块共享的服务端账本授权入口。 */
public interface BookService {
    /** 验证登录账号、当前家庭成员资格并锁定账本，返回可信归属和记账偏好。 */
    BookContext resolve(String username);
}
