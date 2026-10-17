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
    inputs.property("calibrationMmfitDirectory", System.getenv("KCOACH_CALIBRATION_MMFIT_DIR").orEmpty())
    inputs.property("mmfitDirectory", System.getenv("KCOACH_MMFIT_DIR").orEmpty())
    inputs.property("peakComparisonSessions", System.getenv("KCOACH_PEAK_SESSIONS").orEmpty())
    inputs.property("pressEvidenceZip", System.getenv("KCOACH_PRESS_EVIDENCE_ZIP").orEmpty())
    System.getenv("KCOACH_PRESS_EVIDENCE_ZIP")?.takeIf { file(it).isFile }?.let { inputs.file(it) }
}
