plugins {
    application
}

dependencies {
    implementation(project(":sieve-core"))
}

application {
    mainClass.set("dev.sieve.demo.DemoServer")
}
