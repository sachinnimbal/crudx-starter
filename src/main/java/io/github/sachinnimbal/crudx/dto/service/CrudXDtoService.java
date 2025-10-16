package io.github.sachinnimbal.crudx.dto.service;

import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.dto.mapper.CrudXDtoMapper;
import io.github.sachinnimbal.crudx.dto.registry.CrudXDtoRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Standalone DTO Service - Use DTOs without extending CrudXController
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>
 * // Example 1: In your custom controller
 * {@literal @}RestController
 * {@literal @}RequestMapping("/api/users")
 * public class UserController {
 *
 *     {@literal @}Autowired
 *     private CrudXDtoService dtoService;
 *
 *     {@literal @}Autowired
 *     private UserRepository userRepository;
 *
 *     {@literal @}PostMapping
 *     public ResponseEntity<?> createUser({@literal @}RequestBody UserCreateRequest dto) {
 *         // Convert DTO → Entity
 *         User user = dtoService.toEntity(dto, User.class);
 *
 *         // Save entity
 *         user = userRepository.save(user);
 *
 *         // Convert Entity → Response DTO
 *         UserResponse response = dtoService.toDto(user, User.class, UserResponse.class);
 *
 *         return ResponseEntity.ok(response);
 *     }
 *
 *     {@literal @}GetMapping("/{id}")
 *     public ResponseEntity<?> getUser({@literal @}PathVariable Long id) {
 *         User user = userRepository.findById(id).orElseThrow();
 *
 *         // Auto-detect response DTO for GET_BY_ID operation
 *         Object response = dtoService.toResponseDto(user, User.class, OperationType.GET_BY_ID);
 *
 *         return ResponseEntity.ok(response);
 *     }
 * }
 *
 * // Example 2: In your service layer
 * {@literal @}Service
 * public class OrderService {
 *
 *     {@literal @}Autowired
 *     private CrudXDtoService dtoService;
 *
 *     {@literal @}Autowired
 *     private OrderRepository orderRepository;
 *
 *     public OrderResponse createOrder(OrderCreateRequest request) {
 *         // DTO → Entity
 *         Order order = dtoService.toEntity(request, Order.class);
 *
 *         // Business logic
 *         order.calculateTotal();
 *         order = orderRepository.save(order);
 *
 *         // Entity → Response DTO
 *         return dtoService.toDto(order, Order.class, OrderResponse.class);
 *     }
 *
 *     public List<OrderSummary> getAllOrders() {
 *         List<Order> orders = orderRepository.findAll();
 *
 *         // Batch convert with auto-detection
 *         return dtoService.toDtoList(orders, Order.class, OrderSummary.class);
 *     }
 * }
 *
 * // Example 3: Manual mapping (if no DTO configured)
 * {@literal @}RestController
 * public class ProductController {
 *
 *     {@literal @}Autowired
 *     private CrudXDtoService dtoService;
 *
 *     {@literal @}PostMapping("/products")
 *     public ResponseEntity<?> create({@literal @}RequestBody Object dto) {
 *         // Works even if RequestDto not configured - uses entity class
 *         Product product = dtoService.toEntity(dto, Product.class);
 *         // ... save and return
 *     }
 * }
 * </pre>
 */
@Slf4j
@Service
public class CrudXDtoService {

    @Autowired(required = false)
    private CrudXDtoRegistry dtoRegistry;

    @Autowired(required = false)
    private CrudXDtoMapper dtoMapper;

    /**
     * Convert DTO → Entity (Request mapping)
     * Works with or without @CrudXRequestDto annotation
     *
     * @param dto         Input DTO or Map
     * @param entityClass Target entity class
     * @return Mapped entity
     */
    public <E> E toEntity(Object dto, Class<E> entityClass) {
        if (dto == null) {
            return null;
        }

        if (dtoMapper == null) {
            log.warn("CrudXDtoMapper not available. DTO feature disabled.");
            return convertDirectly(dto, entityClass);
        }

        try {
            return dtoMapper.toEntity(dto, entityClass);
        } catch (Exception e) {
            log.error("Failed to map DTO to entity {}: {}",
                    entityClass.getSimpleName(), e.getMessage());
            throw new IllegalArgumentException(
                    "Failed to convert DTO to entity: " + e.getMessage(), e);
        }
    }

    /**
     * Convert Entity → DTO (Response mapping)
     * Explicitly specify DTO class
     *
     * @param entity      Source entity
     * @param entityClass Entity class
     * @param dtoClass    Target DTO class
     * @return Mapped DTO
     */
    public <E, D> D toDto(E entity, Class<E> entityClass, Class<D> dtoClass) {
        if (entity == null) {
            return null;
        }

        if (dtoMapper == null) {
            log.warn("CrudXDtoMapper not available. Returning entity as-is.");
            return convertDirectly(entity, dtoClass);
        }

        try {
            return dtoMapper.toDto(entity, entityClass, dtoClass);
        } catch (Exception e) {
            log.error("Failed to map entity {} to DTO {}: {}",
                    entityClass.getSimpleName(), dtoClass.getSimpleName(), e.getMessage());
            throw new IllegalArgumentException(
                    "Failed to convert entity to DTO: " + e.getMessage(), e);
        }
    }

    /**
     * Auto-detect and convert Entity → Response DTO based on operation
     * Falls back to entity if no DTO configured
     *
     * @param entity      Source entity
     * @param entityClass Entity class
     * @param operation   Operation type (GET_BY_ID, GET_ALL, etc.)
     * @return Response DTO or entity
     */
    public <E> Object toResponseDto(E entity, Class<E> entityClass, OperationType operation) {
        if (entity == null) {
            return null;
        }

        if (dtoRegistry == null || dtoMapper == null) {
            log.debug("DTO feature not available. Returning entity.");
            return entity;
        }

        // Check if response DTO is configured for this operation
        Class<?> responseDtoClass = dtoRegistry.getResponseDtoClass(entityClass, operation);

        if (responseDtoClass != null) {
            try {
                return dtoMapper.toDto(entity, entityClass, responseDtoClass);
            } catch (Exception e) {
                log.error("Failed to map to response DTO. Returning entity.", e);
            }
        }

        // No DTO configured - return entity
        log.debug("No response DTO configured for {} on {}. Returning entity.",
                operation, entityClass.getSimpleName());
        return entity;
    }

    /**
     * Batch convert DTOs → Entities
     *
     * @param dtos        List of DTOs
     * @param entityClass Target entity class
     * @return List of entities
     */
    public <E> List<E> toEntityList(List<?> dtos, Class<E> entityClass) {
        if (dtos == null || dtos.isEmpty()) {
            return List.of();
        }

        if (dtoMapper == null) {
            log.warn("CrudXDtoMapper not available. DTO feature disabled.");
            throw new IllegalStateException("DTO mapper not configured");
        }

        try {
            return dtoMapper.toEntities(dtos, entityClass);
        } catch (Exception e) {
            log.error("Failed to batch map DTOs to entities: {}", e.getMessage());
            throw new IllegalArgumentException(
                    "Failed to convert DTOs to entities: " + e.getMessage(), e);
        }
    }

    /**
     * Batch convert Entities → DTOs
     *
     * @param entities    List of entities
     * @param entityClass Entity class
     * @param dtoClass    Target DTO class
     * @return List of DTOs
     */
    @SuppressWarnings("unchecked")
    public <E, D> List<D> toDtoList(List<E> entities, Class<E> entityClass, Class<D> dtoClass) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }

        if (dtoMapper == null) {
            log.warn("CrudXDtoMapper not available. Returning entities as-is.");
            return (List<D>) entities;
        }

        try {
            return dtoMapper.toDtos(entities, entityClass, dtoClass);
        } catch (Exception e) {
            log.error("Failed to batch map entities to DTOs: {}", e.getMessage());
            throw new IllegalArgumentException(
                    "Failed to convert entities to DTOs: " + e.getMessage(), e);
        }
    }

    /**
     * Auto-detect and batch convert Entities → Response DTOs
     *
     * @param entities    List of entities
     * @param entityClass Entity class
     * @param operation   Operation type
     * @return List of response DTOs or entities
     */
    public <E> List<?> toResponseDtoList(List<E> entities, Class<E> entityClass, OperationType operation) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }

        if (dtoRegistry == null || dtoMapper == null) {
            log.debug("DTO feature not available. Returning entities.");
            return entities;
        }

        Class<?> responseDtoClass = dtoRegistry.getResponseDtoClass(entityClass, operation);

        if (responseDtoClass != null) {
            try {
                return dtoMapper.toDtos(entities, entityClass, responseDtoClass);
            } catch (Exception e) {
                log.error("Failed to batch map to response DTOs. Returning entities.", e);
            }
        }

        log.debug("No response DTO configured. Returning entities.");
        return entities;
    }

    /**
     * Update entity from DTO (partial update)
     *
     * @param dto         Update DTO
     * @param entity      Existing entity to update
     * @param entityClass Entity class
     */
    public <E> void updateEntity(Object dto, E entity, Class<E> entityClass) {
        if (dto == null || entity == null) {
            return;
        }

        if (dtoMapper == null) {
            log.warn("CrudXDtoMapper not available. Update not possible.");
            return;
        }

        try {
            dtoMapper.updateEntity(dto, entity, entityClass);
        } catch (Exception e) {
            log.error("Failed to update entity from DTO: {}", e.getMessage());
            throw new IllegalArgumentException(
                    "Failed to update entity: " + e.getMessage(), e);
        }
    }

    /**
     * Check if Request DTO is configured for operation
     *
     * @param entityClass Entity class
     * @param operation   Operation type
     * @return true if DTO configured
     */
    public boolean hasRequestDto(Class<?> entityClass, OperationType operation) {
        return dtoRegistry != null && dtoRegistry.hasRequestDto(entityClass, operation);
    }

    /**
     * Check if Response DTO is configured for operation
     *
     * @param entityClass Entity class
     * @param operation   Operation type
     * @return true if DTO configured
     */
    public boolean hasResponseDto(Class<?> entityClass, OperationType operation) {
        return dtoRegistry != null && dtoRegistry.hasResponseDto(entityClass, operation);
    }

    /**
     * Get Request DTO class for operation (if configured)
     *
     * @param entityClass Entity class
     * @param operation   Operation type
     * @return DTO class or null
     */
    public Class<?> getRequestDtoClass(Class<?> entityClass, OperationType operation) {
        return dtoRegistry != null ? dtoRegistry.getRequestDtoClass(entityClass, operation) : null;
    }

    /**
     * Get Response DTO class for operation (if configured)
     *
     * @param entityClass Entity class
     * @param operation   Operation type
     * @return DTO class or null
     */
    public Class<?> getResponseDtoClass(Class<?> entityClass, OperationType operation) {
        return dtoRegistry != null ? dtoRegistry.getResponseDtoClass(entityClass, operation) : null;
    }

    // ===== FALLBACK METHODS =====

    @SuppressWarnings("unchecked")
    private <T> T convertDirectly(Object source, Class<T> targetClass) {
        if (targetClass.isInstance(source)) {
            return (T) source;
        }

        log.warn("Cannot convert {} to {}. Returning null.",
                source.getClass().getSimpleName(), targetClass.getSimpleName());
        return null;
    }
}
