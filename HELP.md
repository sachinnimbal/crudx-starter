# CrudX Framework - Help Guide

Complete troubleshooting guide and FAQ for CrudX framework.

---

## Table of Contents

1. [Quick Troubleshooting](#quick-troubleshooting)
2. [Setup Issues](#setup-issues)
3. [Database Configuration](#database-configuration)
4. [Runtime Errors](#runtime-errors)
5. [Performance Issues](#performance-issues)
6. [Best Practices](#best-practices)
7. [FAQ](#faq)

---

## Quick Troubleshooting

### Application Won't Start

**Symptom:** Application fails to start with database configuration errors.

**Common Causes & Solutions:**

1. **No Database Configuration Found**
```yaml
# ❌ Missing configuration
spring:
  datasource:
    # empty

# ✅ Correct configuration
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb
    username: root
    password: password
```

2. **Database Driver Not Found**
```bash
# Error: "Driver class not found: com.mysql.cj.jdbc.Driver"

# Solution: Add driver dependency
Maven:
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <version>8.3.0</version>
    <scope>runtime</scope>
</dependency>

Gradle:
runtimeOnly 'com.mysql:mysql-connector-j:8.3.0'
```

3. **Database Connection Failed**
```bash
# Error: "Connection refused" or "Communications link failure"

# Solutions:
# 1. Check if database server is running
systemctl status mysql  # Linux
brew services list      # macOS

# 2. Verify connection details
# 3. Check firewall settings
# 4. Ensure database exists (or enable auto-create)
crudx:
  database:
    auto-create: true
```

---

## Setup Issues

### Issue: CrudX Annotation Not Working

**Symptom:** `@CrudX` annotation has no effect; services not auto-generated.

**Solution:**

1. **Ensure correct annotation placement:**
```java
// ✅ Correct
@SpringBootApplication
@CrudX
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}

// ❌ Wrong - not on main class
@Configuration
@CrudX
public class SomeConfig { }
```

2. **Check dependency is correctly added:**
```xml
<!-- Maven -->
<dependency>
    <groupId>io.github.sachinnimbal</groupId>
    <artifactId>crudx-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

3. **Verify Spring Boot version compatibility:**
```xml
<!-- CrudX requires Spring Boot 3.x -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.0</version> <!-- Or higher -->
</parent>
```

### Issue: Service Bean Not Found

**Symptom:**
```
IllegalStateException: Service bean not found: employeeService
```

**Root Cause:** Entity doesn't extend correct CrudX base class.

**Solution:**

```java
// ❌ Wrong - generic base class
public class Employee extends CrudXBaseEntity<Long> {
    // This won't work!
}

// ✅ Correct - database-specific base class
// For MySQL:
public class Employee extends CrudXMySQLEntity<Long> {
    private String name;
    private String email;
}

// For PostgreSQL:
public class Employee extends CrudXPostgreSQLEntity<Long> {
    private String name;
    private String email;
}

// For MongoDB:
public class Employee extends CrudXMongoEntity<String> {
    private String name;
    private String email;
}
```

### Issue: Controller Endpoints Not Working

**Symptom:** 404 errors for all CRUD endpoints.

**Solution:**

1. **Ensure controller extends CrudXController:**
```java
// ❌ Wrong
@RestController
@RequestMapping("/api/employees")
public class EmployeeController {
    // Missing extends
}

// ✅ Correct
@RestController
@RequestMapping("/api/employees")
public class EmployeeController extends CrudXController<Employee, Long> {
    // Auto-generates endpoints
}
```

2. **Verify correct generic types:**
```java
// Entity ID type must match controller generic
public class Employee extends CrudXMySQLEntity<Long> { }

// Controller must use same ID type
public class EmployeeController extends CrudXController<Employee, Long> { }
//                                                                    ^^^^ Must match
```

---

## Database Configuration

### MySQL Configuration

**Basic Setup:**
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb
    username: root
    password: password
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQL8Dialect
```

**Common Issues:**

1. **Time Zone Error:**
```yaml
# Error: "The server time zone value 'IST' is unrecognized"
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb?serverTimezone=UTC
```

2. **SSL Connection Error:**
```yaml
# Error: "SSL connection error"
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb?useSSL=false&allowPublicKeyRetrieval=true
```

3. **Character Encoding:**
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb?characterEncoding=UTF-8&useUnicode=true
```

### PostgreSQL Configuration

**Basic Setup:**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mydb
    username: postgres
    password: password
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
```

**Common Issues:**

1. **Database Does Not Exist:**
```bash
# Create database manually
psql -U postgres
CREATE DATABASE mydb;
\q

# Or enable auto-create
crudx:
  database:
    auto-create: true
```

2. **Schema Issues:**
```yaml
spring:
  jpa:
    properties:
      hibernate:
        default_schema: public
```

### MongoDB Configuration

**Basic Setup:**
```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/mydb
      # Or separate properties:
      host: localhost
      port: 27017
      database: mydb
```

**Authentication:**
```yaml
spring:
  data:
    mongodb:
      uri: mongodb://username:password@localhost:27017/mydb?authSource=admin
```

**Common Issues:**

1. **Connection Timeout:**
```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/mydb?connectTimeoutMS=10000&socketTimeoutMS=10000
```

2. **Authentication Failed:**
```bash
# Check MongoDB user exists
mongo
use admin
db.auth("username", "password")
```

### Multi-Database Setup

**Using both SQL and MongoDB:**
```yaml
# application.yml
spring:
  # MySQL for transactional data
  datasource:
    url: jdbc:mysql://localhost:3306/mydb
    username: root
    password: password
  
  # MongoDB for flexible documents
  data:
    mongodb:
      uri: mongodb://localhost:27017/mydb
```

```java
// MySQL Entity
@Entity
public class Order extends CrudXMySQLEntity<Long> {
    private Long customerId;
    private BigDecimal amount;
}

// MongoDB Document
@Document
public class OrderHistory extends CrudXMongoEntity<String> {
    private Long orderId;
    private List<String> events;
}
```

---

## Runtime Errors

### Duplicate Entry Errors

**Symptom:**
```
DuplicateEntityException: Duplicate entry found for unique constraint
```

**Solutions:**

1. **Handle duplicates in batch operations:**
```bash
# Skip duplicates instead of failing
POST /api/employees/batch?skipDuplicates=true
```

2. **Add unique constraints to entity:**
```java
@Entity
@CrudXUniqueConstraint(
    fields = {"email"},
    message = "Email already exists"
)
public class Employee extends CrudXMySQLEntity<Long> {
    private String email;
}
```

3. **Check before creating:**
```java
@Override
protected void beforeCreate(Employee entity) {
    // Custom validation
    if (isDuplicate(entity.getEmail())) {
        throw new DuplicateEntityException("Email already exists");
    }
}
```

### Entity Not Found Errors

**Symptom:**
```
EntityNotFoundException: Employee not found with id: 123
```

**Solutions:**

1. **Verify ID exists before operations:**
```bash
# Check existence first
GET /api/employees/exists/123
```

2. **Use batch operations with skip:**
```bash
# Will skip non-existent IDs
DELETE /api/employees/batch
[123, 456, 789]
```

3. **Handle in custom logic:**
```java
@Override
protected void beforeDelete(Long id, Employee entity) {
    if (!crudService.existsById(id)) {
        // Handle gracefully
    }
}
```

### Memory Issues

**Symptom:**
```
OutOfMemoryError: Java heap space
```

**Solutions:**

1. **Use pagination for large datasets:**
```bash
# Instead of fetching all
GET /api/employees

# Use pagination
GET /api/employees/paged?page=0&size=100
```

2. **CrudX auto-handles large datasets:**
```bash
# If dataset > 1000, CrudX auto-switches to pagination
GET /api/employees
# Returns first 1000 with pagination info
```

3. **Increase heap size:**
```bash
# JVM options
java -Xms512m -Xmx2048m -jar myapp.jar
```

4. **Enable memory optimization:**
```yaml
crudx:
  performance:
    track-memory: false  # Disable if causing issues
```

### Validation Errors

**Symptom:**
```
MethodArgumentNotValidException: Validation failed
```

**Solutions:**

1. **Add validation annotations:**
```java
public class Employee extends CrudXMySQLEntity<Long> {
    @NotNull(message = "Name is required")
    @Size(min = 2, max = 100)
    private String name;
    
    @Email(message = "Invalid email format")
    private String email;
}
```

2. **Handle validation in lifecycle hooks:**
```java
@Override
protected void beforeCreate(Employee entity) {
    if (entity.getName() == null || entity.getName().trim().isEmpty()) {
        throw new IllegalArgumentException("Name cannot be empty");
    }
}
```

---

## Performance Issues

### Slow Query Performance

**Symptom:** Endpoints responding slowly.

**Diagnosis:**
```yaml
# Enable SQL logging
spring:
  jpa:
    properties:
      hibernate:
        format_sql: true
        use_sql_comments: true
    show-sql: true
```

**Solutions:**

1. **Add database indexes:**
```java
@Entity
@Table(indexes = {
    @Index(name = "idx_email", columnList = "email"),
    @Index(name = "idx_name", columnList = "name")
})
public class Employee extends CrudXMySQLEntity<Long> {
    private String name;
    private String email;
}
```

2. **Use pagination:**
```bash
# Always paginate large result sets
GET /api/employees/paged?page=0&size=50
```

3. **Optimize queries:**
```java
// Use specific fields instead of full entity
@Override
protected void afterFindById(Employee entity) {
    // Lazy load related entities only when needed
}
```

4. **Enable performance monitoring:**
```yaml
crudx:
  performance:
    enabled: true
```
Access dashboard at: `http://localhost:8080/crudx/performance/dashboard`

### Batch Operation Timeouts

**Symptom:** Batch operations timing out or failing.

**Solutions:**

1. **Reduce batch size:**
```bash
# Instead of 10,000 records at once
POST /api/employees/batch
[... 10000 items ...]

# Split into smaller batches (recommended: 100-1000)
POST /api/employees/batch
[... 500 items ...]
```

2. **CrudX has built-in limits:**
```java
// Auto-limits to 1000 records per batch for safety
// Processes in sub-batches of 100 for memory efficiency
```

3. **Use async processing for large batches:**
```java
@Async
public CompletableFuture<BatchResult<Employee>> createLargeBatch(List<Employee> employees) {
    return CompletableFuture.completedFuture(
        crudService.createBatch(employees, true)
    );
}
```

### High Memory Usage

**Symptom:** Application consuming too much memory.

**Solutions:**

1. **Avoid fetching all records:**
```bash
# ❌ Don't do this for large datasets
GET /api/employees

# ✅ Use pagination
GET /api/employees/paged?page=0&size=100
```

2. **CrudX uses cursor-based streaming for large datasets:**
```java
// Automatically switches to streaming when count > 5000
// Processes in batches of 100 to minimize memory
```

3. **Monitor with built-in dashboard:**
```yaml
crudx:
  performance:
    enabled: true
    track-memory: true
```

4. **Reduce metric retention:**
```yaml
crudx:
  performance:
    max-stored-metrics: 500  # Default: 1000
    retention-minutes: 30     # Default: 60
```

---

## Best Practices

### Entity Design

```java
// ✅ Good Practice - Multiple unique constraints
@Entity
@CrudXUniqueConstraints({
    @CrudXUniqueConstraint(
        fields = {"email"}, 
        message = "Email already exists"
    ),
    @CrudXUniqueConstraint(
        fields = {"username"}, 
        message = "Username taken"
    ),
    @CrudXUniqueConstraint(
        fields = {"name", "department"}, 
        message = "Employee name must be unique within department"
    )
})
public class Employee extends CrudXMySQLEntity<Long> {
    private String name;
    private String email;
    private String username;
    private String department;
}

// ✅ Single constraint - no wrapper needed
@Entity
@CrudXUniqueConstraint(fields = {"email"})
public class User extends CrudXMySQLEntity<Long> {
    private String email;
}

// ❌ Bad Practice - Missing constraints
@Entity
public class Employee extends CrudXMySQLEntity<Long> {
    private String email; // Should be unique but not enforced
}
```

### Controller Design

```java
// ✅ Good Practice
@RestController
@RequestMapping("/api/employees")
@CrossOrigin(origins = "http://localhost:3000")
public class EmployeeController extends CrudXController<Employee, Long> {
    
    @Autowired
    private EmailService emailService;
    
    @Override
    protected void afterCreate(Employee entity) {
        emailService.sendWelcome(entity.getEmail());
    }
    
    @Override
    protected void beforeDelete(Long id, Employee entity) {
        // Cleanup related data
        relatedDataService.cleanup(id);
    }
}

// ❌ Bad Practice - Overriding core endpoints
@RestController
@RequestMapping("/api/employees")
public class EmployeeController extends CrudXController<Employee, Long> {
    
    // ❌ Don't override core CRUD methods
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Employee entity) {
        // This breaks CrudX functionality
    }
}
```

### Database Configuration

```yaml
# ✅ Production configuration
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 30000
  jpa:
    hibernate:
      ddl-auto: validate  # Never use 'create' or 'create-drop' in production
    properties:
      hibernate:
        format_sql: false
        use_sql_comments: false
    show-sql: false

# ❌ Bad Practice - Insecure configuration
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb
    username: root
    password: password123  # Hardcoded password
  jpa:
    hibernate:
      ddl-auto: create  # Drops and recreates tables on restart!
```

### Performance Optimization

```java
// ✅ Efficient batch processing
List<Employee> employees = readFromFile(); // 10,000 records

// Split into chunks
int chunkSize = 500;
for (int i = 0; i < employees.size(); i += chunkSize) {
    List<Employee> chunk = employees.subList(
        i, 
        Math.min(i + chunkSize, employees.size())
    );
    crudService.createBatch(chunk, true);
}

// ❌ Inefficient - all at once
crudService.createBatch(employees, true); // May timeout
```

---

## FAQ

### General Questions

**Q: Can I use CrudX with existing Spring Boot projects?**

A: Yes! Simply add the `@CrudX` annotation to your main class and start creating CrudX controllers. Existing controllers and services remain unaffected.

**Q: Does CrudX work with Spring Security?**

A: Yes, CrudX controllers are standard Spring controllers and work with Spring Security. Add security annotations as usual:
```java
@RestController
@RequestMapping("/api/employees")
@PreAuthorize("hasRole('ADMIN')")
public class EmployeeController extends CrudXController<Employee, Long> {
}
```

**Q: Can I customize the generated endpoints?**

A: Yes, use lifecycle hooks to add custom logic without losing auto-generated functionality. For completely custom endpoints, add new methods to your controller.

**Q: Does CrudX support transactions?**

A: Yes, all CrudX operations are automatically transactional. For custom transaction boundaries, use `@Transactional` annotation.

**Q: Can I use CrudX with multiple databases?**

A: Yes! You can use different database types in the same application. Just extend the appropriate base class for each entity.

### Technical Questions

**Q: What happens when dataset exceeds 1000 records?**

A: CrudX automatically switches to paginated response with a message indicating the total count. Use the `/paged` endpoint for full control.

**Q: How does batch operation duplicate handling work?**

A: When `skipDuplicates=true`, CrudX:
1. Validates each entity against unique constraints
2. Skips duplicates and continues processing
3. Returns both created entities and skip reasons

When `skipDuplicates=false` (default):
1. First duplicate causes entire batch to fail
2. Transaction rolls back
3. No entities are created

**Q: How accurate is memory tracking?**

A: CrudX uses thread-local memory allocation measurement (`ThreadMXBean`). First requests show higher values due to JVM warmup. Typical request memory: 50-500KB.

**Q: Can I disable auto-generation for specific entities?**

A: Yes, simply don't create a controller that extends `CrudXController` for that entity. Use standard Spring Data repositories instead.

**Q: Does CrudX affect existing Spring Data repositories?**

A: No, CrudX only manages entities with CrudX controllers. Your existing repositories continue to work normally.

### Performance Questions

**Q: What's the maximum batch size?**

A: CrudX automatically limits batches to 1000 records for safety. For larger datasets, split into multiple batches.

**Q: How does CrudX handle large datasets?**

A: For datasets > 5000 records, CrudX automatically uses cursor-based streaming with batches of 100 records to minimize memory usage.

**Q: Should I always enable performance monitoring?**

A: For development/testing: Yes, very helpful for optimization.
For production: Only if you need it - adds minimal overhead (~2-3ms per request) but consumes memory for metrics storage.

**Q: Why is my first request slow?**

A: This is JVM warmup. Subsequent requests are significantly faster as JIT compilation optimizes the code.

### Troubleshooting Questions

**Q: My controller endpoints return 404**

A: Check:
1. Controller extends `CrudXController<Entity, ID>`
2. Entity extends correct base class (CrudXMySQLEntity, etc.)
3. `@RestController` and `@RequestMapping` are present
4. Application has `@CrudX` annotation

**Q: Getting "Service bean not found" error**

A: Entity must extend database-specific base class:
- `CrudXMySQLEntity` for MySQL
- `CrudXPostgreSQLEntity` for PostgreSQL
- `CrudXMongoEntity` for MongoDB

**Q: Unique constraint not working**

A: Ensure you're using `@CrudXUniqueConstraint` (not JPA's `@UniqueConstraint`). For multiple constraints, use `@CrudXUniqueConstraints` wrapper.

**Q: Performance dashboard shows 404**

A: Check:
1. `crudx.performance.enabled=true` in configuration
2. Accessing correct path: `/crudx/performance/dashboard`
3. Application started without errors

---

## Getting Help

### Community Support

- **GitHub Issues**: [Report bugs or request features](https://github.com/sachinnimbal/crudx-starter/issues)
- **Stack Overflow**: Tag questions with `crudx-framework`
- **Examples**: [Sample projects](https://github.com/sachinnimbal/crudx-examples)

### Documentation

- **API Documentation**: [API_DOCUMENTATION.md](API_DOCUMENTATION.md)
- **README**: [README.md](README.md)
- **Wiki**: [Detailed guides and tutorials](https://github.com/sachinnimbal/crudx-examples/wiki)

### Contact

- **Email**: sachinnimbal@gmail.com
- **LinkedIn**: [Sachin Nimbal](https://www.linkedin.com/in/sachin-nimbal/)

---

## Debugging Checklist

When encountering issues, check these in order:

- [ ] CrudX dependency is in `pom.xml` or `build.gradle`
- [ ] `@CrudX` annotation on main application class
- [ ] Database configuration present in `application.yml`
- [ ] Database server is running and accessible
- [ ] Entity extends correct base class (`CrudXMySQLEntity`, etc.)
- [ ] Controller extends `CrudXController<Entity, ID>`
- [ ] Generic types match between entity and controller
- [ ] Database driver dependency is present
- [ ] No conflicting Spring Data repositories for same entity
- [ ] Application starts without errors in console

---

**Still stuck? Check the [API Documentation](API_DOCUMENTATION.md) or open an issue on GitHub.**