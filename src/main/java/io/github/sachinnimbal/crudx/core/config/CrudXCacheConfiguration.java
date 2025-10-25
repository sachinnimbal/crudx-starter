package io.github.sachinnimbal.crudx.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.TcpIpConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.github.sachinnimbal.crudx.core.cache.CrudXCacheManager;
import io.github.sachinnimbal.crudx.core.cache.impl.CaffeineCacheManager;
import io.github.sachinnimbal.crudx.core.cache.impl.CustomCacheManagerAdapter;
import io.github.sachinnimbal.crudx.core.cache.impl.HazelcastCacheManager;
import io.github.sachinnimbal.crudx.core.cache.impl.RedisCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Slf4j
@Configuration
@EnableConfigurationProperties(CrudXCacheProperties.class)
@ConditionalOnProperty(prefix = "crudx.cache", name = "enabled", havingValue = "true")
public class CrudXCacheConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "crudx.cache", name = "provider", havingValue = "caffeine", matchIfMissing = true)
    public CrudXCacheManager caffeineCacheManager(CrudXCacheProperties properties) {
        return new CaffeineCacheManager(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "crudx.cache", name = "provider", havingValue = "redis")
    @ConditionalOnClass(RedisTemplate.class)
    public CrudXCacheManager redisCacheManager(CrudXCacheProperties properties, ObjectMapper objectMapper) {
        RedisTemplate<String, Object> redisTemplate = createRedisTemplate(properties, objectMapper);
        return new RedisCacheManager(redisTemplate, properties, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "crudx.cache", name = "provider", havingValue = "hazelcast")
    @ConditionalOnClass(HazelcastInstance.class)
    public CrudXCacheManager hazelcastCacheManager(CrudXCacheProperties properties) {
        HazelcastInstance hazelcast = createHazelcastInstance(properties);
        return new HazelcastCacheManager(hazelcast, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "crudx.cache", name = "provider", havingValue = "custom")
    public CrudXCacheManager customCacheManager(ApplicationContext context,
                                                CrudXCacheProperties properties) {
        return new CustomCacheManagerAdapter(context, properties);
    }

    private RedisTemplate<String, Object> createRedisTemplate(CrudXCacheProperties properties,
                                                              ObjectMapper objectMapper) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(properties.getRedis().getHost());
        config.setPort(properties.getRedis().getPort());
        config.setDatabase(properties.getRedis().getDatabase());

        if (properties.getRedis().getPassword() != null) {
            config.setPassword(properties.getRedis().getPassword());
        }

        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer(objectMapper));
        template.afterPropertiesSet();

        log.info("Redis connection established: {}:{}",
                properties.getRedis().getHost(), properties.getRedis().getPort());

        return template;
    }

    private HazelcastInstance createHazelcastInstance(CrudXCacheProperties properties) {
        Config config = new Config();
        config.setClusterName(properties.getHazelcast().getClusterName());

        NetworkConfig network = config.getNetworkConfig();
        JoinConfig join = network.getJoin();
        join.getMulticastConfig().setEnabled(false);

        TcpIpConfig tcpIpConfig = join.getTcpIpConfig();
        tcpIpConfig.setEnabled(true);
        tcpIpConfig.setMembers(properties.getHazelcast().getMembers());

        return Hazelcast.newHazelcastInstance(config);
    }
}
