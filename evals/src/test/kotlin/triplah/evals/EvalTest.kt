package triplah.evals

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvalTest {
    private val root = File(System.getProperty("triplah.root") ?: "..")

    @Test
    fun `all 30 eval cases pass and meet the speed target`() {
        val runner = EvalRunner(root)
        assertEquals(30, runner.file.cases.size)
        assertEquals(listOf(10, 10, 10), runner.file.cases.groupBy { it.painPoint }.toSortedMap().values.map { it.size })
        val results = runner.run()
        File(root, "evals/report.md").writeText(runner.report(results))
        val failed = results.filter { !it.passed }
        assertTrue(failed.isEmpty(), failed.joinToString("\n") { "${it.case.id}: ${it.failures}" })
        results.groupBy { it.mode }.forEach { (mode, rs) ->
            val p95 = EvalRunner.pct(rs.map { it.millis }.sorted(), 0.95)
            assertTrue(p95 < 1000, "$mode p95 $p95 ms is over the 1 s seed-data target")
        }
    }
}
