# CrudX Framework - Complete API Documentation

Comprehensive API reference for all CrudX framework features, annotations, and endpoints.

---

## Response Format

All CrudX endpoints return a standardized response format.

### Success Response Structure

```java
public class ApiResponse<T> {
    private boolean success;           // Always true for success
    private String message;            // Human-readable message
    private Integer statusCode;        // HTTP status code (200, 201, etc.)
    private String status;             // HTTP status name
    private T data;                    // Response payload
    private String executionTime;      // Request execution time
    private String timestamp;          // ISO-8601 timestamp
}
```

### Error Response Structure

```java
public class ApiResponse<T> {
    private boolean success;           // Always false for errors
    private String message;            // Error message
    private Integer statusCode;        // HTTP error code
    private String status;             // HTTP status name
    private ErrorDetails error;        // Detailed error info
    private String timestamp;          // ISO-8601 timestamp
}

public class ErrorDetails {
    private String code;               // Error code (e.g., "DUPLICATE_ENTITY")
    private String details;            // Technical details
}
```

### Execution Time Format

CrudX formats execution time for readability:
- `< 1s`: Shows milliseconds (e.g., "245 ms")
- `1s - 60s`: Shows seconds (e.g., "5.32s (5320 ms)")
- `1m - 60m`: Shows minutes (e.g., "2m 15s (135000 ms)")
- `> 1h`: Shows hours (e.g., "1h 30m 45s (5445000 ms)")

---

## Lifecycle Hooks

CrudX provides lifecycle hooks to inject custom business logic without losing auto-generated functionality.

### Available Hooks

```java
@RestController
@RequestMapping("/api/employees")
public class EmployeeController extends CrudXController<Employee, Long> {
    
    // CREATE HOOKS
    @Override
    protected void beforeCreate(Employee entity) {
        // Called before entity is saved
        // Use for: validation, data transformation, pre-processing
    }
    
    @Override
    protected void afterCreate(Employee entity) {
        // Called after entity is saved
        // Use for: notifications, logging, triggering workflows
    }
    
    // BATCH CREATE HOOKS
    @Override
    protected void beforeCreateBatch(List<Employee> entities) {
        // Called before batch create
        // Use for: bulk validation, data enrichment
    }
    
    @Override
    protected void afterCreateBatch(List<Employee> entities) {
        // Called after batch create
        // Use for: bulk notifications, analytics
    }
    
    // UPDATE HOOKS
    @Override
    protected void beforeUpdate(Long id, Map<String, Object> updates, Employee existingEntity) {
        // Called before update is applied
        // Parameters:
        //   - id: Entity ID being updated
        //   - updates: Map of fields to update
        //   - existingEntity: Current entity state
        // Use for: change validation, audit logging
    }
    
    @Override
    protected void afterUpdate(Employee updatedEntity, Employee oldEntity) {
        // Called after update is applied
        // Parameters:
        //   - updatedEntity: New entity state
        //   - oldEntity: Previous entity state
        // Use for: change tracking, notifications
    }
    
    // DELETE HOOKS
    @Override
    protected void beforeDelete(Long id, Employee entity) {
        // Called before entity is deleted
        // Use for: cascade deletes, final validation
    }
    
    @Override
    protected void afterDelete(Long id, Employee deletedEntity) {
        // Called after entity is deleted
        // Use for: cleanup, notifications
    }
    
    // BATCH DELETE HOOKS
    @Override
    protected void beforeDeleteBatch(List<Long> ids) {
        // Called before batch delete
        // Use for: bulk cleanup, validation
    }
    
    @Override
    protected void afterDeleteBatch(List<Long> ids) {
        // Called after batch delete
        // Use for: bulk notifications, analytics
    }
    
    // READ HOOKS
    @Override
    protected void afterFindById(Employee entity) {
        // Called after entity is retrieved by ID
        // Use for: access logging, lazy loading
    }
    
    @Override
    protected void afterFindAll(List<Employee> entities) {
        // Called after all entities are retrieved
        // Use for: bulk post-processing
    }
    
    @Override
    protected void afterFindPaged(PageResponse<Employee> pageResponse) {
        // Called after paginated retrieval
        // Use for: page-level processing
    }
}
```

### Practical Examples

**Example 1: Email Notifications**
```java
@RestController
@RequestMapping("/api/employees")
public class EmployeeController extends CrudXController<Employee, Long> {
    
    @Autowired
    private EmailService emailService;
    
    @Override
    protected void afterCreate(Employee entity) {
        emailService.sendWelcome(
            entity.getEmail(),
            entity.getName()
        );
    }
    
    @Override
    protected void afterUpdate(Employee updated, Employee old) {
        if (!old.getDepartment().equals(updated.getDepartment())) {
            emailService.notifyDepartmentChange(
                updated.getEmail(),
                old.getDepartment(),
                updated.getDepartment()
            );
        }
    }
}
```

**Example 2: Data Validation & Enrichment**
```java
@Override
protected void beforeCreate(Employee entity) {
    // Normalize data
    entity.setEmail(entity.getEmail().toLowerCase().trim());
    entity.setName(StringUtils.capitalize(entity.getName()));
    
    // Business rule validation
    if (entity.getSalary() != null && entity.getSalary().compareTo(BigDecimal.ZERO) < 0) {
        throw new IllegalArgumentException("Salary cannot be negative");
    }
    
    // Auto-populate fields
    entity.setStatus("ACTIVE");
    entity.setHireDate(LocalDate.now());
}
```

**Example 3: Audit Logging**
```java
@Autowired
private AuditService auditService;

@Override
protected void afterUpdate(Employee updated, Employee old) {
    Map<String, Object> changes = new HashMap<>();
    
    if (!Objects.equals(old.getSalary(), updated.getSalary())) {
        changes.put("salary", Map.of(
            "old", old.getSalary(),
            "new", updated.getSalary()
        ));
    }
    
    if (!changes.isEmpty()) {
        auditService.logChanges(
            "Employee",
            updated.getId(),
            getCurrentUser(),
            changes
        );
    }
}
```

**Example 4: Cascade Operations**
```java
@Autowired
private TaskService taskService;

@Override
protected void beforeDelete(Long id, Employee entity) {
    // Reassign tasks to manager
    List<Task> employeeTasks = taskService.findByEmployeeId(id);
    
    for (Task task : employeeTasks) {
        task.setAssigneeId(entity.getManagerId());
        taskService.update(task);
    }
}
```

---

## Service Layer API

CrudX auto-generates service implementations, but you can access them directly for custom operations.

### CrudXService Interface

```java
public interface CrudXService<T extends CrudXBaseEntity<ID>, ID extends Serializable> {
    
    // CREATE
    T create(T entity);
    BatchResult<T> createBatch(List<T> entities, boolean skipDuplicates);
    
    // READ
    T findById(ID id);
    List<T> findAll();
    List<T> findAll(Sort sort);
    Page<T> findAll(Pageable pageable);
    
    // UPDATE
    T update(ID id, Map<String, Object> updates);
    
    // DELETE
    void delete(ID id);
    BatchResult<ID> deleteBatch(List<ID> ids);
    
    // UTILITY
    long count();
    boolean existsById(ID id);
}
```

### Using Service in Custom Logic

```java
@RestController
@RequestMapping("/api/employees")
public class EmployeeController extends CrudXController<Employee, Long> {
    
    // Custom endpoint using auto-generated service
    @GetMapping("/department/{dept}")
    public ResponseEntity<List<Employee>> getByDepartment(@PathVariable String dept) {
        // Access the auto-generated service
        List<Employee> all = crudService.findAll();
        
        List<Employee> filtered = all.stream()
            .filter(e -> dept.equals(e.getDepartment()))
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(filtered);
    }
    
    // Custom bulk operation
    @PostMapping("/promote-department")
    public ResponseEntity<?> promoteDepartment(
            @RequestParam String department,
            @RequestParam BigDecimal raisePercent) {
        
        List<Employee> employees = crudService.findAll();
        
        employees.stream()
            .filter(e -> department.equals(e.getDepartment()))
            .forEach(e -> {
                BigDecimal newSalary = e.getSalary()
                    .multiply(BigDecimal.ONE.add(raisePercent.divide(BigDecimal.valueOf(100))));
                
                Map<String, Object> updates = Map.of("salary", newSalary);
                crudService.update(e.getId(), updates);
            });
        
        return ResponseEntity.ok(
            Map.of("message", "Department promoted successfully")
        );
    }
}
```

### BatchResult Class

```java
public class BatchResult<T> {
    private List<T> createdEntities;      // Successfully processed entities
    private int skippedCount;             // Number of skipped items
    private List<String> skippedReasons;  // Reasons for skipping
    
    public int getTotalProcessed() {
        return createdEntities.size() + skippedCount;
    }
    
    public boolean hasSkipped() {
        return skippedCount > 0;
    }
}
```

---

## Configuration Properties

### Database Configuration

```yaml
# MySQL Configuration
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:password}
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
  
  jpa:
    hibernate:
      ddl-auto: update  # Options: create, create-drop, update, validate, none
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQL8Dialect
        format_sql: false
        use_sql_comments: false
        jdbc:
          batch_size: 20
        order_inserts: true
        order_updates: true
    show-sql: false

# PostgreSQL Configuration
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mydb
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:password}
    driver-class-name: org.postgresql.Driver
  
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect

# MongoDB Configuration
spring:
  data:
    mongodb:
      uri: mongodb://${DB_USERNAME:}:${DB_PASSWORD:}@localhost:27017/mydb
      # Or individual properties:
      host: localhost
      port: 27017
      database: mydb
      username: ${DB_USERNAME:}
      password: ${DB_PASSWORD:}
```

### CrudX Specific Configuration

```yaml
# Database Auto-Creation
crudx:
  database:
    auto-create: true  # Auto-create database if not exists (default: true)

# Custom Package Scanning (if needed)
crudx:
  jpa:
    repository:
      packages: com.myapp.repositories  # Custom repository packages
    entity:
      packages: com.myapp.entities      # Custom entity packages
  
  mongo:
    repository:
      packages: com.myapp.mongo.repositories

# Performance Monitoring
crudx:
  performance:
    enabled: false                      # Enable performance monitoring
    dashboard-enabled: true             # Enable web dashboard
    dashboard-path: /crudx/performance  # Dashboard URL path
    track-memory: false                 # Track memory usage (adds overhead)
    max-stored-metrics: 1000            # Max metrics in memory
    retention-minutes: 60               # Metrics retention time
```

### Hibernate DDL Modes

| Mode | Behavior | Use Case |
|------|----------|----------|
| `create` | Drop and recreate schema on startup | Never use in production |
| `create-drop` | Create on startup, drop on shutdown | Testing only |
| `update` | Update schema automatically | Development, careful use in production |
| `validate` | Validate schema, no changes | Production (recommended) |
| `none` | No schema management | Manual migration control |

---

## Performance Monitoring API

When performance monitoring is enabled, CrudX exposes these endpoints.

### Enable Performance Monitoring

```yaml
crudx:
  performance:
    enabled: true
```

### Dashboard

**URL:** `http://localhost:8080/crudx/performance/dashboard`

**Features:**
- Real-time request monitoring
- Response time distribution charts
- Success/failure rate tracking
- Memory usage analytics
- Per-endpoint statistics
- Top slow endpoints
- Error tracking

### Performance API Endpoints

All endpoints under `/crudx/performance` (configurable via `dashboard-path`):

#### 1. Get Summary

**Endpoint:** `GET /crudx/performance/summary`

**Response:**
```json
{
  "success": true,
  "data": {
    "totalRequests": 1523,
    "successfulRequests": 1498,
    "failedRequests": 25,
    "successRate": 98.36,
    "avgExecutionTimeMs": 45.3,
    "minExecutionTimeMs": 5,
    "maxExecutionTimeMs": 1250,
    "avgMemoryKb": 128,
    "minMemoryKb": 32,
    "maxMemoryKb": 512,
    "monitoringStartTime": "2025-01-15T10:00:00",
    "lastRequestTime": "2025-01-15T15:30:00",
    "topSlowEndpoints": {
      "POST /api/employees/batch": 1250,
      "GET /api/reports/complex": 890
    },
    "topErrorEndpoints": {
      "POST /api/employees": 15,
      "PATCH /api/employees/999": 10
    },
    "topMemoryEndpoints": {
      "GET /api/employees": 512,
      "POST /api/employees/batch": 450
    }
  }
}
```

#### 2. Get All Metrics

**Endpoint:** `GET /crudx/performance/metrics`

**Response:**
```json
{
  "success": true,
  "data": [
    {
      "endpoint": "/api/employees",
      "method": "GET",
      "entityName": "Employee",
      "executionTimeMs": 45,
      "memoryUsedKb": 128,
      "memoryUsedMb": 0.125,
      "memoryUsedFormatted": "128 KB",
      "timestamp": "2025-01-15T15:30:00",
      "success": true,
      "errorType": null
    }
  ]
}
```

#### 3. Get Metrics by Endpoint

**Endpoint:** `GET /crudx/performance/metrics/endpoint?endpoint=/api/employees`

**Response:** Same as above, filtered by endpoint

#### 4. Clear All Metrics

**Endpoint:** `DELETE /crudx/performance/metrics`

**Response:**
```json
{
  "success": true,
  "message": "All metrics cleared"
}
```

#### 5. Get Configuration

**Endpoint:** `GET /crudx/performance/config`

**Response:**
```json
{
  "success": true,
  "data": {
    "enabled": true,
    "dashboardEnabled": true,
    "dashboardPath": "/crudx/performance",
    "trackMemory": false,
    "maxStoredMetrics": 1000,
    "retentionMinutes": 60
  }
}
```

#### 6. Health Check

**Endpoint:** `GET /crudx/performance/health`

**Response:**
```json
{
  "success": true,
  "data": "OK",
  "message": "Performance monitoring is active"
}
```

### Memory Tracking Notes

- **Accuracy:** Uses thread-local allocation measurement (`ThreadMXBean`)
- **First Request:** Shows higher values due to JVM warmup
- **Typical Range:** 50-500KB per request
- **Overhead:** ~2-3ms per request when enabled
- **JVM Support:** Requires `ThreadMXBean.isThreadAllocatedMemorySupported()`

---

## Error Handling

CrudX provides comprehensive error handling with meaningful messages.

### Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `ENTITY_NOT_FOUND` | 404 | Entity not found with given ID |
| `DUPLICATE_ENTITY` | 409 | Unique constraint violation |
| `VALIDATION_ERROR` | 400 | Bean validation failed |
| `INVALID_ARGUMENT` | 400 | Invalid request parameter |
| `DATA_INTEGRITY_VIOLATION` | 409 | Database constraint violation |
| `DATABASE_ERROR` | 500 | Database operation failed |
| `SQL_ERROR` | 500 | SQL query error |
| `INVALID_REQUEST_BODY` | 400 | Malformed JSON or invalid format |
| `TYPE_MISMATCH` | 400 | Parameter type mismatch |
| `MISSING_PARAMETER` | 400 | Required parameter missing |
| `METHOD_NOT_ALLOWED` | 405 | HTTP method not supported |
| `NULL_POINTER_ERROR` | 500 | Unexpected null value |
| `RUNTIME_ERROR` | 500 | Unexpected runtime error |
| `INTERNAL_SERVER_ERROR` | 500 | Unknown server error |

### Error Response Examples

**Validation Error:**
```json
{
  "success": false,
  "message": "Validation failed",
  "statusCode": 400,
  "status": "BAD_REQUEST",
  "data": {
    "name": "Name is required",
    "email": "Invalid email format"
  },
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Field validation failed"
  },
  "timestamp": "2025-01-15T16:00:00"
}
```

**Data Integrity Error:**
```json
{
  "success": false,
  "message": "A record with this information already exists",
  "statusCode": 409,
  "status": "CONFLICT",
  "error": {
    "code": "DATA_INTEGRITY_VIOLATION",
    "details": "Duplicate entry 'crudx@example.com' for key 'uk_email'"
  },
  "timestamp": "2025-01-15T16:05:00"
}
```

### Custom Error Handling

```java
@RestController
@RequestMapping("/api/employees")
public class EmployeeController extends CrudXController<Employee, Long> {
    
    @Override
    protected void beforeCreate(Employee entity) {
        // Custom validation with specific error
        if (entity.getSalary().compareTo(BigDecimal.valueOf(1000000)) > 0) {
            throw new IllegalArgumentException(
                "Salary cannot exceed $1,000,000"
            );
        }
    }
    
    // Custom exception handler
    @ExceptionHandler(CustomBusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            CustomBusinessException ex) {
        
        return ResponseEntity
            .status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ApiResponse.error(
                ex.getMessage(),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "BUSINESS_RULE_VIOLATION",
                ex.getDetails()
            ));
    }
}
```

---

## Advanced Features

### 1. Multi-Tenancy Support

```java
@Entity
@Table(name = "employees")
@org.hibernate.annotations.Where(clause = "tenant_id = current_tenant()")
public class Employee extends CrudXMySQLEntity<Long> {
    
    @Column(name = "tenant_id")
    private String tenantId;
    
    // Other fields...
}

@RestController
@RequestMapping("/api/employees")
public class EmployeeController extends CrudXController<Employee, Long> {
    
    @Override
    protected void beforeCreate(Employee entity) {
        // Auto-populate tenant from security context
        String tenantId = SecurityContextHolder.getContext()
            .getAuthentication()
            .getPrincipal()
            .getTenantId();
        
        entity.setTenantId(tenantId);
    }
}
```

### 2. Soft Delete Support

```java
@Entity
public class Employee extends CrudXMySQLEntity<Long> {
    
    private String name;
    private String email;
    
    @Column(name = "deleted")
    private boolean deleted = false;
    
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}

@RestController
@RequestMapping("/api/employees")
public class EmployeeController extends CrudXController<Employee, Long> {
    
    @Override
    protected void beforeDelete(Long id, Employee entity) {
        // Soft delete instead of hard delete
        entity.setDeleted(true);
        entity.setDeletedAt(LocalDateTime.now());
        
        Map<String, Object> updates = Map.of(
            "deleted", true,
            "deletedAt", LocalDateTime.now()
        );
        
        crudService.update(id, updates);
        
        // Prevent actual deletion
        throw new PreventDeletionException("Entity soft-deleted");
    }
}
```

### 3. Event Publishing

```java
@RestController
@RequestMapping("/api/employees")
public class EmployeeController extends CrudXController<Employee, Long> {
    
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    
    @Override
    protected void afterCreate(Employee entity) {
        eventPublisher.publishEvent(
            new EmployeeCreatedEvent(this, entity)
        );
    }
    
    @Override
    protected void afterUpdate(Employee updated, Employee old) {
        eventPublisher.publishEvent(
            new EmployeeUpdatedEvent(this, updated, old)
        );
    }
}

// Event listener
@Component
public class EmployeeEventListener {
    
    @EventListener
    public void handleEmployeeCreated(EmployeeCreatedEvent event) {
        // Send to message queue, update cache, etc.
    }
}
```

### 4. Caching Integration

```java
@RestController
@RequestMapping("/api/employees")
@CacheConfig(cacheNames = "employees")
public class EmployeeController extends CrudXController<Employee, Long> {
    
    @Override
    @Cacheable(key = "#id")
    protected void afterFindById(Employee entity) {
        // Entity cached after retrieval
    }
    
    @Override
    @CacheEvict(key = "#entity.id")
    protected void afterUpdate(Employee updated, Employee old) {
        // Cache invalidated after update
    }
    
    @Override
    @CacheEvict(key = "#id")
    protected void afterDelete(Long id, Employee deleted) {
        // Cache invalidated after delete
    }
}
```

### 5. Custom Queries

```java
@RestController
@RequestMapping("/api/employees")
public class EmployeeController extends CrudXController<Employee, Long> {
    
    @GetMapping("/search")
    public ResponseEntity<List<Employee>> search(
            @RequestParam String name,
            @RequestParam(required = false) String department) {
        
        // Use service for basic operations
        List<Employee> all = crudService.findAll();
        
        // Custom filtering
        Predicate<Employee> filter = e -> 
            e.getName().toLowerCase().contains(name.toLowerCase()) &&
            (department == null || department.equals(e.getDepartment()));
        
        List<Employee> results = all.stream()
            .filter(filter)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(results);
    }
    
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        List<Employee> all = crudService.findAll();
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", all.size());
        stats.put("avgSalary", all.stream()
            .map(Employee::getSalary)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(all.size()), 2, RoundingMode.HALF_UP)
        );
        stats.put("byDepartment", all.stream()
            .collect(Collectors.groupingBy(
                Employee::getDepartment,
                Collectors.counting()
            ))
        );
        
        return ResponseEntity.ok(stats);
    }
}
```

---

## Best Practices

### 1. Entity Design
- Always add `@CrudXUniqueConstraint` for unique fields
- Use appropriate ID types (Long for SQL, String for MongoDB)
- Add JPA indexes for frequently queried fields
- Keep entities focused and normalized

### 2. Controller Design
- Use lifecycle hooks for cross-cutting concerns
- Don't override auto-generated endpoints
- Add custom endpoints for specific business logic
- Use `@PreAuthorize` for security

### 3. Performance
- Always use pagination for large datasets
- Add database indexes for sort/filter fields
- Use batch operations for bulk processing
- Enable performance monitoring in development

### 4. Error Handling
- Throw specific exceptions in lifecycle hooks
- Add custom `@ExceptionHandler` methods
- Provide meaningful error messages
- Log errors appropriately

### 5. Security
- Validate all inputs in `beforeCreate` / `beforeUpdate`
- Check authorization in lifecycle hooks
- Never trust client-provided IDs
- Sanitize user input

---

**For more examples and guides, visit the [CrudX Wiki](https://github.com/sachinnimbal/crudx-examples/wiki)** Table of Contents

1. [Core Annotations](#core-annotations)
2. [Base Entity Classes](#base-entity-classes)
3. [Auto-Generated REST Endpoints](#auto-generated-rest-endpoints)
4. [Response Format](#response-format)
5. [Lifecycle Hooks](#lifecycle-hooks)
6. [Service Layer API](#service-layer-api)
7. [Configuration Properties](#configuration-properties)
8. [Performance Monitoring API](#performance-monitoring-api)
9. [Error Handling](#error-handling)
10. [Advanced Features](#advanced-features)

---

## Core Annotations

### @CrudX

**Purpose:** Enables CrudX framework functionality in your Spring Boot application.

**Target:** Class (Application main class)

**Usage:**
```java
@SpringBootApplication
@CrudX
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

**What it does:**
- Automatically scans for CrudX controllers
- Registers service beans for detected entities
- Configures database connections
- Enables auto-repository generation
- Initializes performance monitoring (if enabled)

---

### @CrudXUniqueConstraint

**Purpose:** Defines a unique constraint on one or more fields.

**Target:** Type (Class)

**Attributes:**

| Attribute | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `fields` | String[] | Yes | - | Field names that must be unique |
| `name` | String | No | "" | Constraint name (optional) |
| `message` | String | No | "Duplicate entry found for unique constraint" | Error message |

**Usage:**

**Single Field Constraint:**
```java
@Entity
@CrudXUniqueConstraint(
    fields = {"email"},
    message = "Email address already exists"
)
public class User extends CrudXMySQLEntity<Long> {
    private String email;
}
```

**Composite Constraint:**
```java
@Entity
@CrudXUniqueConstraint(
    fields = {"firstName", "lastName", "department"},
    name = "uk_employee_name_dept",
    message = "Employee name must be unique within department"
)
public class Employee extends CrudXMySQLEntity<Long> {
    private String firstName;
    private String lastName;
    private String department;
}
```

**Validation Behavior:**
- Checks uniqueness before INSERT operations
- Checks uniqueness before UPDATE operations (excludes current record)
- Throws `DuplicateEntityException` if constraint violated
- In batch operations with `skipDuplicates=true`, skips violating records

---

### @CrudXUniqueConstraints

**Purpose:** Container annotation for multiple `@CrudXUniqueConstraint` annotations.

**Target:** Type (Class)

**Usage:**
```java
@Entity
@CrudXUniqueConstraints({
    @CrudXUniqueConstraint(
        fields = {"email"},
        message = "Email already exists"
    ),
    @CrudXUniqueConstraint(
        fields = {"username"},
        message = "Username already taken"
    ),
    @CrudXUniqueConstraint(
        fields = {"ssn"},
        message = "SSN must be unique"
    )
})
public class Employee extends CrudXMySQLEntity<Long> {
    private String email;
    private String username;
    private String ssn;
}
```

---

## Base Entity Classes

All entities must extend one of these base classes based on your database type.

### CrudXMySQLEntity<ID>

**Purpose:** Base class for MySQL entities with auto-increment ID generation.

**ID Strategy:** `GenerationType.IDENTITY`

**Features:**
- Auto-generated ID on insert
- Automatic audit trail (`createdAt`, `updatedAt`, `createdBy`, `updatedBy`)
- JPA lifecycle callbacks (`@PrePersist`, `@PreUpdate`)

**Usage:**
```java
@Entity
@Table(name = "employees")
public class Employee extends CrudXMySQLEntity<Long> {
    
    @Column(nullable = false)
    private String name;
    
    @Column(unique = true)
    private String email;
    
    private String department;
    
    private BigDecimal salary;
    
    // Getters and setters
}
```

**Inherited Fields:**
```java
private ID id;  // Auto-generated
private CrudXAudit audit;  // Contains:
    // - LocalDateTime createdAt
    // - String createdBy
    // - LocalDateTime updatedAt
    // - String updatedBy
```

---

### CrudXPostgreSQLEntity<ID>

**Purpose:** Base class for PostgreSQL entities with sequence-based ID generation.

**ID Strategy:** `GenerationType.SEQUENCE`

**Features:**
- Sequence-based ID generation
- Automatic audit trail
- JPA lifecycle callbacks

**Usage:**
```java
@Entity
@Table(name = "products")
public class Product extends CrudXPostgreSQLEntity<Long> {
    
    private String name;
    private String sku;
    private BigDecimal price;
    private Integer stock;
    
    // Getters and setters
}
```

**Note:** PostgreSQL sequences can be customized:
```java
@Entity
@SequenceGenerator(
    name = "product_seq",
    sequenceName = "product_id_seq",
    allocationSize = 1
)
public class Product extends CrudXPostgreSQLEntity<Long> {
    // ...
}
```

---

### CrudXMongoEntity<ID>

**Purpose:** Base class for MongoDB documents.

**ID Type:** Typically `String` (MongoDB ObjectId) or `Long`

**Features:**
- Flexible schema
- Automatic audit trail
- Manual lifecycle methods (`onCreate`, `onUpdate`)

**Usage:**
```java
@Document(collection = "users")
public class User extends CrudXMongoEntity<String> {
    
    private String username;
    private String email;
    private List<String> roles;
    private Map<String, Object> metadata;
    
    // Getters and setters
}
```

**Inherited Fields:**
```java
@Id
private ID id;  // MongoDB ObjectId (String) or custom ID
private CrudXAudit audit;  // Audit trail
```

**Lifecycle Methods:**
```java
// Called automatically before save
public void onCreate() {
    super.onCreate();  // Sets createdAt, updatedAt
}

// Called automatically before update
public void onUpdate() {
    super.onUpdate();  // Updates updatedAt
}
```

---

### CrudXAudit (Embedded)

**Purpose:** Provides automatic audit trail for all entities.

**Fields:**
```java
public class CrudXAudit {
    @CreatedDate
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    
    private String createdBy;
    
    @LastModifiedDate
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
    
    private String updatedBy;
}
```

**Automatic Population:**
- `createdAt`: Set on entity creation
- `updatedAt`: Updated on every modification
- `createdBy` / `updatedBy`: Can be set manually or via Spring Security integration

**Custom Population:**
```java
@Override
protected void beforeCreate(Employee entity) {
    entity.getAudit().setCreatedBy(getCurrentUser());
}

@Override
protected void beforeUpdate(Long id, Map<String, Object> updates, Employee existing) {
    existing.getAudit().setUpdatedBy(getCurrentUser());
}
```

---

## Auto-Generated REST Endpoints

All controllers extending `CrudXController` automatically expose these endpoints.

### 1. Create Single Entity

**Endpoint:** `POST /api/{resource}`

**Request Body:**
```json
{
  "name": "Crudx Starter",
  "email": "crudx@example.com",
  "department": "Engineering"
}
```

**Response:** `201 Created`
```json
{
  "success": true,
  "message": "Entity created successfully",
  "statusCode": 201,
  "status": "CREATED",
  "data": {
    "id": 1,
    "name": "Crudx Starter",
    "email": "crudx@example.com",
    "department": "Engineering",
    "audit": {
      "createdAt": "2025-01-15 10:30:00",
      "updatedAt": "2025-01-15 10:30:00"
    }
  },
  "executionTime": "45 ms",
  "timestamp": "2025-01-15T10:30:00"
}
```

**Error Response:** `409 Conflict` (Duplicate)
```json
{
  "success": false,
  "message": "Email address already exists",
  "statusCode": 409,
  "status": "CONFLICT",
  "error": {
    "code": "DUPLICATE_ENTITY",
    "details": "Email address already exists"
  },
  "timestamp": "2025-01-15T10:30:00"
}
```

---

### 2. Create Batch

**Endpoint:** `POST /api/{resource}/batch`

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `skipDuplicates` | boolean | No | true | Skip duplicate entries instead of failing |

**Request Body:**
```json
[
  {
    "name": "Crudx Starter",
    "email": "crudx@example.com"
  },
  {
    "name": "Jane Smith",
    "email": "jane@example.com"
  },
  {
    "name": "Bob Wilson",
    "email": "crudx@example.com"  // Duplicate
  }
]
```

**Response:** `201 Created`
```json
{
  "success": true,
  "message": "Batch creation completed: 2 created, 1 skipped",
  "statusCode": 201,
  "status": "CREATED",
  "data": {
    "createdEntities": [
      {
        "id": 1,
        "name": "Crudx Starter",
        "email": "crudx@example.com",
        "audit": {
          "createdAt": "2025-01-15 10:30:00",
          "updatedAt": "2025-01-15 10:30:00"
        }
      },
      {
        "id": 2,
        "name": "Jane Smith",
        "email": "jane@example.com",
        "audit": {
          "createdAt": "2025-01-15 10:30:01",
          "updatedAt": "2025-01-15 10:30:01"
        }
      }
    ],
    "skippedCount": 1,
    "skippedReasons": [
      "Entity at index 2 skipped - Email address already exists"
    ],
    "totalProcessed": 3
  },
  "executionTime": "127 ms",
  "timestamp": "2025-01-15T10:30:01"
}
```

**Behavior:**
- **With `skipDuplicates=true`**: Continues processing, skips duplicates
- **With `skipDuplicates=false`**: Stops on first duplicate, rolls back transaction
- **Batch Limit**: Max 1000 records per request (auto-limited for safety)
- **Sub-batching**: Processes in chunks of 100 for memory efficiency

---

### 3. Get by ID

**Endpoint:** `GET /api/{resource}/{id}`

**Response:** `200 OK`
```json
{
  "success": true,
  "message": "Entity retrieved successfully",
  "statusCode": 200,
  "status": "OK",
  "data": {
    "id": 1,
    "name": "Crudx Starter",
    "email": "crudx@example.com",
    "department": "Engineering",
    "audit": {
      "createdAt": "2025-01-15 10:30:00",
      "updatedAt": "2025-01-15 10:30:00"
    }
  },
  "executionTime": "12 ms",
  "timestamp": "2025-01-15T10:35:00"
}
```

**Error Response:** `404 Not Found`
```json
{
  "success": false,
  "message": "Employee not found with id: 999",
  "statusCode": 404,
  "status": "NOT_FOUND",
  "error": {
    "code": "ENTITY_NOT_FOUND",
    "details": "Employee not found with id: 999"
  },
  "timestamp": "2025-01-15T10:35:00"
}
```

---

### 4. Get All (Auto-Paginated)

**Endpoint:** `GET /api/{resource}`

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `sortBy` | String | No | - | Field name to sort by |
| `sortDirection` | String | No | ASC | Sort direction (ASC/DESC) |

**Example:** `GET /api/employees?sortBy=name&sortDirection=ASC`

**Response (Small Dataset < 1000):** `200 OK`
```json
{
  "success": true,
  "message": "Retrieved 150 entities",
  "statusCode": 200,
  "status": "OK",
  "data": [
    {
      "id": 1,
      "name": "Crudx Core",
      "email": "core@example.com"
    },
    {
      "id": 2,
      "name": "Sachin Nimbal",
      "email": "sachin@example.com"
    }
    // ... more entities
  ],
  "executionTime": "68 ms",
  "timestamp": "2025-01-15T10:40:00"
}
```

**Response (Large Dataset > 1000):** `200 OK` with Auto-Pagination
```json
{
  "success": true,
  "message": "Large dataset detected (5000 total records). Returning first 1000 records. Use /paged endpoint with page parameter for more data.",
  "statusCode": 200,
  "status": "OK",
  "data": {
    "content": [ /* first 1000 entities */ ],
    "currentPage": 0,
    "pageSize": 1000,
    "totalElements": 5000,
    "totalPages": 5,
    "first": true,
    "last": false,
    "empty": false
  },
  "executionTime": "245 ms",
  "timestamp": "2025-01-15T10:40:00"
}
```

**Auto-Pagination Behavior:**
- If total records > 1000: Automatically returns paginated response
- Returns first 1000 records with pagination metadata
- Suggests using `/paged` endpoint for more control
- Uses cursor-based streaming for datasets > 5000 (memory-optimized)

---

### 5. Get Paginated

**Endpoint:** `GET /api/{resource}/paged`

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `page` | int | No | 0 | Page number (0-indexed) |
| `size` | int | No | 10 | Items per page (max: 100,000) |
| `sortBy` | String | No | - | Field to sort by |
| `sortDirection` | String | No | ASC | Sort direction (ASC/DESC) |

**Example:** `GET /api/employees/paged?page=0&size=20&sortBy=name&sortDirection=ASC`

**Response:** `200 OK`
```json
{
  "success": true,
  "message": "Retrieved page 0 with 20 elements (total: 150)",
  "statusCode": 200,
  "status": "OK",
  "data": {
    "content": [
      {
        "id": 1,
        "name": "Crudx Core",
        "email": "core@example.com"
      }
      // ... 19 more entities
    ],
    "currentPage": 0,
    "pageSize": 20,
    "totalElements": 150,
    "totalPages": 8,
    "first": true,
    "last": false,
    "empty": false
  },
  "executionTime": "32 ms",
  "timestamp": "2025-01-15T10:45:00"
}
```

---

### 6. Count

**Endpoint:** `GET /api/{resource}/count`

**Response:** `200 OK`
```json
{
  "success": true,
  "message": "Total count: 150",
  "statusCode": 200,
  "status": "OK",
  "data": 150,
  "executionTime": "8 ms",
  "timestamp": "2025-01-15T10:50:00"
}
```

---

### 7. Check Existence

**Endpoint:** `GET /api/{resource}/exists/{id}`

**Response:** `200 OK`
```json
{
  "success": true,
  "message": "Entity exists",
  "statusCode": 200,
  "status": "OK",
  "data": true,
  "executionTime": "6 ms",
  "timestamp": "2025-01-15T10:55:00"
}
```

---

### 8. Partial Update

**Endpoint:** `PATCH /api/{resource}/{id}`

**Request Body:** (Only fields to update)
```json
{
  "department": "Management",
  "salary": 95000
}
```

**Response:** `200 OK`
```json
{
  "success": true,
  "message": "Entity updated successfully",
  "statusCode": 200,
  "status": "OK",
  "data": {
    "id": 1,
    "name": "Crudx Starter",
    "email": "crudx@example.com",
    "department": "Management",
    "salary": 95000,
    "audit": {
      "createdAt": "2025-01-15 10:30:00",
      "updatedAt": "2025-01-15 11:00:00"
    }
  },
  "executionTime": "28 ms",
  "timestamp": "2025-01-15T11:00:00"
}
```

**Notes:**
- Only sends fields that need updating
- `id` field cannot be updated
- Automatically updates `audit.updatedAt`
- Validates unique constraints

---

### 9. Delete by ID

**Endpoint:** `DELETE /api/{resource}/{id}`

**Response:** `200 OK`
```json
{
  "success": true,
  "message": "Entity deleted successfully",
  "statusCode": 200,
  "status": "OK",
  "data": null,
  "executionTime": "18 ms",
  "timestamp": "2025-01-15T11:05:00"
}
```

**Error Response:** `404 Not Found`
```json
{
  "success": false,
  "message": "Employee not found with id: 999",
  "statusCode": 404,
  "status": "NOT_FOUND",
  "error": {
    "code": "ENTITY_NOT_FOUND",
    "details": "Employee not found with id: 999"
  },
  "timestamp": "2025-01-15T11:05:00"
}
```

---

### 10. Delete Batch

**Endpoint:** `DELETE /api/{resource}/batch`

**Request Body:**
```json
[1, 2, 3, 999, 1000]
```

**Response:** `200 OK`
```json
{
  "success": true,
  "message": "Batch deletion completed: 3 deleted, 2 skipped (not found)",
  "statusCode": 200,
  "status": "OK",
  "data": {
    "createdEntities": [1, 2, 3],  // Successfully deleted IDs
    "skippedCount": 2,
    "skippedReasons": [
      "ID 999 not found",
      "ID 1000 not found"
    ],
    "totalProcessed": 5
  },
  "executionTime": "42 ms",
  "timestamp": "2025-01-15T11:10:00"
}
```

**Behavior:**
- Checks existence before deletion
- Skips non-existent IDs
- Returns list of deleted IDs and skipped IDs
- Batch limit: 1000 IDs per request

---

### 11. Force Delete Batch

**Endpoint:** `DELETE /api/{resource}/batch/force`

**Request Body:**
```json
[1, 2, 3, 999, 1000]
```

**Response:** `200 OK`
```json
{
  "success": true,
  "message": "5 IDs processed for deletion (existence not verified)",
  "statusCode": 200,
  "status": "OK",
  "data": null,
  "executionTime": "25 ms",
  "timestamp": "2025-01-15T11:15:00"
}
```

**Difference from Regular Batch Delete:**
- **Skips existence check** (faster for large batches)
- Processes all IDs without validation
- Use when you're certain IDs exist
- Still respects batch size limit (1000)

---

##