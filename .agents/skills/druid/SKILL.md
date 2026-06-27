```markdown
# druid Development Patterns

> Auto-generated skill from repository analysis

## Overview
This skill teaches the core development patterns and conventions used in the `druid` Java codebase. You will learn about file naming, import/export styles, commit message conventions, and how to structure and run tests. This guide is ideal for contributors aiming to write consistent, high-quality code in the `druid` repository.

## Coding Conventions

### File Naming
- **Style:** PascalCase  
  **Example:**  
  ```java
  public class DataProcessor { ... }
  ```

### Import Style
- **Style:** Relative imports  
  **Example:**  
  ```java
  import mypackage.utils.Helper;
  ```

### Export Style
- **Style:** Named exports  
  **Example:**  
  ```java
  public class DataProcessor { ... }
  ```

### Commit Message Conventions
- **Type:** Conventional Commits
- **Prefix:** `feat`
- **Average Length:** 70 characters  
  **Example:**  
  ```
  feat: add support for new data ingestion format
  ```

## Workflows

### Feature Development
**Trigger:** When adding a new feature  
**Command:** `/feature-development`

1. Create a new branch for your feature.
2. Implement the feature using PascalCase file naming and relative imports.
3. Write or update tests in files matching `*.test.ts`.
4. Commit changes using the `feat` prefix and a clear, concise message.
5. Open a pull request for review.

### Testing
**Trigger:** When verifying code correctness  
**Command:** `/run-tests`

1. Identify or create test files with the `*.test.ts` pattern.
2. Run the test suite using the project's preferred test runner (framework unknown; refer to project documentation).
3. Ensure all tests pass before merging changes.

## Testing Patterns

- **Framework:** Unknown (refer to project documentation)
- **File Pattern:** `*.test.ts`
- **Example:**  
  ```typescript
  // DataProcessor.test.ts
  import { DataProcessor } from './DataProcessor';

  test('should process data correctly', () => {
    // test logic here
  });
  ```

## Commands
| Command              | Purpose                                      |
|----------------------|----------------------------------------------|
| /feature-development | Scaffold and document the feature workflow   |
| /run-tests           | Run all tests in the repository              |
```
