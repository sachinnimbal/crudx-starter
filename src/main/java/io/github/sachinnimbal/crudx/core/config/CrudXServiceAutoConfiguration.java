package io.github.sachinnimbal.crudx.core.config;

import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import io.github.sachinnimbal.crudx.core.model.CrudXMongoEntity;
import io.github.sachinnimbal.crudx.core.model.CrudXMySQLEntity;
import io.github.sachinnimbal.crudx.core.model.CrudXPostgreSQLEntity;
import io.github.sachinnimbal.crudx.service.impl.CrudXMongoService;
import io.github.sachinnimbal.crudx.service.impl.CrudXSQLService;
import io.github.sachinnimbal.crudx.web.CrudXController;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;


/**
 * @author Sachin Nimbal
 * @version 1.0.0
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Slf4j
@Component
public class CrudXServiceAutoConfiguration implements BeanDefinitionRegistryPostProcessor, ApplicationContextAware {

    protected ApplicationContext applicationContext;
    private final Set<String> processedEntities = new HashSet<>();
    private static final Set<Class<?>> discoveredSQLEntities = new HashSet<>();

    @Autowired
    private Environment environment;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        log.info("🔍 Auto-discovering CrudX controllers and generating services...");

        String[] beanNames = registry.getBeanDefinitionNames();
        int servicesCreated = 0;

        for (String beanName : beanNames) {
            BeanDefinition beanDefinition = registry.getBeanDefinition(beanName);
            String beanClassName = beanDefinition.getBeanClassName();

            if (beanClassName == null) continue;

            try {
                Class<?> beanClass = Class.forName(beanClassName);

                if (CrudXController.class.isAssignableFrom(beanClass) &&
                        !CrudXController.class.equals(beanClass)) {

                    EntityInfo entityInfo = extractEntityInfo(beanClass);

                    if (entityInfo != null) {
                        String entityKey = entityInfo.entityClass.getName();

                        if (!processedEntities.contains(entityKey)) {
                            if (entityInfo.databaseType == DatabaseType.MYSQL ||
                                    entityInfo.databaseType == DatabaseType.POSTGRESQL) {
                                discoveredSQLEntities.add(entityInfo.entityClass);
                            }

                            registerServiceBean(registry, entityInfo);
                            processedEntities.add(entityKey);
                            servicesCreated++;

                            log.info("  ✓ {} -> {} ({})",
                                    entityInfo.entityClass.getSimpleName(),
                                    entityInfo.serviceBeanName,
                                    entityInfo.databaseType);
                        }
                    }
                }
            } catch (ClassNotFoundException e) {
                log.debug("Could not load class: {}", beanClassName);
            } catch (Exception e) {
                log.warn("Error processing bean: {}", beanName, e);
            }
        }

        if (servicesCreated > 0) {
            log.info("✓ Auto-generated {} service bean(s) successfully", servicesCreated);
        }

        if (!discoveredSQLEntities.isEmpty()) {
            log.info("✓ Discovered {} SQL entity/entities for JPA registration", discoveredSQLEntities.size());
        }
    }

    private EntityInfo extractEntityInfo(Class<?> controllerClass) {
        Type genericSuperclass = controllerClass.getGenericSuperclass();

        if (genericSuperclass instanceof ParameterizedType paramType) {
            Type[] typeArgs = paramType.getActualTypeArguments();

            if (typeArgs.length >= 2 && typeArgs[0] instanceof Class<?> entityClass &&
                    typeArgs[1] instanceof Class<?> idClass) {

                DatabaseType detectedType = detectDatabaseType(entityClass);

                if (detectedType != null) {
                    return new EntityInfo(entityClass, idClass, detectedType);
                } else {
                    log.warn("Entity {} does not extend any CrudX database-specific entity class",
                            entityClass.getSimpleName());
                }
            }
        }

        return null;
    }

    private DatabaseType detectDatabaseType(Class<?> entityClass) {
        if (CrudXMySQLEntity.class.isAssignableFrom(entityClass)) {
            return DatabaseType.MYSQL;
        } else if (CrudXPostgreSQLEntity.class.isAssignableFrom(entityClass)) {
            return DatabaseType.POSTGRESQL;
        } else if (CrudXMongoEntity.class.isAssignableFrom(entityClass)) {
            return DatabaseType.MONGODB;
        }
        return null;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // Not needed
    }

    private void registerServiceBean(BeanDefinitionRegistry registry, EntityInfo entityInfo) {
        if (registry.containsBeanDefinition(entityInfo.serviceBeanName)) {
            log.debug("Service already exists: {}", entityInfo.serviceBeanName);
            return;
        }

        Class<?> serviceImplClass;

        switch (entityInfo.databaseType) {
            case MYSQL:
            case POSTGRESQL:
                serviceImplClass = DynamicSQLService.class;
                break;
            case MONGODB:
                serviceImplClass = DynamicMongoService.class;
                break;
            default:
                log.error("Unsupported database type: {}", entityInfo.databaseType);
                return;
        }

        BeanDefinitionBuilder builder = BeanDefinitionBuilder
                .genericBeanDefinition(serviceImplClass)
                .addConstructorArgValue(entityInfo.entityClass)
                .setScope(BeanDefinition.SCOPE_SINGLETON)
                .setLazyInit(false);

        registry.registerBeanDefinition(entityInfo.serviceBeanName, builder.getBeanDefinition());
    }

    @Transactional
    public static class DynamicSQLService<T extends CrudXBaseEntity<ID>, ID extends Serializable>
            extends CrudXSQLService<T, ID> {

        @Autowired(required = false)
        protected EntityManager entityManager;

        public DynamicSQLService(Class<T> entityClass) {
            this.entityClass = entityClass;
        }

        @Override
        @PostConstruct
        protected void init() {
            if (entityManager == null) {
                throw new IllegalStateException(
                        "EntityManager not available. Please add 'spring-boot-starter-data-jpa' " +
                                "dependency and appropriate database driver to your project."
                );
            }
            if (entityClass != null) {
                log.debug("SQL Entity class pre-configured: {}", entityClass.getSimpleName());
            } else {
                super.init();
            }
        }
    }

    public static class DynamicMongoService<T extends CrudXMongoEntity<ID>, ID extends Serializable>
            extends CrudXMongoService<T, ID> {

        @Autowired(required = false)
        protected MongoTemplate mongoTemplate;

        public DynamicMongoService(Class<T> entityClass) {
            this.entityClass = entityClass;
        }

        @Override
        @PostConstruct
        protected void init() {
            if (mongoTemplate == null) {
                throw new IllegalStateException(
                        "MongoTemplate not available. Please add 'spring-boot-starter-data-mongodb' " +
                                "dependency and configure MongoDB connection in application.yml"
                );
            }
            if (entityClass != null) {
                log.debug("MongoDB Entity class pre-configured: {}", entityClass.getSimpleName());
            } else {
                super.init();
            }
        }
    }

    // CRITICAL: Only register these beans if JPA classes are available
    @Bean
    @ConditionalOnClass(name = "jakarta.persistence.EntityManager")
    public org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer hibernatePropertiesCustomizer() {
        return (hibernateProperties) -> {
            if (!discoveredSQLEntities.isEmpty()) {
                log.info("╔════════════════════════════════════════════════════════════════╗");
                log.info("║     Registering SQL Entities with Hibernate                    ║");
                log.info("╠════════════════════════════════════════════════════════════════╣");

                for (Class<?> entityClass : discoveredSQLEntities) {
                    log.info("║  ✓ {}", String.format("%-58s", entityClass.getName()) + "║");
                }

                log.info("╚════════════════════════════════════════════════════════════════╝");
            }
        };
    }

    @Bean
    @ConditionalOnClass(name = {
            "org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean",
            "org.springframework.orm.jpa.persistenceunit.MutablePersistenceUnitInfo"
    })
    public static org.springframework.beans.factory.config.BeanPostProcessor entityManagerFactoryBeanPostProcessor() {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof LocalContainerEntityManagerFactoryBean) {
                    LocalContainerEntityManagerFactoryBean emfBean = (LocalContainerEntityManagerFactoryBean) bean;

                    if (!discoveredSQLEntities.isEmpty()) {
                        Class<?>[] managedTypes = discoveredSQLEntities.toArray(new Class<?>[0]);

                        try {
                            java.lang.reflect.Method setManagedTypesMethod =
                                    LocalContainerEntityManagerFactoryBean.class.getMethod("setManagedTypes", Class[].class);
                            setManagedTypesMethod.invoke(emfBean, (Object) managedTypes);
                            log.debug("Successfully set {} managed types via setManagedTypes", managedTypes.length);
                        } catch (NoSuchMethodException e) {
                            emfBean.setPersistenceUnitPostProcessors(
                                    persistenceUnitInfo -> {
                                        for (Class<?> entityClass : discoveredSQLEntities) {
                                            persistenceUnitInfo.addManagedClassName(entityClass.getName());
                                        }
                                    }
                            );
                            log.debug("Successfully added {} managed class names via PersistenceUnitPostProcessor",
                                    managedTypes.length);
                        } catch (Exception e) {
                            log.warn("Could not set managed types, trying alternative approach", e);
                        }
                    }
                }
                return bean;
            }
        };
    }

    private enum DatabaseType {
        MYSQL, POSTGRESQL, MONGODB
    }

    private static class EntityInfo {
        final Class<?> entityClass;
        final Class<?> idClass;
        final DatabaseType databaseType;
        final String serviceBeanName;

        EntityInfo(Class<?> entityClass, Class<?> idClass, DatabaseType databaseType) {
            this.entityClass = entityClass;
            this.idClass = idClass;
            this.databaseType = databaseType;
            this.serviceBeanName = generateServiceBeanName(entityClass);
        }

        private static String generateServiceBeanName(Class<?> entityClass) {
            String simpleName = entityClass.getSimpleName();
            return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1) + "Service";
        }
    }

    public static Set<Class<?>> getDiscoveredSQLEntities() {
        return new HashSet<>(discoveredSQLEntities);
    }
}