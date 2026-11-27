package com.fermapp.api.modules.reports

import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.util.Times
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.domain.model.Role
import com.fermapp.api.modules.reports.ReportService.Range
import com.fermapp.api.persistence.repository.FarmRepository
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import java.time.Instant
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Reports in JSON, PDF or Excel (reports.routes.ts). */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports")
class ReportController(
    private val reports: ReportService,
    private val renderer: ReportRenderer,
    private val farms: FarmRepository,
) {

    @GetMapping("/{type}")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Generate a report in JSON, PDF or Excel")
    fun report(
        auth: AuthContext,
        @PathVariable @Pattern(regexp = TYPES) type: String,
        @RequestParam(defaultValue = "json") @Pattern(regexp = "json|pdf|xlsx") format: String,
        @RequestParam(required = false) @Pattern(regexp = "portrait|landscape") orientation: String?,
        @RequestParam(required = false) @Min(2000) @Max(2100) year: Int?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
    ): ResponseEntity<*> {
        if (auth.role() == Role.OPERATOR && type in ReportService.FINANCIAL_REPORTS) {
            throw Errors.forbidden()
        }
        val target = year ?: Times.currentLocalYear()
        val start = from ?: Times.utc(target, 1, 1, 0)
        val end = to ?: Times.utc(target, 12, 31, 0).plusSeconds((23 * 3600 + 59 * 60 + 59).toLong())
        if (start.isAfter(end)) {
            throw Errors.badRequest("The start date must be before the end date.")
        }
        val report = reports.build(type, auth.farmId(), Range(start, end, target))
        if (format == "json") {
            return ApiResponse.ok(report)
        }
        val farmName = farms.findById(auth.farmId()).orElseThrow { Errors.recordNotFound() }.name
        val filename = "report-$type-$target"
        if (format == "pdf") {
            return ApiResponse.file(renderer.toPdf(report, farmName, orientation), "application/pdf", "$filename.pdf", false)
        }
        return ApiResponse.file(
            renderer.toExcel(report, farmName),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "$filename.xlsx", false,
        )
    }

    private companion object {
        const val TYPES = "costs|profitability|farm|parcels|work|machines|crops|fuel|yields|rotation|irrigation"
    }
}
