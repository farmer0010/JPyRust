plugins {
    `java-library`
}
repositories {
    mavenCentral()
}
dependencies {
    // 추후 Native Loader 로직을 위한 유틸리티가 필요하면 여기에 추가
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
tasks.test {
    useJUnitPlatform()
}
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17)) // 호환성이 좋은 LTS 버전
    }
}
