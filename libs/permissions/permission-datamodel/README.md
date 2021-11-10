This module contains ORM mapping classes for representing RPC RBAC data constructs.

## Integration Tests

Integration tests are not simple JUnit tests, but OSGi tests that have to be executed using Gradle as follows:

```
gradle :libs:permissions:permission-datamodel:clean :libs:permissions:permission-datamodel:integrationTest
```

Should you need to debug `-D-runjdb=5005` can be added to expose port 5005 for remote debugging.

For more information on DB integration tests, please see [here](../../db/readme.md).

## There is more to do
1. Try running locally with Postgres
2. Switch to use a dedicated schema for RBAC
3. Make change to Corda API to be able to use `db.changelog-master.xml`