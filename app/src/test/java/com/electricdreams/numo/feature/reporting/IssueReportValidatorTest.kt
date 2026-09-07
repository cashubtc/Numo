package com.electricdreams.numo.feature.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportValidatorTest {
    private val validInput = IssueReportInput(
        description = "Scanner closes unexpectedly",
        appVersion = "1.8",
        appBuild = "23",
        platform = "Android",
        osVersion = "16",
        deviceModel = "Pixel 9"
    )

    @Test
    fun `given surrounding whitespace, validation returns trimmed values`() {
        val result = IssueReportValidator.validate(
            validInput.copy(description = "  Scanner closes unexpectedly  ")
        )

        assertTrue(result is IssueReportValidationResult.Valid)
        assertEquals(
            "Scanner closes unexpectedly",
            (result as IssueReportValidationResult.Valid).input.description
        )
    }

    @Test
    fun `given whitespace description, validation reports required`() {
        val result = IssueReportValidator.validate(validInput.copy(description = " \n "))

        assertEquals(
            IssueReportValidationResult.Invalid(
                IssueReportValidationResult.Field.DESCRIPTION,
                IssueReportValidationResult.Reason.REQUIRED
            ),
            result
        )
    }

    @Test
    fun `given multibyte description over byte limit, validation reports too large`() {
        val result = IssueReportValidator.validate(
            validInput.copy(description = "😀".repeat(2_001))
        )

        assertEquals(
            IssueReportValidationResult.Invalid(
                IssueReportValidationResult.Field.DESCRIPTION,
                IssueReportValidationResult.Reason.TOO_LARGE
            ),
            result
        )
    }
}
