# JPyRust Web Demo

This is a Spring Boot application demonstrating the usage of `JPyRust` library.

## Prerequisites
- JDK 17+
- Gradle (System installed or use wrapper if generated)

## How to Run
1. Navigate to this directory:
   ```powershell
   cd demo-web
   ```
2. Run the application (using system gradle):
   ```powershell
   gradle bootRun
   ```
   Or if you generate a wrapper:
   ```powershell
   ./gradlew bootRun
   ```

## API Usage
- **Endpoint**: `GET /api/ai/chat`
- **Params**:
    - `message`: (String) Input text
    - `id`: (int) Numeric ID
- **Example**:
  ```
  http://localhost:8080/api/ai/chat?message=Hello&id=123
  ```
- **Response**: JSON containing the processed result from Python.
