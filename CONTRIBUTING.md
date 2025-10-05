# Contributing to CrudX Framework

Thank you for your interest in contributing to CrudX! This document provides guidelines and instructions for contributing to the project.

---

## Table of Contents

1. [Code of Conduct](#code-of-conduct)
2. [Getting Started](#getting-started)
3. [How to Contribute](#how-to-contribute)
4. [Development Setup](#development-setup)
5. [Coding Standards](#coding-standards)
6. [Testing Guidelines](#testing-guidelines)
7. [Pull Request Process](#pull-request-process)
8. [Issue Guidelines](#issue-guidelines)
9. [Documentation](#documentation)
10. [Community](#community)

---

## Code of Conduct

### Our Pledge

We are committed to providing a welcoming and inclusive environment for all contributors, regardless of experience level, gender identity, sexual orientation, disability, personal appearance, race, ethnicity, age, religion, or nationality.

### Expected Behavior

- Use welcoming and inclusive language
- Be respectful of differing viewpoints and experiences
- Gracefully accept constructive criticism
- Focus on what is best for the community
- Show empathy towards other community members

### Unacceptable Behavior

- Harassment, trolling, or discriminatory comments
- Publishing others' private information without permission
- Personal or political attacks
- Any conduct that could reasonably be considered inappropriate in a professional setting

### Enforcement

Violations of the Code of Conduct may be reported to sachinnimbal9@gmail.com. All complaints will be reviewed and investigated promptly and fairly.

---

## Getting Started

### Prerequisites

- Java 17 or higher
- Maven 3.8+ or Gradle 7.0+
- Git
- IDE (IntelliJ IDEA, Eclipse, or VS Code recommended)
- Docker (optional, for testing with databases)

### Find an Issue to Work On

1. Browse [open issues](https://github.com/sachinnimbal/crudx-starter/issues)
2. Look for issues labeled `good first issue` or `help wanted`
3. Comment on the issue to let others know you're working on it
4. Wait for maintainer approval before starting work

### Types of Contributions

We welcome:

- Bug fixes
- New features
- Documentation improvements
- Test coverage improvements
- Performance optimizations
- Example applications
- Tutorial content

---

## How to Contribute

### Reporting Bugs

Before creating a bug report:

1. Check the [existing issues](https://github.com/sachinnimbal/crudx-starter/issues)
2. Verify the bug exists in the latest version
3. Check the [documentation](https://github.com/sachinnimbal/crudx-examples/wiki)

**Bug Report Template:**

```markdown
**Description:**
A clear description of the bug

**Steps to Reproduce:**
1. Step one
2. Step two
3. Step three

**Expected Behavior:**
What should happen

**Actual Behavior:**
What actually happens

**Environment:**
- CrudX Version: 
- Spring Boot Version: 
- Java Version: 
- Database: MySQL/PostgreSQL/MongoDB
- OS: 

**Additional Context:**
Any other relevant information, logs, or screenshots
```

### Suggesting Features

**Feature Request Template:**

```markdown
**Feature Description:**
Clear description of the proposed feature

**Problem it Solves:**
What problem does this feature address?

**Proposed Solution:**
How do you envision this feature working?

**Alternatives Considered:**
Other approaches you've considered

**Additional Context:**
Mockups, examples, or references
```

---

## Development Setup

### 1. Fork and Clone

```bash
# Fork the repository on GitHub
# Then clone your fork
git clone https://github.com/YOUR_USERNAME/crudx-starter.git
cd crudx-starter

# Add upstream remote
git remote add upstream https://github.com/sachinnimbal/crudx-starter.git
```

### 2. Set Up Development Environment

**Using Maven:**
```bash
# Install dependencies
mvn clean install

# Run tests
mvn test
```

**Using Gradle:**
```bash
# Install dependencies
./gradlew build

# Run tests
./gradlew test
```

### 3. Database Setup for Testing

**Docker Compose (Recommended):**

```yaml
# docker-compose.yml
version: '3.8'
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: testpassword
      MYSQL_DATABASE: crudx_test
    ports:
      - "3306:3306"
  
  postgres:
    image: postgres:15
    environment:
      POSTGRES_PASSWORD: testpassword
      POSTGRES_DB: crudx_test
    ports:
      - "5432:5432"
  
  mongodb:
    image: mongo:6.0
    environment:
      MONGO_INITDB_ROOT_USERNAME: root
      MONGO_INITDB_ROOT_PASSWORD: testpassword
    ports:
      - "27017:27017"
```

```bash
# Start databases
docker-compose up -d

# Run tests
mvn test

# Stop databases
docker-compose down
```

### 4. Create a Branch

```bash
# Update your fork
git fetch upstream
git checkout main
git merge upstream/main

# Create feature branch
git checkout -b feature/your-feature-name

# Or for bug fixes
git checkout -b fix/bug-description
```

---

## Coding Standards

### Java Code Style

Follow these conventions:

**1. Naming Conventions:**
```java
// Classes: PascalCase
public class CrudXService { }

// Methods: camelCase
public void createEntity() { }

// Constants: UPPER_SNAKE_CASE
private static final int MAX_BATCH_SIZE = 1000;

// Variables: camelCase
private String entityName;
```

**2. Code Formatting:**
```java
// Indentation: 4 spaces (no tabs)
// Braces: Same line for methods, classes
public class Example {
    
    public void method() {
        if (condition) {
            // code
        } else {
            // code
        }
    }
}

// Line length: Max 120 characters
// Imports: Organize and remove unused
```

**3. Documentation:**
```java
/**
 * Creates a new entity with validation.
 * 
 * @param entity the entity to create
 * @return the created entity with generated ID
 * @throws DuplicateEntityException if unique constraint violated
 */
public T create(T entity) {
    // implementation
}
```

**4. Logging:**
```java
// Use SLF4J
private static final Logger log = LoggerFactory.getLogger(MyClass.class);

// Log levels:
log.debug("Detailed diagnostic information");
log.info("General informational messages");
log.warn("Warning messages for potentially harmful situations");
log.error("Error events that might still allow the application to continue");
```

### Best Practices

**1. Null Safety:**
```java
// Always check for nulls
if (entity == null) {
    throw new IllegalArgumentException("Entity cannot be null");
}

// Use Optional for potentially null returns
public Optional<Employee> findByEmail(String email) {
    // implementation
}
```

**2. Exception Handling:**
```java
// Use specific exceptions
throw new DuplicateEntityException("Email already exists");

// Not generic exceptions
// BAD: throw new RuntimeException("Error");
```

**3. Resource Management:**
```java
// Use try-with-resources
try (Connection conn = dataSource.getConnection()) {
    // use connection
} catch (SQLException e) {
    log.error("Database error", e);
    throw new DatabaseException("Failed to connect", e);
}
```

**4. Performance:**
```java
// Avoid N+1 queries
// Use batch operations
// Close resources properly
// Use appropriate data structures
```

---

## Testing Guidelines

### Test Structure

```
src/
├── test/
│   ├── java/
│   │   └── io/github/sachinnimbal/crudx/
│   │       ├── unit/          # Unit tests
│   │       ├── integration/   # Integration tests
│   │       └── performance/   # Performance tests
│   └── resources/
│       └── application-test.yml
```

### Writing Tests

**1. Unit Tests:**
```java
@ExtendWith(MockitoExtension.class)
class CrudXServiceTest {
    
    @Mock
    private EntityManager entityManager;
    
    @InjectMocks
    private CrudXSQLService<Employee, Long> service;
    
    @Test
    @DisplayName("Should create entity successfully")
    void testCreateEntity() {
        // Given
        Employee employee = new Employee();
        employee.setName("Crudx Starter");
        
        // When
        Employee result = service.create(employee);
        
        // Then
        assertNotNull(result);
        verify(entityManager).persist(employee);
    }
    
    @Test
    @DisplayName("Should throw exception for duplicate entity")
    void testCreateDuplicate() {
        // Given
        Employee employee = new Employee();
        employee.setEmail("duplicate@example.com");
        
        // When & Then
        assertThrows(DuplicateEntityException.class, () -> {
            service.create(employee);
        });
    }
}
```

**2. Integration Tests:**
```java
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
class EmployeeControllerIntegrationTest {
    
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("test")
        .withUsername("test")
        .withPassword("test");
    
    @Autowired
    private MockMvc mockMvc;
    
    @Test
    void testCreateEmployee() throws Exception {
        String json = """
            {
                "name": "Crudx Starter",
                "email": "crudx@example.com"
            }
            """;
        
        mockMvc.perform(post("/api/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.name").value("Crudx Starter"));
    }
}
```

**3. Test Coverage Requirements:**
- Minimum 80% code coverage for new features
- 100% coverage for critical paths (create, update, delete)
- All public methods must have tests
- Edge cases must be tested

**4. Running Tests:**
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=CrudXServiceTest

# Run with coverage
mvn test jacoco:report

# Skip tests
mvn install -DskipTests
```

---

## Pull Request Process

### Before Submitting

- [ ] Code follows style guidelines
- [ ] All tests pass locally
- [ ] New tests added for new features
- [ ] Documentation updated
- [ ] Commit messages are clear
- [ ] No merge conflicts with main branch

### Commit Message Format

Follow conventional commits:

```
type(scope): subject

body (optional)

footer (optional)
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting)
- `refactor`: Code refactoring
- `test`: Test additions or changes
- `chore`: Build process or tooling changes
- `perf`: Performance improvements

**Examples:**
```bash
feat(service): add batch delete operation

Implements batch delete with existence checking and skip tracking.
Processes in batches of 100 for memory efficiency.

Closes #123

---

fix(controller): handle null pointer in update endpoint

Added null checks before accessing entity fields during partial updates.

Fixes #456

---

docs(readme): update installation instructions

Added Gradle installation steps and clarified Maven configuration.
```

### Pull Request Template

```markdown
## Description
Brief description of changes

## Type of Change
- [ ] Bug fix
- [ ] New feature
- [ ] Breaking change
- [ ] Documentation update

## Related Issues
Closes #123

## Changes Made
- Change 1
- Change 2
- Change 3

## Testing
- [ ] Unit tests added
- [ ] Integration tests added
- [ ] Manual testing completed

## Checklist
- [ ] Code follows style guidelines
- [ ] Self-review completed
- [ ] Comments added for complex code
- [ ] Documentation updated
- [ ] No new warnings
- [ ] Tests pass locally
- [ ] Dependent changes merged

## Screenshots (if applicable)
```

### Review Process

1. Automated checks run (build, tests, coverage)
2. Maintainer reviews code
3. Address review feedback
4. Approve and merge

**Review Criteria:**
- Code quality and style
- Test coverage
- Documentation completeness
- Performance impact
- Breaking changes justified

---

## Issue Guidelines

### Labels

- `bug`: Something isn't working
- `enhancement`: New feature or request
- `documentation`: Documentation improvements
- `good first issue`: Good for newcomers
- `help wanted`: Extra attention needed
- `question`: Further information requested
- `wontfix`: Won't be worked on
- `duplicate`: Already reported
- `performance`: Performance related
- `breaking`: Breaking change

### Issue Lifecycle

1. **New**: Issue created
2. **Triaged**: Maintainer reviews and labels
3. **Assigned**: Someone is working on it
4. **In Progress**: Work started
5. **Review**: Pull request submitted
6. **Closed**: Completed or won't fix

---

## Documentation

### What to Document

**Code Documentation:**
- Public API methods (JavaDoc)
- Complex algorithms
- Configuration options
- Important decisions

**User Documentation:**
- README updates
- API documentation
- Wiki articles
- Example code

### Documentation Style

```java
/**
 * Creates multiple entities in a single batch operation.
 * 
 * <p>This method processes entities in sub-batches of 100 for memory efficiency.
 * When {@code skipDuplicates} is true, duplicate entries are skipped instead of
 * causing the entire operation to fail.
 * 
 * @param entities the list of entities to create (max 1000)
 * @param skipDuplicates if true, skip duplicates; if false, fail on first duplicate
 * @return BatchResult containing created entities and skip information
 * @throws IllegalArgumentException if entities list is null or empty
 * @throws DuplicateEntityException if skipDuplicates is false and duplicate found
 * 
 * @since 1.0.0
 * @see BatchResult
 */
public BatchResult<T> createBatch(List<T> entities, boolean skipDuplicates) {
    // implementation
}
```

---

## Community

### Communication Channels

- **GitHub Issues**: Bug reports and feature requests
- **GitHub Discussions**: General questions and discussions
- **Stack Overflow**: Tag questions with `crudx-framework`
- **Email**: sachinnimbal9@gmail.com for private concerns

### Getting Help

- Check [documentation](https://github.com/sachinnimbal/crudx-examples/wiki)
- Search [existing issues](https://github.com/sachinnimbal/crudx-starter/issues)
- Ask in GitHub Discussions
- Post on Stack Overflow with `crudx-framework` tag

### Recognition

Contributors are recognized in:
- CONTRIBUTORS.md file
- Release notes
- Project README

---

## Release Process

### Version Numbering

We follow [Semantic Versioning](https://semver.org/):
- MAJOR: Breaking changes
- MINOR: New features (backward compatible)
- PATCH: Bug fixes

### Release Checklist

- [ ] All tests pass
- [ ] Documentation updated
- [ ] CHANGELOG updated
- [ ] Version bumped
- [ ] Tag created
- [ ] Published to Maven Central
- [ ] GitHub release created
- [ ] Announcement posted

---

## Questions?

If you have questions about contributing, please:

1. Check this document thoroughly
2. Search existing issues and discussions
3. Create a new discussion if needed
4. Email sachinnimbal9@gmail.com for specific concerns

---

## Thank You!

Your contributions make CrudX better for everyone. We appreciate your time and effort in improving this project!

**Happy coding!** 🚀

---

**License:** By contributing to CrudX, you agree that your contributions will be licensed under the Apache License 2.0.