package collector.bu.config.budget;

import collector.bu.service.budget.BudgetService;

import java.time.Clock;
import collector.bu.dao.BudgetDao;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** 定时按每个用户时区处理跨月结转，页面关闭也会保存站内消息。 */
@Configuration
public class BudgetScheduler {
    private final BudgetDao dao;
    private final BudgetService service;
    /** 注入需要刷新预算的用户查询及事务业务服务。 */
    public BudgetScheduler(BudgetDao dao,BudgetService service) { this.dao=dao;this.service=service; }
    /** 提供服务器真实时钟，预算计算仍转换为各用户注册的时区。 */
    @Bean
    public static Clock budgetClock() { return Clock.systemUTC(); }
    /** 每分钟逐用户刷新预算，一个用户异常不阻塞其他人；不记录消费或联系方式。 */
    @Scheduled(fixedDelayString="${budget.refresh-delay-ms:60000}",initialDelayString="${budget.refresh-delay-ms:60000}")
    public void refresh() {
        for(var owner : dao.owners())try { service.refresh(owner); }catch(RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(BudgetScheduler.class).warn("Budget refresh failed; will retry next cycle ({})",e.getClass().getSimpleName());
        }
    }
}
