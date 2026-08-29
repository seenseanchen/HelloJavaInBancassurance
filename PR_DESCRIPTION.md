Title: Upgrade Java runtime to 25 (modernize/java-20260829061812)

Summary:
- Bump `<java.version>` from 21 → 25 in `pom.xml`.
- Configure `maven-compiler-plugin` with `<release>${java.version}</release>` to ensure compilation target.
- Verified compilation under JDK 25 and ran full test suite (Testcontainers + PostgreSQL started via Docker).

Commits:
- 89b5b89c24cd13e03f5606979372fcbf28b74afd — Step 3: Upgrade java.version to 25 and set maven-compiler-plugin <release>${java.version}</release>

Validation performed:
- `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home ./mvnw -q -DskipTests clean test-compile` — compile OK
- `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home ./mvnw -q test` — full test suite executed with Docker/Testcontainers; all integration tests passed on local machine.

Testing notes / Next steps:
- CI may need Docker available for integration tests. If CI runners do not support Docker, consider using Testcontainers' remote Docker or a dedicated PG test instance for CI.
- Update any CI/Dockerfiles that pin older JDK images to `eclipse-temurin:25-jdk` or similar.

How to create PR locally (if CLI available):

```bash
# create PR from branch modernize/java-20260829061812 into main
gh pr create --base main --head modernize/java-20260829061812 --title "Upgrade Java runtime to 25" --body-file PR_DESCRIPTION.md
```

If you want me to open the PR now, I will try using the GitHub CLI (`gh`). If it fails, I'll give the command above for you to run.
