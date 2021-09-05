plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotiln.gradle)
    implementation(libs.detekt.gradle)
    // hack to access version catelouge https://github.com/gradle/gradle/issues/15383
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}