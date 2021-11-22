plugins {
    id("kotlin-inject.multiplatform")
    id("kotlin-inject.detekt")
    id("kotlin-inject.publish")
}

kotlin {
    sourceSets {
        nativeMain {
            dependencies {
                implementation(libs.kotlinx.atomicfu)
                implementation("co.touchlab:stately-iso-collections:1.1.10-a1")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines)
                implementation(libs.assertk)
            }
        }
    }
}
