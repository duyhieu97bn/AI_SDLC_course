package vn.camhub.platform.tenancy;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TenancyConfiguration {

    /** Bọc DataSource chính; Flyway dùng kết nối riêng (tài khoản owner) nên không bị ảnh hưởng. */
    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof TenantAwareDataSource)
                        && "dataSource".equals(beanName)) {
                    return new TenantAwareDataSource(ds);
                }
                return bean;
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "camhub.tenancy.header-resolver-enabled", havingValue = "true")
    HeaderOrganizationResolver headerOrganizationResolver() {
        return new HeaderOrganizationResolver();
    }

    @Bean
    FilterRegistrationBean<TenantFilter> tenantFilter(List<OrganizationResolver> resolvers) {
        FilterRegistrationBean<TenantFilter> reg = new FilterRegistrationBean<>(new TenantFilter(resolvers));
        // Sau chuỗi filter của Spring Security để đọc Tổ chức từ access token đã xác thực
        reg.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 10);
        return reg;
    }
}
