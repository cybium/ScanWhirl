package com.scanwhirl.app.eval

import android.graphics.BitmapFactory
import com.scanwhirl.app.util.Deskew
import com.scanwhirl.app.util.PhotoAutoCrop
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max

/**
 * scanbench: how well does our own gallery-photo detector (`Deskew.detectQuad`, the only
 * detector behind [PhotoAutoCrop]) do on real phone video?
 *
 * LOCAL ONLY, PRIVATE DATA. The frames come from the owner's private recordings and live
 * outside every checkout, in `qa/recordings/frames` (the videos themselves are in
 * `qa/recordings/inbox`). Nothing here copies, uploads or commits a video or a frame; this
 * test only reads the JPEGs in place and writes numbers. The class name contains "Eval", so
 * `tools\gate.ps1` keeps it on this PC and never sends it to the cloud.
 *
 * Skipped (not failed) when the frames folder is missing: CI has no private data and must not
 * go red. Re-cut frames with `qa/recordings/tools/extract_frames.py` (see
 * docs/evals/DETECTQUAD_VIDEO_EVAL.md).
 *
 * Metrics per clip, all on frames exactly as the app would decode them (upright, long side
 * 2400 px, JPEG q90, one frame every 0.5 s):
 *  - detection %: frames where [Deskew.detectQuad] returns a confident quad (the same
 *    STRONG_AREA / convexity / separation rules the app ships), i.e. the frames where a
 *    gallery photo would actually be auto-cropped;
 *  - false-positive %: clips 26-30 are NO-DOCUMENT scenarios, so any confident quad there is
 *    a false positive;
 *  - jitter: mean corner movement between consecutive detected frames, normalised by the
 *    frame diagonal (0 = rock steady, 1 = corners jump across the whole frame);
 *  - area %: quad area as a % of the frame;
 *  - edge-hug: mean distance of the four corners from the nearest frame border (0.03 = the
 *    detector picked the 3% inset corners). It is a diagnostic, not a verdict: a page that
 *    really fills the frame also hugs the border, but a quad that hugs it on an empty
 *    surface means the "confidence" rules accepted the frame itself;
 *  - frame-fit %: share of detected frames whose four corners all sit within 5% of a border,
 *    i.e. the quad is the frame. Harmless when the page really fills the frame, a wrong crop
 *    everywhere else;
 *  - ms/frame: median and p90 of the detector call itself (decode + gradient + quad).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DetectQuadVideoEval {

    private val frameRoots = listOf(
        File("/mnt/c/Users/Cybium/Desktop/ScanWhirl-HQ/qa/recordings/frames"),
        File("C:/Users/Cybium/Desktop/ScanWhirl-HQ/qa/recordings/frames")
    )

    private val resultsRoots = listOf(
        File("/mnt/c/Users/Cybium/Desktop/ScanWhirl-HQ/qa/recordings"),
        File("C:/Users/Cybium/Desktop/ScanWhirl-HQ/qa/recordings")
    )

    /** Clip -> the scenario as named in FINAL_VIDEO_INDEX.txt (verdicts read from the index, not guessed). */
    private data class Clip(val key: String, val scenario: String, val expectDocument: Boolean)

    private val clips = listOf(
        Clip("01", "A4 on wooden surface", true),
        Clip("02", "White page on white surface", true),
        Clip("03", "White page on patterned fabric", true),
        Clip("04", "Page on dark surface", true),
        Clip("05", "Handwritten notebook", true),
        Clip("06", "Open book", true),
        Clip("07", "Shop receipt", true),
        Clip("08", "Small prepaid card", true),
        Clip("09", "Move closer", true),
        Clip("10", "Page fills frame", true),
        Clip("11", "Corner near/outside frame", true),
        Clip("12", "Angled phone/perspective", true),
        Clip("13", "Shaky hand", true),
        Clip("14", "Sideways pan", true),
        Clip("15", "Paper held upright", true),
        Clip("16", "Low light", true),
        Clip("17", "Glare on glossy surface", true),
        Clip("18", "Hand shadow", true),
        Clip("19", "Side daylight", true),
        Clip("20", "Local/strong light", true),
        Clip("21", "Folded page", true),
        Clip("22", "Crumpled page", true),
        Clip("23", "Curled page", true),
        Clip("24", "Paper on other papers", true),
        Clip("25", "Two pages side by side", true),
        Clip("26", "Empty surface", false),
        Clip("27", "Laptop screen with document", false),
        Clip("28", "Door", false),
        Clip("29", "Cluttered desk", false),
        Clip("30", "Hand moving on empty table", false)
    )

    private val extraClips = listOf(
        Clip("EXTRA_02_Second_Book_Angled", "Extra: second angle of book", true),
        Clip("EXTRA_03_Desk_Calendar", "Extra: desk calendar (false-positive candidate)", false),
        Clip("EXTRA_04_Handwritten_Notebook", "Extra: handwriting sample", true),
        Clip("EXTRA_05_Spiral_Bound_Open_Pages", "Extra: spiral-bound open pages", true)
    )

    private data class ClipResult(
        val key: String,
        val scenario: String,
        val expectDocument: Boolean,
        val frames: Int,
        val detected: Int,
        val jitter: Double,
        val areaPercent: Double,
        val edgeHug: Double,
        val frameFitPercent: Double,
        val frameFitFrames: Int,
        val nonFrameFitPercent: Double,
        val medianMs: Double,
        val p90Ms: Double,
        val warpChecked: Int,
        val warpFailed: Int,
        /** Frame files worth a human look: no-quad frames on document clips, quad frames on no-document clips. */
        val interesting: List<String>
    ) {
        val detectionPercent: Double get() = if (frames == 0) 0.0 else 100.0 * detected / frames
        val falsePositivePercent: Double
            get() = if (expectDocument) 0.0 else detectionPercent
    }

    @Test
    fun `detect quad on real phone video frames`() {
        val root = frameRoots.firstOrNull { it.isDirectory }
        if (root == null) {
            println("EVAL SKIPPED: no frames at ${frameRoots.first().path} (private videos stay local)")
            return
        }
        val outRoot = resultsRoots.firstOrNull { it.isDirectory } ?: root.parentFile!!
        val all = clips + extraClips
        val present = all.filter { File(root, it.key).isDirectory }
        assertTrue(
            "frames folder is empty: run extract_frames.py",
            present.isNotEmpty()
        )

        // Warm up the JIT and BitmapFactory so the first clip is not charged for it.
        present.first().let { clip ->
            File(root, clip.key).listFiles { f -> f.isFile && f.extension.equals("jpg", true) }
                ?.sortedBy { it.name }?.firstOrNull()?.readBytes()?.let { Deskew.detectQuad(it) }
        }

        val results = present.map { runClip(File(root, it.key), it) }

        val text = render(results)
        println(text)
        val target = File(outRoot, "BENCH_detectQuad_data.md")
        target.writeText(text)
        println("WROTE ${target.absolutePath} at ${target.lastModified()}")
        assertTrue("every clip must contribute frames", results.all { it.frames > 0 })
    }

    private fun runClip(dir: File, clip: Clip): ClipResult {
        val frames = dir.listFiles { f -> f.isFile && f.extension.equals("jpg", true) }
            ?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
            .orEmpty()
        val times = ArrayList<Long>(frames.size)
        val quads = ArrayList<Deskew.Quad?>(frames.size)
        val dims = ArrayList<Pair<Int, Int>>(frames.size)
        var detected = 0

        for (frame in frames) {
            val bytes = frame.readBytes()
            val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, size)
            dims += max(1, size.outWidth) to max(1, size.outHeight)
            val started = System.nanoTime()
            val quad = runCatching { Deskew.detectQuad(bytes) }.getOrNull()
            times += System.nanoTime() - started
            quads += quad
            if (quad != null) detected++
        }

        // Jitter between consecutive detected frames, in units of the frame diagonal.
        val pairSteps = ArrayList<Double>()
        var previous = -1
        for (i in quads.indices) {
            val quad = quads[i] ?: continue
            if (previous >= 0) {
                val (w, h) = dims[i]
                val diagonal = hypot(w.toDouble(), h.toDouble())
                val a = quads[previous]!!.pts
                val b = quad.pts
                var sum = 0.0
                for (c in 0 until 4) {
                    val dx = ((b[c * 2] - a[c * 2]) * w).toDouble()
                    val dy = ((b[c * 2 + 1] - a[c * 2 + 1]) * h).toDouble()
                    sum += hypot(dx, dy) / diagonal
                }
                pairSteps += sum / 4.0
            }
            previous = i
        }

        val areas = quads.mapNotNull { it?.let { q -> kotlin.math.abs(shoelace(q.pts)) * 100.0 } }
        val hugs = quads.mapNotNull { q ->
            if (q == null) return@mapNotNull null
            var sum = 0.0
            for (c in 0 until 4) {
                val x = q.pts[c * 2].toDouble()
                val y = q.pts[c * 2 + 1].toDouble()
                sum += minOf(x, 1.0 - x, y, 1.0 - y)
            }
            sum / 4.0
        }
        // Frame-fit: every corner sits within 5% of a frame border, i.e. the quad is the frame
        // itself. Harmless on a page that really fills the frame, wrong everywhere else.
        val frameFits = quads.count { q ->
            q != null && (0 until 4).all { c ->
                val x = q.pts[c * 2].toDouble()
                val y = q.pts[c * 2 + 1].toDouble()
                minOf(x, 1.0 - x, y, 1.0 - y) < 0.05
            }
        }
        val sorted = times.sorted()
        val median = if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2] / 1_000_000.0
        val p90 = if (sorted.isEmpty()) 0.0 else sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)] / 1_000_000.0

        // End-to-end sanity on a few frames: the app entry must turn a quad into cropped bytes.
        var warpChecked = 0
        var warpFailed = 0
        for (i in quads.indices) {
            if (quads[i] == null) continue
            if (warpChecked >= 2) break
            warpChecked++
            val cropped = runCatching { PhotoAutoCrop.crop(frames[i].readBytes()) }.getOrNull()
            if (cropped == null || !cropped.cropped) warpFailed++
        }

        val interesting = if (clip.expectDocument) {
            // Frames the detector missed: the failure a human has to look at.
            quads.indices.filter { quads[it] == null }.map { frames[it].name }
        } else {
            // Frames it wrongly accepted.
            quads.indices.filter { quads[it] != null }.map { frames[it].name }
        }

        return ClipResult(
            key = clip.key,
            scenario = clip.scenario,
            expectDocument = clip.expectDocument,
            frames = frames.size,
            detected = detected,
            jitter = if (pairSteps.isEmpty()) 0.0 else pairSteps.average(),
            areaPercent = if (areas.isEmpty()) 0.0 else areas.average(),
            edgeHug = if (hugs.isEmpty()) 0.0 else hugs.average(),
            frameFitPercent = if (detected == 0) 0.0 else 100.0 * frameFits / detected,
            frameFitFrames = frameFits,
            nonFrameFitPercent = if (frames.isEmpty()) 0.0 else 100.0 * (detected - frameFits) / frames.size,
            medianMs = median,
            p90Ms = p90,
            warpChecked = warpChecked,
            warpFailed = warpFailed,
            interesting = interesting.take(3)
        )
    }

    private fun render(results: List<ClipResult>): String {
        // Canonical clips are "01".."30"; the EXTRA_* keys are reported on their own so the
        // headline numbers stay comparable with FINAL_VIDEO_INDEX.txt.
        val docs = results.filter { it.expectDocument && it.key.toIntOrNull() != null }
        val negatives = results.filter { !it.expectDocument && it.key.toIntOrNull() != null }
        val extras = results.filter { it.key.toIntOrNull() == null }
        val docFrames = docs.sumOf { it.frames }
        val docDetections = docs.sumOf { it.detected }
        val negFrames = negatives.sumOf { it.frames }
        val negDetections = negatives.sumOf { it.detected }
        val allTimes = results.map { it.medianMs }
        val sb = StringBuilder()

        sb.append("# detectQuad video benchmark (numbers only, no images)\n\n")
        sb.append("Generated by `DetectQuadVideoEval` (local Robolectric eval) on ")
            .append(results.size).append(" clips; one frame every 0.5 s, upright, long side 2400 px, JPEG q90.\n")
            .append("Detector = `Deskew.detectQuad`, the only detector behind `PhotoAutoCrop.crop`.\n\n")

        sb.append("| clip | scenario | frames | detected | detection % | false-pos % | frame-fit % | non-frame-fit % | jitter (diag) | area % | edge-hug | ms median | ms p90 |\n")
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n")
        for (r in results) {
            sb.append(
                String.format(
                    Locale.ROOT,
                    "| %s | %s | %d | %d | %.0f%% | %.0f%% | %.0f%% | %.0f%% | %.4f | %.1f | %.3f | %.1f | %.1f |%n",
                    r.key, r.scenario, r.frames, r.detected, r.detectionPercent,
                    r.falsePositivePercent, r.frameFitPercent, r.nonFrameFitPercent,
                    r.jitter, r.areaPercent, r.edgeHug, r.medianMs, r.p90Ms
                )
            )
        }

        sb.append("\n## Totals\n\n")
        sb.append(
            String.format(
                Locale.ROOT,
                "- Document clips 01-25: %d/%d frames detected = %.1f%%%n",
                docDetections, docFrames, if (docFrames == 0) 0.0 else 100.0 * docDetections / docFrames
            )
        )
        sb.append(
            String.format(
                Locale.ROOT,
                "- No-document clips 26-30: %d/%d frames accepted = %.1f%% FALSE POSITIVES%n",
                negDetections, negFrames, if (negFrames == 0) 0.0 else 100.0 * negDetections / negFrames
            )
        )
        sb.append(
            String.format(
                Locale.ROOT,
                "- Per-clip median detector time: %.1f ms (max %.1f ms)%n",
                if (allTimes.isEmpty()) 0.0 else allTimes.average(), allTimes.maxOrNull() ?: 0.0
            )
        )
        sb.append(
            String.format(
                Locale.ROOT,
                "- Mean edge-hug: documents %.3f, no-document clips %.3f (0.03 = the 3%% inset corners)%n",
                if (docs.isEmpty()) 0.0 else docs.map { it.edgeHug }.average(),
                if (negatives.isEmpty()) 0.0 else negatives.map { it.edgeHug }.average()
            )
        )
        val fitDocs = docs.sumOf { it.frameFitFrames }
        val fitNeg = negatives.sumOf { it.frameFitFrames }
        val extraFrames = extras.sumOf { it.frames }
        val extraDetections = extras.sumOf { it.detected }
        sb.append(
            String.format(
                Locale.ROOT,
                "- Extras: %d/%d frames detected; the desk-calendar extra is a false positive by design%n",
                extraDetections, extraFrames
            )
        )
        sb.append(
            String.format(
                Locale.ROOT,
                "- Non-frame-fit quads (a real crop candidate): documents %d frames, no-document %d frames%n",
                docDetections - fitDocs, negDetections - fitNeg
            )
        )
        sb.append(
            String.format(
                Locale.ROOT,
                "- Frame-fit quads (all corners within 5%% of a border): documents %d/%d detected frames, no-document %d/%d%n",
                fitDocs, docDetections, fitNeg, negDetections
            )
        )
        val warps = results.sumOf { it.warpChecked }
        val warpFails = results.sumOf { it.warpFailed }
        sb.append("- Warp spot-check (PhotoAutoCrop.crop on 2 detected frames/clip): $warpFails failure(s) in $warps\n")

        sb.append("\n## Weakest clips (fewest detections on documents, most false positives on negatives)\n\n")
        val worst = (docs.sortedBy { it.detectionPercent }.take(4) +
            negatives.sortedByDescending { it.falsePositivePercent }.take(2))
            .sortedBy { if (it.expectDocument) it.detectionPercent else -it.falsePositivePercent }
            .take(5)
        for (r in worst) {
            sb.append(
                String.format(
                    Locale.ROOT,
                    "- %s (%s): detection %.0f%%, FP %.0f%%, frame-fit %.0f%%, jitter %.4f, area %.1f%%, edge-hug %.3f, %.1f ms median%n",
                    r.key, r.scenario, r.detectionPercent, r.falsePositivePercent, r.frameFitPercent,
                    r.jitter, r.areaPercent, r.edgeHug, r.medianMs                )
            )
            for (frame in r.interesting) sb.append("    - frame: ").append(r.key).append('/').append(frame).append('\n')
        }

        sb.append("\n## Per-clip jitter detail\n\n")
        for (r in results.filter { it.jitter > 0.0 }.sortedByDescending { it.jitter }.take(8)) {
            sb.append(
                String.format(Locale.ROOT, "- %s: jitter %.4f of the diagonal%n", r.key, r.jitter)
            )
        }
        sb.append('\n').append("sqrt(mean squared corner step) is not used: the figure is the plain mean so a\n")
            .append("single jump cannot be hidden.\n")
        return sb.toString()
    }

    private fun shoelace(p: FloatArray): Double {
        var acc = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            acc += p[i * 2].toDouble() * p[j * 2 + 1] - p[j * 2].toDouble() * p[i * 2 + 1]
        }
        return acc / 2.0
    }
}
