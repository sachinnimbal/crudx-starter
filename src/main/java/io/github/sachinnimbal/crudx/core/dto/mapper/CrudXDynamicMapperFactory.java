package io.github.sachinnimbal.crudx.core.dto.mapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;

import static io.github.sachinnimbal.crudx.core.enums.CrudXOperation.CREATE;
import static io.github.sachinnimbal.crudx.core.enums.CrudXOperation.GET_ID;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "crudx.dto", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CrudXDynamicMapperFactory implements BeanFactoryPostProcessor,
        ApplicationContextAware, Ordered {

    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private CrudXMapperGenerator generator;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE; // Run very early
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (!(beanFactory instanceof BeanDefinitionRegistry)) {
            log.warn("Cannot register mapper beans - bean factory is not a registry");
            return;
        }

        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;

        log.debug("Preparing to create dynamic mapper beans...");

        // Register a placeholder - actual mapper will be created when CrudXMapperRegistry finds DTOs
        // This ensures mapper beans exist when requested
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void createMapperBean(Class<?> entityClass, BeanDefinitionRegistry registry,
                                 CrudXMapperRegistry mapperRegistry) {
        String beanName = getMapperBeanName(entityClass);

        if (registry.containsBeanDefinition(beanName)) {
            log.debug("Mapper bean already exists: {}", beanName);
            return;
        }

        // Get generator from context if not injected
        CrudXMapperGenerator gen = this.generator;
        if (gen == null) {
            try {
                gen = applicationContext.getBean(CrudXMapperGenerator.class);
            } catch (Exception e) {
                log.error("CrudXMapperGenerator not available - cannot create mapper for {}",
                        entityClass.getSimpleName());
                return;
            }
        }

        final CrudXMapperGenerator finalGen = gen;

        // Create a factory method that returns the mapper instance
        BeanDefinitionBuilder builder = BeanDefinitionBuilder
                .genericBeanDefinition(CrudXMapper.class, () ->
                        new RuntimeGeneratedMapper(entityClass, finalGen, mapperRegistry)
                );

        registry.registerBeanDefinition(beanName, builder.getBeanDefinition());

        log.info("✓ Created dynamic mapper bean: {} for entity {}",
                beanName, entityClass.getSimpleName());
    }

    public String getMapperBeanName(Class<?> entityClass) {
        String name = entityClass.getSimpleName();
        return Character.toLowerCase(name.charAt(0)) + name.substring(1) + "MapperCrudX";
    }

    private static class RuntimeGeneratedMapper<E, R, S> implements CrudXMapper<E, R, S> {

        private final Class<E> entityClass;
        private final CrudXMapperGenerator generator;
        private final CrudXMapperRegistry registry;

        @SuppressWarnings("unchecked")
        public RuntimeGeneratedMapper(Class<?> entityClass,
                                      CrudXMapperGenerator generator,
                                      CrudXMapperRegistry registry) {
            this.entityClass = (Class<E>) entityClass;
            this.generator = generator;
            this.registry = registry;
        }

        @Override
        public E toEntity(R request) {
            return generator.toEntity(request, entityClass);
        }

        @Override
        public void updateEntity(R request, E entity) {
            generator.updateEntity(request, entity);
        }

        @Override
        public S toResponse(E entity) {
            Class<S> responseClass = getResponseClass();
            return generator.toResponse(entity, responseClass);
        }

        @Override
        public List<S> toResponseList(List<E> entities) {
            Class<S> responseClass = getResponseClass();
            return generator.toResponseList(entities, responseClass);
        }

        @Override
        public Class<E> getEntityClass() {
            return entityClass;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<R> getRequestClass() {
            return (Class<R>) registry.getRequestDTO(entityClass,
                            CREATE)
                    .orElse(Object.class);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<S> getResponseClass() {
            return (Class<S>) registry.getResponseDTO(entityClass,
                            GET_ID)
                    .orElse(Object.class);
        }
    }
}
