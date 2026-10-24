package it.ristomanager.cloud.tenant;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TenantConfig {

    /** Wraps the application's DataSource so every connection is tagged with the current restaurant. */
    @Bean
    static BeanPostProcessor tenantDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && "dataSource".equals(beanName) && !(bean instanceof TenantDataSource)) {
                    return new TenantDataSource(ds);
                }
                return bean;
            }
        };
    }
}
