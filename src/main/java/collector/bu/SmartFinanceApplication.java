package collector.bu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SmartFinanceApplication {
    /**
     * 启动 Spring Boot 应用，初始化容器、数据库配置和 HTTP 服务。
     * @param args 启动参数，例如服务端口和启用的配置环境
     */
    public static void main(String[] args) {
        SpringApplication.run(SmartFinanceApplication.class, args);
    }
}
