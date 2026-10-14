package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

data class PatternCandidate(val profile: ReviewedPatternProfile, val sha256: String, val createdAtMs: Long)

data class VerifiedPatternAdoption(val candidate: PatternCandidate, val evidence: PatternValidationEvidence)

/** Local frozen candidates, immutable reports and source checks. Transport is owned by the adoption controller. */
class PatternCandidateStore(private val context: Context) {
    private val root = File(context.filesDir, "reviewed-patterns")
    private val captures = CalibrationCaptureStore(context)
    private val reviews = CalibrationReviewStore(context)

    fun localUserId(): String = synchronized(lock) {
        val prefs = context.getSharedPreferences("measurement-owner", Context.MODE_PRIVATE)
        prefs.getString("id", null) ?: UUID.randomUUID().toString().also { check(prefs.edit().putString("id", it).commit()) }
    }

    fun saveCandidate(profile: ReviewedPatternProfile): Result<PatternCandidate> = synchronized(lock) {
        runCatching {
            require(profile.compatibility.localUserId == localUserId())
            val bytes = ReviewedPatternCodec.encode(profile)
            val hash = SensorCaptureFormat.sha256(bytes)
            val file = candidateFile(profile.id)
            if (file.exists()) {
                val saved = load(profile.id).getOrThrow()
                require(saved.sha256 == hash) { "A frozen candidate cannot be overwritten" }
                return@runCatching saved
            }
            require(list().getOrThrow().size < 500) { "Candidate storage limit reached; existing data is retained" }
            val created = System.currentTimeMillis()
            writeNew(file, JSONObject().put("schemaVersion", 1).put("createdAtMs", created).put("sha256", hash)
                .put("profile", Base64.getEncoder().encodeToString(bytes)))
            PatternCandidate(profile, hash, created)
        }
    }

    fun load(id: String): Result<PatternCandidate> = synchronized(lock) {
        runCatching {
            val file = candidateFile(id)
            require(file.length() in 1..3_000_000)
            val json = JSONObject(file.readText())
            require(json.getInt("schemaVersion") == 1)
            val bytes = Base64.getDecoder().decode(json.getString("profile"))
            val hash = json.getString("sha256")
            require(SensorCaptureFormat.sha256(bytes) == hash)
            val profile = ReviewedPatternCodec.decode(bytes)
            require(profile.id == id && profile.compatibility.localUserId == localUserId())
            val created = json.getLong("createdAtMs").also { require(it > 0) }
            PatternCandidate(profile, hash, created)
        }
    }

    fun list(): Result<List<PatternCandidate>> = synchronized(lock) {
        runCatching { root.listFiles().orEmpty().filter { it.name.endsWith(".candidate.json") }
            .map { load(it.name.removeSuffix(".candidate.json")).getOrThrow() }.sortedByDescending { it.createdAtMs } }
    }

    /** Every compatible reviewed clip collected so far can join the next training version. */
    fun buildForCapture(captureId: String): Result<PatternCandidate> = synchronized(lock) {
        runCatching {
            val current = readSet(captures.load(captureId).getOrThrow()).getOrThrow()
            require(current.references.any { it.eligibleForLearning }) { "No reviewed, synchronized repetitions" }
            val accumulated = captures.list().mapNotNull { readSet(it).getOrNull() }.filter {
                it.compatibility == current.compatibility && it.references.any { ref -> ref.eligibleForLearning }
            }
            val version = list().getOrThrow().filter { it.profile.compatibility == current.compatibility }
                .maxOfOrNull { it.profile.version }?.plus(1) ?: 1L
            val profile = ReviewedPatternBuilder.build(accumulated, "pattern-${UUID.randomUUID()}", version).getOrThrow()
            val previous = list().getOrThrow().filter { it.profile.compatibility == current.compatibility }.maxByOrNull { it.profile.version }
            if (previous != null && profile.copy(id = previous.profile.id, version = previous.profile.version) == previous.profile)
                return@runCatching previous
            saveCandidate(profile).getOrThrow()
        }
    }

    /** Only clips filmed AFTER this candidate was frozen can provide its held-out evidence. */
    fun validate(candidateId: String): Result<PatternValidationReport> = synchronized(lock) {
        runCatching {
            val candidate = load(candidateId).getOrThrow()
            checkCurrentSources(candidate.profile)
            val previous = observedRuntime(candidate.profile.compatibility)
            val unscored = mutableListOf<UnscoredPatternCapture>()
            val key = candidate.profile.compatibility
            val heldOut = captures.list().filter { it.createdAtMs > candidate.createdAtMs &&
                ExerciseMeasurementContracts.resolve(it.exerciseId).recipe == key.recipe &&
                (if (it.watchOnLeft) "LEFT" else "RIGHT") == key.side && it.watchOrientation == key.placement }.mapNotNull { capture ->
                val read = readSet(capture)
                val set = read.getOrNull()
                val count = reviews.load(capture.id).getOrNull()?.countReference
                if (set == null || set.compatibility != key || count == null) {
                    unscored += UnscoredPatternCapture(capture.id,
                        Instant.ofEpochMilli(capture.createdAtMs).atOffset(ZoneOffset.UTC).toLocalDate().toString(),
                        capture.exerciseId, count?.reps, when { set == null -> "capture_not_ready";
                            set.compatibility != key -> "device_incompatible"; else -> "count_not_reviewed" })
                    return@mapNotNull null
                }
                val sensor = captures.sensor(capture.id).getOrThrow()
                val baseline = TrackingProfileSnapshot.decode(sensor.manifest.payload.getValue(WirePayloadKeys.TRACKING_PROFILE_SNAPSHOT))
                if (!baselineMatches(sensor.manifest, previous)) {
                    unscored += UnscoredPatternCapture(capture.id, set.dayId, set.exerciseId, count.reps, "baseline_identity_mismatch")
                    return@mapNotNull null
                }
                HeldOutPatternSet(set, count, baseline)
            }
            val report = ReviewedPatternValidation.evaluate(candidate.profile, heldOut, unscored, previousRuntime = previous).getOrThrow()
            checkCurrentSources(candidate.profile)
            heldOut.forEach { snapshot ->
                val current = readSet(captures.load(snapshot.set.captureId).getOrThrow()).getOrThrow()
                require(ReviewedPatternBuilder.referenceDigest(current.references) == ReviewedPatternBuilder.referenceDigest(snapshot.set.references) &&
                    reviews.load(current.captureId).getOrThrow().countReference == snapshot.countReference) { "Validation labels changed; evaluate again" }
            }
            val directory = File(root, "$candidateId-validation").also { check(it.mkdirs() || it.isDirectory) }
            val json = JSONObject().put("schemaVersion", 1).put("candidateId", candidateId).put("candidateHash", candidate.sha256)
                .put("baselineCandidateHash", previous?.candidateHash ?: JSONObject.NULL)
                .put("baselineRuntimeHash", previous?.let { SensorCaptureFormat.sha256(PatternRuntimeCodec.encode(it)) } ?: JSONObject.NULL)
                .put("evaluatedAtMs", System.currentTimeMillis()).put("engine", candidate.profile.engineId)
                .put("scope", "OFFLINE_PHONE_ONLY").put("countGatePassed", report.countGatePassed).put("watchAdoptionReady", false)
                .put("sets", report.totalSets).put("reps", report.totalReps).put("days", report.distinctDays)
                .put("unknownReferenceSets", report.unknownReferenceSets)
                .put("unscoredCaptures", JSONArray(report.unscoredCaptures.map { missing ->
                    JSONObject().put("captureId", missing.captureId).put("dayId", missing.dayId)
                        .put("exerciseId", missing.exerciseId).put("expected", missing.expected ?: JSONObject.NULL).put("reason", missing.reason)
                }))
                .put("predictedSets", report.predictedSets).put("exactSets", report.exactSets).put("withinOneSets", report.withinOneSets)
                .put("rawMae", report.rawMae ?: JSONObject.NULL).put("baselineRawMae", report.baselineRawMae ?: JSONObject.NULL)
                .put("paperRawMae", report.paperRawMae ?: JSONObject.NULL).put("causalRawMae", report.causalRawMae ?: JSONObject.NULL)
                .put("causalExactSets", report.causalExactSets)
                .put("startBoundaryMaeMs", report.startBoundaryMaeMs ?: JSONObject.NULL)
                .put("turnaroundMaeMs", report.turnaroundMaeMs ?: JSONObject.NULL).put("endBoundaryMaeMs", report.endBoundaryMaeMs ?: JSONObject.NULL)
                .put("failures", JSONArray(report.failures)).put("rows", JSONArray(report.rows.map { row ->
                    JSONObject().put("captureId", row.captureId).put("rawSha256", row.rawSha256).put("reviewRevision", row.reviewRevision)
                        .put("referenceDigest", ReviewedPatternBuilder.referenceDigest(heldOut.single { it.set.captureId == row.captureId }.set.references))
                        .put("countReference", countJson(heldOut.single { it.set.captureId == row.captureId }.countReference))
                        .put("manifestHash", manifestHash(captures.sensor(row.captureId).getOrThrow().manifest))
                        .put("exerciseId", row.exerciseId).put("dayId", row.dayId).put("expected", row.expected)
                        .put("baseline", row.baselineCount ?: JSONObject.NULL).put("candidate", row.candidateCount ?: JSONObject.NULL)
                        .put("paperPeak", row.paperPeakCount ?: JSONObject.NULL).put("causal", row.causalCount ?: JSONObject.NULL)
                        .put("causalSourceEmissionDelaysMs", JSONArray(row.causalSourceEmissionDelaysMs))
                        .put("exclusionReason", row.exclusionReason ?: JSONObject.NULL)
                        .put("startErrorsMs", JSONArray(row.startErrorsMs)).put("turnErrorsMs", JSONArray(row.turnErrorsMs))
                        .put("endErrorsMs", JSONArray(row.endErrorsMs))
                }))
            require(observedRuntime(candidate.profile.compatibility) == previous) { "Observed active profile changed; evaluate again" }
            writeNew(File(directory, "evaluation-${UUID.randomUUID()}.json"), json)
            report
        }
    }

    fun validateNative(candidateId: String): Result<NativePatternValidationReport> = synchronized(lock) {
        runCatching {
            val candidate = load(candidateId).getOrThrow()
            checkCurrentSources(candidate.profile)
            val previous = observedRuntime(candidate.profile.compatibility)
            val key = candidate.profile.compatibility
            val missing = mutableListOf<UnscoredPatternCapture>()
            val cases = captures.list().filter { it.createdAtMs > candidate.createdAtMs &&
                ExerciseMeasurementContracts.resolve(it.exerciseId).recipe == key.recipe &&
                (if (it.watchOnLeft) "LEFT" else "RIGHT") == key.side && it.watchOrientation == key.placement
            }.mapNotNull { capture ->
                val set = readSet(capture).getOrNull()
                val count = reviews.load(capture.id).getOrNull()?.countReference
                if (set == null || count == null || set.compatibility != key) {
                    missing += UnscoredPatternCapture(capture.id,
                        Instant.ofEpochMilli(capture.createdAtMs).atOffset(ZoneOffset.UTC).toLocalDate().toString(),
                        capture.exerciseId, count?.reps, "capture_not_ready")
                    return@mapNotNull null
                }
                val sensor = captures.sensor(capture.id).getOrThrow()
                val manifest = sensor.manifest
                val trace = manifest.payload["patternShadowTrace"]?.takeIf { it.isNotEmpty() }
                    ?.let { PatternTrialTraceCodec.decode(it) }
                val baseline = TrackingProfileSnapshot.decode(manifest.payload.getValue(WirePayloadKeys.TRACKING_PROFILE_SNAPSHOT))
                NativePatternCase(HeldOutPatternSet(set, count, baseline), trace,
                    manifest.payload.getValue("watchClockId"),
                    manifest.payload[if (previous == null) WirePayloadKeys.WATCH_REPS else "patternBaselineCount"]?.toIntOrNull(),
                    trace?.events?.takeIf { events -> events.all { it.feedbackAtNs != null } }?.map { it.feedbackAtNs!! },
                    baselineRuntime = activeRuntimeFrom(manifest),
                    baselineRuntimeUsed = manifest.payload["patternActiveUsed"] == "true",
                    baselineRuntimeInterrupted = manifest.payload["patternActiveInterrupted"] != "false",
                    baselineSnapshotVerified = baselineMatches(manifest, previous),
                    baselineSampleCalls = manifest.payload["patternBaselineSampleCalls"]?.toIntOrNull())
            }
            val report = PatternNativeValidation.evaluate(candidate.profile, cases, missing, previousRuntime = previous).getOrThrow()
            checkCurrentSources(candidate.profile)
            cases.forEach { snapshot ->
                val current = readSet(captures.load(snapshot.heldOut.set.captureId).getOrThrow()).getOrThrow()
                require(ReviewedPatternBuilder.referenceDigest(current.references) == ReviewedPatternBuilder.referenceDigest(snapshot.heldOut.set.references) &&
                    reviews.load(current.captureId).getOrThrow().countReference == snapshot.heldOut.countReference) { "Native validation labels changed" }
            }
            val directory = File(root, "$candidateId-native-validation").also { check(it.mkdirs() || it.isDirectory) }
            val json = JSONObject().put("schemaVersion", 1).put("candidateId", candidateId).put("candidateHash", report.candidateHash)
                .put("baselineCandidateHash", previous?.candidateHash ?: JSONObject.NULL)
                .put("baselineRuntimeHash", previous?.let { SensorCaptureFormat.sha256(PatternRuntimeCodec.encode(it)) } ?: JSONObject.NULL)
                .put("evaluatedAtMs", System.currentTimeMillis()).put("scope", "OBSERVED_WATCH_OUTPUTS")
                .put("approved", report.approved).put("totalSets", report.totalSets).put("totalReps", report.totalReps)
                .put("validDays", report.validDays).put("predictedSets", report.predictedSets).put("exactSets", report.exactSets)
                .put("withinOneSets", report.withinOneSets).put("mae", report.mae ?: JSONObject.NULL)
                .put("latencyP95Ms", report.latencyP95Ms ?: JSONObject.NULL).put("worstBoundaryMaeMs", report.boundaryMaeMs ?: JSONObject.NULL)
                .put("failures", JSONArray(report.failures)).put("unscored", JSONArray(missing.map {
                    JSONObject().put("captureId", it.captureId).put("expected", it.expected ?: JSONObject.NULL).put("reason", it.reason)
                })).put("rows", JSONArray(report.rows.map { row -> JSONObject().put("captureId", row.captureId)
                    .put("rawSha256", row.rawSha256).put("referenceDigest", row.referenceDigest).put("reviewRevision", row.reviewRevision)
                    .put("countReference", countJson(cases.single { it.heldOut.set.captureId == row.captureId }.heldOut.countReference))
                    .put("manifestHash", manifestHash(captures.sensor(row.captureId).getOrThrow().manifest))
                    .put("traceHash", row.traceHash ?: JSONObject.NULL).put("dayId", row.dayId).put("exerciseId", row.exerciseId)
                    .put("expected", row.expected).put("predicted", row.predicted ?: JSONObject.NULL).put("baseline", row.baseline ?: JSONObject.NULL)
                    .put("exclusionReason", row.exclusionReason ?: JSONObject.NULL).put("latencyMs", JSONArray(row.latencyMs))
                    .put("boundaryErrorsMs", JSONArray(row.boundaryErrorsMs)).put("processingP95UpperNs", row.processingP95UpperNs ?: JSONObject.NULL)
                }))
            require(observedRuntime(candidate.profile.compatibility) == previous) { "Observed active profile changed; evaluate again" }
            writeNew(File(directory, "evaluation-${UUID.randomUUID()}.json"), json)
            report
        }
    }

    fun checkCurrentSources(profile: ReviewedPatternProfile) {
        profile.sources.forEach { source ->
            val set = readSet(captures.load(source.captureId).getOrThrow()).getOrThrow()
            val references = set.references.filter { it.eligibleForLearning }
            require(set.compatibility == profile.compatibility && set.rawSha256 == source.rawSha256 &&
                set.exerciseId == source.exerciseId && set.dayId == source.dayId &&
                references.firstOrNull()?.revision == source.reviewRevision &&
                ReviewedPatternBuilder.referenceDigest(references) == source.referenceDigest) { "Reviewed training sources changed; build a new candidate" }
        }
    }

    /** Pins all currently confirmed compatible material without filtering by predicted success. */
    fun freezeRegressionDataset(candidateId: String): Result<FrozenPatternBenchmark> = synchronized(lock) {
        runCatching {
            val candidate = load(candidateId).getOrThrow(); checkCurrentSources(candidate.profile)
            val key = candidate.profile.compatibility
            val material = captures.list().mapNotNull { capture ->
                val set = readSet(capture).getOrNull() ?: return@mapNotNull null
                if (set.compatibility != key) return@mapNotNull null
                val count = reviews.load(capture.id).getOrNull()?.countReference ?: return@mapNotNull null
                val manifest = captures.sensor(capture.id).getOrThrow().manifest
                HeldOutPatternSet(set, count, TrackingProfileSnapshot.decode(manifest.payload.getValue(WirePayloadKeys.TRACKING_PROFILE_SNAPSHOT)))
            }
            require(material.isNotEmpty()) { "No confirmed originals for fixed comparison" }
            PatternBenchmarkStore(context).freeze(material).getOrThrow()
        }
    }

    fun compareRegression(candidateId: String): Result<List<PatternRegressionReport>> = synchronized(lock) {
        runCatching {
            val candidate = load(candidateId).getOrThrow(); checkCurrentSources(candidate.profile)
            val runtime = PatternRuntimeProfile.from(candidate.profile)
            val previous = PatternTransferOutbox(context).activeHistory(runtime.compatibility).getOrThrow().firstOrNull()?.command?.runtime
            val store = PatternBenchmarkStore(context)
            val fixed = store.list(runtime.compatibility).getOrThrow()
            require(fixed.isNotEmpty()) { "Freeze confirmed originals before comparison" }
            fixed.map { benchmark ->
                val current = benchmark.entries.map { entry ->
                    val capture = captures.load(entry.captureId).getOrThrow()
                    val set = readSet(capture).getOrThrow()
                    val count = requireNotNull(reviews.load(capture.id).getOrThrow().countReference)
                    val manifest = captures.sensor(capture.id).getOrThrow().manifest
                    HeldOutPatternSet(set, count, TrackingProfileSnapshot.decode(manifest.payload.getValue(WirePayloadKeys.TRACKING_PROFILE_SNAPSHOT)))
                }
                val report = store.compare(benchmark.id, runtime, previous, current).getOrThrow()
                val after = benchmark.entries.map { entry ->
                    val capture = captures.load(entry.captureId).getOrThrow()
                    val manifest = captures.sensor(capture.id).getOrThrow().manifest
                    HeldOutPatternSet(readSet(capture).getOrThrow(), requireNotNull(reviews.load(capture.id).getOrThrow().countReference),
                        TrackingProfileSnapshot.decode(manifest.payload.getValue(WirePayloadKeys.TRACKING_PROFILE_SNAPSHOT)))
                }
                require(store.compare(benchmark.id, runtime, previous, after).getOrThrow() == report) { "Fixed comparison inputs changed while evaluating" }
                checkCurrentSources(candidate.profile)
                val currentPrevious = PatternTransferOutbox(context).activeHistory(runtime.compatibility).getOrThrow().firstOrNull()?.command?.runtime
                require(currentPrevious == previous) { "Observed active profile changed; compare again" }
                val directory = File(root, "$candidateId-regression").also { check(it.mkdirs() || it.isDirectory) }
                writeNew(File(directory, "evaluation-${UUID.randomUUID()}.json"), JSONObject().put("schemaVersion", 1)
                    .put("candidateHash", report.candidateHash).put("previousHash", report.previousHash ?: JSONObject.NULL)
                    .put("benchmarkId", benchmark.id).put("benchmarkHash", benchmark.sha256).put("scope", "FIXED_REGRESSION_ONLY")
                    .put("independentAccuracyEvidence", false).put("passed", report.passed).put("failures", JSONArray(report.failures))
                    .put("rows", JSONArray(report.rows.map { row -> JSONObject().put("captureId", row.captureId)
                        .put("rawSha256", row.rawSha256).put("referenceDigest", row.referenceDigest).put("expected", row.countReference.reps)
                        .put("exerciseId", row.exerciseId).put("candidate", row.candidateCount ?: JSONObject.NULL)
                        .put("previous", row.previousCount ?: JSONObject.NULL).put("refusalReason", row.refusalReason ?: JSONObject.NULL)
                        .put("candidateBoundaryErrorsMs", JSONArray(row.candidateBoundaryErrorsMs)).put("previousBoundaryErrorsMs", JSONArray(row.previousBoundaryErrorsMs))
                    })))
                report
            }
        }
    }

    /** Re-executes all gates now. Saved booleans alone cannot authorize a new transition. */
    fun validateForAdoption(candidateId: String): Result<VerifiedPatternAdoption> = synchronized(lock) {
        runCatching {
            val candidate = load(candidateId).getOrThrow()
            val previous = observedRuntime(candidate.profile.compatibility)
            val before = validationMaterialDigest(candidate)
            val phoneBefore = reportFiles(candidateId, "validation").toSet()
            val phone = validate(candidateId).getOrThrow()
            require(phone.countGatePassed) { "Phone validation failed: ${phone.failures.joinToString()}" }
            val phoneFile = (reportFiles(candidateId, "validation").toSet() - phoneBefore).single()
            val nativeBefore = reportFiles(candidateId, "native-validation").toSet()
            val native = validateNative(candidateId).getOrThrow()
            require(native.approved) { "Native watch validation failed: ${native.failures.joinToString()}" }
            require(phone.previousHash == previous?.candidateHash && native.previousHash == previous?.candidateHash)
            val nativeFile = (reportFiles(candidateId, "native-validation").toSet() - nativeBefore).single()
            val regression = compareRegression(candidateId).getOrThrow()
            require(regression.isNotEmpty() && regression.all { it.passed }) { "Fixed regression comparison failed" }
            require(load(candidateId).getOrThrow() == candidate && observedRuntime(candidate.profile.compatibility) == previous &&
                validationMaterialDigest(candidate) == before) { "Validation material or observed baseline changed; evaluate again" }
            val evidence = PatternValidationEvidence(SensorCaptureFormat.sha256(phoneFile.readBytes()),
                SensorCaptureFormat.sha256(nativeFile.readBytes()), previous?.candidateHash)
            verifyAdoptionEvidence(candidateId, evidence, requireBaselineHash = true).getOrThrow()
            VerifiedPatternAdoption(candidate, evidence)
        }
    }

    /** Rechecks a previously used profile's exact receipt and originals before restoring it. */
    fun verifyAdoptionEvidence(candidateId: String, evidence: PatternValidationEvidence,
        requireBaselineHash: Boolean = false): Result<PatternCandidate> = synchronized(lock) {
        runCatching {
            val candidate = load(candidateId).getOrThrow()
            checkCurrentSources(candidate.profile)
            listOf("validation" to evidence.phoneReportHash, "native-validation" to evidence.watchReportHash).forEach { (kind, hash) ->
                val file = reportFiles(candidateId, kind).singleOrNull { SensorCaptureFormat.sha256(it.readBytes()) == hash }
                    ?: error("Verified report missing or changed")
                val json = JSONObject(file.readText())
                require(json.getInt("schemaVersion") == 1 && json.getString("candidateId") == candidateId &&
                    json.getString("candidateHash") == candidate.sha256)
                if (kind == "validation") require(json.getString("scope") == "OFFLINE_PHONE_ONLY" && json.getBoolean("countGatePassed"))
                else require(json.getString("scope") == "OBSERVED_WATCH_OUTPUTS" && json.getBoolean("approved"))
                require(json.getJSONArray("failures").length() == 0)
                val recordedBaselineHash = json.optString("baselineCandidateHash").takeIf { it != "null" && it.isNotEmpty() }
                if (requireBaselineHash) require(recordedBaselineHash == evidence.previousActiveHash)
                val rows = json.getJSONArray("rows")
                require(rows.length() > 0)
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val capture = captures.load(row.getString("captureId")).getOrThrow()
                    require(capture.createdAtMs > candidate.createdAtMs)
                    val set = readSet(capture).getOrThrow()
                    val count = reviews.load(capture.id).getOrThrow().countReference ?: error("Confirmed count missing")
                    require(set.compatibility == candidate.profile.compatibility && set.rawSha256 == row.getString("rawSha256") &&
                        set.exerciseId == row.getString("exerciseId") && set.dayId == row.getString("dayId") &&
                        ReviewedPatternBuilder.referenceDigest(set.references) == row.getString("referenceDigest") &&
                        countJson(count).toString() == row.getJSONObject("countReference").toString()) { "Validation originals or labels changed" }
                    val manifest = captures.sensor(capture.id).getOrThrow().manifest
                    require(manifestHash(manifest) == row.getString("manifestHash")) { "Recorded execution settings changed" }
                    val baseline = activeRuntimeFrom(manifest)
                    if (row.isNull("exclusionReason")) require(baseline?.candidateHash == recordedBaselineHash &&
                        baseline?.let { SensorCaptureFormat.sha256(PatternRuntimeCodec.encode(it)) } ==
                        json.optString("baselineRuntimeHash").takeIf { it != "null" && it.isNotEmpty() }) { "Recorded baseline does not match validation" }
                    if (kind == "native-validation" && !row.isNull("traceHash")) {
                        val trace = PatternTrialTraceCodec.decode(manifest.payload.getValue("patternShadowTrace"))
                        require(SensorCaptureFormat.sha256(PatternTrialTraceCodec.encode(trace).toByteArray(Charsets.UTF_8)) == row.getString("traceHash"))
                    }
                }
            }
            candidate
        }
    }

    private fun validationMaterialDigest(candidate: PatternCandidate): String {
        checkCurrentSources(candidate.profile)
        val key = candidate.profile.compatibility
        val material = captures.list().filter { ExerciseMeasurementContracts.resolve(it.exerciseId).recipe == key.recipe &&
            (if (it.watchOnLeft) "LEFT" else "RIGHT") == key.side && it.watchOrientation == key.placement }
            .sortedBy { it.id }.map { capture ->
                val sensor = captures.sensor(capture.id).getOrNull()
                JSONObject().put("capture", capture.toString()).put("review", reviews.load(capture.id).getOrNull()?.toString() ?: JSONObject.NULL)
                    .put("manifest", sensor?.manifest?.toString() ?: JSONObject.NULL).toString()
            }
        return SensorCaptureFormat.sha256(material.joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    private fun reportFiles(id: String, kind: String): List<File> {
        candidateFile(id) // validate path
        return File(root, "$id-$kind").listFiles().orEmpty().filter { it.name.endsWith(".json") && it.length() in 1..3_000_000 }
    }
    private fun observedRuntime(key: PatternCompatibility) = PatternTransferOutbox(context).activeHistory(key).getOrThrow().firstOrNull()?.command?.runtime
    private fun activeRuntimeFrom(manifest: WireEnvelope) = manifest.payload["patternActiveSnapshot"]?.takeIf { it.isNotEmpty() }
        ?.let { PatternRuntimeCodec.decode(Base64.getDecoder().decode(it)) }
    private fun baselineMatches(manifest: WireEnvelope, previous: PatternRuntimeProfile?): Boolean = runCatching {
        val generic = TrackingProfileSnapshot.decode(manifest.payload.getValue(WirePayloadKeys.TRACKING_PROFILE_SNAPSHOT))
        require(generic.exerciseId == manifest.payload[WirePayloadKeys.EXERCISE_ID])
        val runtime = activeRuntimeFrom(manifest)
        require(manifest.payload["patternActiveUsed"] in setOf("true", "false") && manifest.payload["patternActiveInterrupted"] == "false")
        if (runtime == null) {
            require(manifest.payload["patternActiveSnapshot"] == "" && manifest.payload["patternActiveCandidateHash"] == "" &&
                manifest.payload["patternActiveUsed"] == "false" && previous == null)
        } else {
            require(runtime == previous && manifest.payload["patternActiveUsed"] == "true" &&
                runtime.candidateHash == manifest.payload["patternActiveCandidateHash"] &&
                runtime.compatibility == PatternCompatibilityCodec.decode(manifest.payload.getValue("patternBinding")) &&
                runtime.compatibility.deviceId == manifest.payload["watchDeviceId"])
        }
        true
    }.getOrDefault(false)
    private fun countJson(count: SetCountReference) = JSONObject().put("setId", count.setId).put("attemptId", count.attemptId)
        .put("reps", count.reps).put("reviewedAtMs", count.reviewedAtMs)
    private fun manifestHash(manifest: WireEnvelope) = SensorCaptureFormat.sha256(WireCodec.encode(manifest).toByteArray(Charsets.UTF_8))

    private fun readSet(capture: CalibrationCapture): Result<ReviewedPatternSet> = runCatching {
        require(capture.status == CalibrationCaptureStatus.REVIEW_PENDING && capture.videoComplete)
        val sensor = captures.sensor(capture.id).getOrThrow()
        val manifest = sensor.manifest
        val device = manifest.payload["watchDeviceId"].orEmpty().also { require(it.isNotBlank()) { "Device identity missing; record a new clip" } }
        require(manifest.payload["watchEpochVerified"] == "true")
        val contract = ExerciseMeasurementContracts.resolve(capture.exerciseId)
        val key = PatternCompatibility(localUserId(), contract.recipe, if (capture.watchOnLeft) "LEFT" else "RIGHT",
            capture.watchOrientation, device, "watch-native-si-v1")
        val review = reviews.load(capture.id).getOrThrow()
        val day = Instant.ofEpochMilli(capture.createdAtMs).atOffset(ZoneOffset.UTC).toLocalDate().toString()
        ReviewedPatternSet(capture.id, manifest.payload.getValue(WirePayloadKeys.SHA256).lowercase(), capture.exerciseId,
            key, day, sensor.validated.document, review.references)
    }

    private fun candidateFile(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,100}")))
        return File(root, "$id.candidate.json")
    }
    private fun writeNew(file: File, json: JSONObject) {
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        require(!file.exists())
        val temp = File(file.parentFile, file.name + ".incoming")
        FileOutputStream(temp).use { out -> out.write(json.toString().toByteArray(Charsets.UTF_8)); out.fd.sync() }
        check(temp.renameTo(file))
    }
    companion object { private val lock = Any() }
}
