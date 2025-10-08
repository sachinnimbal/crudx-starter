/*
 * Copyright 2025 Sachin Nimbal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Slf4j
@Component
public class CrudXServiceAutoConfiguration implements BeanDefinitionRegistryPostProcessor, ApplicationContextAware {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String WHITE = "\u001B[37m";
    private static final String YELLOW = "\u001B[33m";

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
        log.info("Auto-discovering CrudX controllers and generating services...");

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

                            log.info(" {} -> {} ({})",
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
            log.info("Auto-generated {} service bean(s) successfully", servicesCreated);
        }

        if (!discoveredSQLEntities.isEmpty()) {
            log.info("Discovered {} SQL entity/entities for JPA registration", discoveredSQLEntities.size());
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

        @Autowired
        private Environment environment;

        public DynamicSQLService(Class<T> entityClass) {
            this.entityClass = entityClass;
        }

        @Override
        @PostConstruct
        protected void init() {
            // Check if SQL is actually configured
            String sqlUrl = environment.getProperty("spring.datasource.url");
            boolean isSqlConfigured = sqlUrl != null && !sqlUrl.trim().isEmpty();

            // Only fail if SQL is configured but EntityManager is not available
            if (isSqlConfigured && entityManager == null) {
                String errorMsg = buildEntityManagerErrorMessage();
                System.out.println(errorMsg);
                System.exit(1);
            }

            // If SQL is not configured, just skip initialization silently
            if (!isSqlConfigured) {
                log.debug("SQL not configured - skipping SQL service initialization for {}",
                        entityClass != null ? entityClass.getSimpleName() : "unknown");
                return;
            }

            if (entityClass != null) {
                log.debug("SQL Entity class pre-configured: {}", entityClass.getSimpleName());
            } else {
                super.init();
            }
        }

        private String buildEntityManagerErrorMessage() {
            return "\n" +
                    RED + "===============================================\n" + RESET +
                    RED + BOLD + "   ENTITYMANAGER NOT AVAILABLE\n" + RESET +
                    RED + "===============================================\n" + RESET +
                    RED + "SQL database is configured but EntityManager\n" + RESET +
                    RED + "bean is not available!\n" + RESET +
                    "\n" +
                    YELLOW + "This usually means:\n" + RESET +
                    "  1. spring-boot-starter-data-jpa is missing\n" +
                    "  2. Database driver is not in classpath\n" +
                    "  3. Database connection failed\n" +
                    "  4. Database server is not running\n" +
                    "\n" +
                    CYAN + "Required dependencies:\n" + RESET +
                    GREEN + "  → spring-boot-starter-data-jpa\n" + RESET +
                    GREEN + "  → Database driver (MySQL/PostgreSQL)\n" + RESET +
                    "\n" +
                    CYAN + "Verify your configuration:\n" + RESET +
                    "  spring.datasource.url=" +
                    environment.getProperty("spring.datasource.url") + "\n" +
                    "  spring.datasource.driver-class-name=" +
                    environment.getProperty("spring.datasource.driver-class-name") + "\n" +
                    "\n" +
                    YELLOW + "Check database server is running!\n" + RESET +
                    RED + "===============================================\n" + RESET +
                    RED + BOLD + "Application startup aborted.\n" + RESET;
        }
    }

    public static class DynamicMongoService<T extends CrudXMongoEntity<ID>, ID extends Serializable>
            extends CrudXMongoService<T, ID> {

        @Autowired(required = false)
        protected MongoTemplate mongoTemplate;

        @Autowired
        private Environment environment;

        public DynamicMongoService(Class<T> entityClass) {
            this.entityClass = entityClass;
        }

        @Override
        @PostConstruct
        protected void init() {
            // Check if MongoDB is actually configured
            String mongoUri = environment.getProperty("spring.data.mongodb.uri");
            boolean isMongoConfigured = mongoUri != null && !mongoUri.trim().isEmpty();

            // Only fail if MongoDB is configured but MongoTemplate is not available
            if (isMongoConfigured && mongoTemplate == null) {
                String errorMsg = buildMongoTemplateErrorMessage();
                System.out.println(errorMsg);
                System.exit(1);
            }

            // If MongoDB is not configured, just skip initialization silently
            if (!isMongoConfigured) {
                log.debug("MongoDB not configured - skipping MongoDB service initialization for {}",
                        entityClass != null ? entityClass.getSimpleName() : "unknown");
                return;
            }

            if (entityClass != null) {
                log.debug("MongoDB Entity class pre-configured: {}", entityClass.getSimpleName());
            } else {
                super.init();
            }
        }

        private String buildMongoTemplateErrorMessage() {
            return "\n" +
                    RED + "===============================================\n" + RESET +
                    RED + BOLD + "   MONGOTEMPLATE NOT AVAILABLE\n" + RESET +
                    RED + "===============================================\n" + RESET +
                    RED + "MongoDB is configured but MongoTemplate\n" + RESET +
                    RED + "bean is not available!\n" + RESET +
                    "\n" +
                    YELLOW + "This usually means:\n" + RESET +
                    "  1. spring-boot-starter-data-mongodb is missing\n" +
                    "  2. MongoDB connection failed\n" +
                    "  3. MongoDB server is not running\n" +
                    "  4. MongoDB URI is incorrect\n" +
                    "\n" +
                    CYAN + "Required dependency:\n" + RESET +
                    GREEN + "  → spring-boot-starter-data-mongodb\n" + RESET +
                    "\n" +
                    CYAN + "Verify your configuration:\n" + RESET +
                    "  spring.data.mongodb.uri=" +
                    environment.getProperty("spring.data.mongodb.uri") + "\n" +
                    "\n" +
                    YELLOW + "Check MongoDB server is running at the configured URI!\n" + RESET +
                    "\n" +
                    CYAN + "Alternative format:\n" + RESET +
                    "  spring.data.mongodb.host=localhost\n" +
                    "  spring.data.mongodb.port=27017\n" +
                    "  spring.data.mongodb.database=dbname\n" +
                    RED + "===============================================\n" + RESET +
                    RED + BOLD + "Application startup aborted.\n" + RESET;
        }
    }

    @Bean
    @ConditionalOnClass(name = "jakarta.persistence.EntityManager")
    public org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer hibernatePropertiesCustomizer() {
        return (hibernateProperties) -> {
            if (!discoveredSQLEntities.isEmpty()) {
                log.info(CYAN + "================================================" + RESET);
                log.info(BOLD + WHITE + "  Registering SQL Entities with Hibernate" + RESET);
                log.info(CYAN + "================================================" + RESET);

                for (Class<?> entityClass : discoveredSQLEntities) {
                    log.info(GREEN + "  [OK] " + RESET + entityClass.getName());
                }

                log.info(CYAN + "================================================" + RESET);
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