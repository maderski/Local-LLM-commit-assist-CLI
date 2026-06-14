package com.maderskitech.llmcommit

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptCompactorTest {
    @Test
    fun compactDiffToTokenBudget_returnsDiffUnchangedWhenItFitsWithinBudget() {
        val diff = "diff --git a/file.txt b/file.txt\n@@ -1 +1 @@\n-old\n+new"
        val result = PromptCompactor.compactDiffToTokenBudget(diff, 10_000)
        assertEquals(diff, result)
    }

    @Test
    fun compactDiffToTokenBudget_appendsTruncationNoticeForSingleOversizedSection() {
        val diff = buildString {
            append("diff --git a/large.txt b/large.txt\n")
            append("@@ -1 +1 @@\n")
            repeat(3_000) { index -> append("+line $index with content\n") }
        }
        val result = PromptCompactor.compactDiffToTokenBudget(diff, 100)

        assertTrue(result.startsWith("[diff truncated to fit model context:"))
        assertFalse(result.contains("file patch(es)"))
        assertTrue(result.length <= 250)
    }

    @Test
    fun compactDiffToTokenBudget_appendsFileCountNoticeForMultiSectionDiff() {
        val diff = buildString {
            repeat(5) { fileIndex ->
                append("diff --git a/file$fileIndex.txt b/file$fileIndex.txt\n")
                append("@@ -1 +1 @@\n")
                repeat(500) { lineIndex -> append("+content $fileIndex.$lineIndex\n") }
            }
        }
        val result = PromptCompactor.compactDiffToTokenBudget(diff, 200)

        assertContains(result, "[diff truncated to fit model context:")
        assertContains(result, "file patch(es)")
        assertContains(result, "5")
    }
}
