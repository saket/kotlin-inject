KSP issue, to reproduce:

run ./gradlew integration-tests:ksp:test
In integration-tests/common/src/test/kotlin/me/tatarka/inject/test/ProvidesTest.kt,
modify ProvidesImplicitReturnType from:
```kotlin
@Component abstract class ProvidesImplicitReturnType {
    abstract val foo: String

    @Provides
    fun foo() = privateGetFoo()

    private fun privateGetFoo() = "test"
}
```
to:
```kotlin
@Component abstract class ProvidesImplicitReturnType {
    abstract val foo: List<String>

    @Provides
    fun foo() = privateGetFoo()

    private fun privateGetFoo() = listOf("test")
}
```
re-run ./gradlew integration-tests:ksp:test