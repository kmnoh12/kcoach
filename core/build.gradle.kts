plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    inputs.property("mmfitDirectory", System.getenv("KCOACH_MMFIT_DIR").orEmpty())
    inputs.property("peakComparisonSessions", System.getenv("KCOACH_PEAK_SESSIONS").orEmpty())
}
