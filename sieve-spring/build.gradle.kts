dependencies {
    api(project(":sieve-core"))
    compileOnly(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    compileOnly("org.springframework.boot:spring-boot-starter-web")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
