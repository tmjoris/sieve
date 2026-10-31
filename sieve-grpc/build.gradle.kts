dependencies {
    api(project(":sieve-core"))
    api(platform("io.grpc:grpc-bom:1.85.1"))
    api("io.grpc:grpc-api")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.grpc:grpc-stub")
    testImplementation("io.grpc:grpc-inprocess")
    testRuntimeOnly("io.grpc:grpc-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
