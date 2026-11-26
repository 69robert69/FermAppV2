package com.fermapp.api.modules.reports;

import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.domain.model.Role;
import com.fermapp.api.domain.report.ReportTable;
import com.fermapp.api.modules.reports.ReportService.Range;
import com.fermapp.api.persistence.repository.FarmRepository;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reports in JSON, PDF or Excel (reports.routes.ts). */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports")
public class ReportController {

    private static final String TYPES = "costs|profitability|farm|parcels|work|machines|crops|fuel|yields|rotation|irrigation";

    private final ReportService reports;
    private final ReportRenderer renderer;
    private final FarmRepository farms;

    public ReportController(ReportService reports, ReportRenderer renderer, FarmRepository farms) {
        this.reports = reports;
        this.renderer = renderer;
        this.farms = farms;
    }

    @GetMapping("/{type}")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Generate a report in JSON, PDF or Excel")
    public ResponseEntity<?> report(AuthContext auth,
                                    @PathVariable @Pattern(regexp = TYPES) String type,
                                    @RequestParam(defaultValue = "json") @Pattern(regexp = "json|pdf|xlsx") String format,
                                    @RequestParam(required = false) @Pattern(regexp = "portrait|landscape") String orientation,
                                    @RequestParam(required = false) @Min(2000) @Max(2100) Integer year,
                                    @RequestParam(required = false) Instant from,
                                    @RequestParam(required = false) Instant to) {
        if (auth.role() == Role.OPERATOR && ReportService.FINANCIAL_REPORTS.contains(type)) {
            throw Errors.forbidden();
        }
        int target = year != null ? year : Times.currentLocalYear();
        Instant start = from != null ? from : Times.utc(target, 1, 1, 0);
        Instant end = to != null ? to : Times.utc(target, 12, 31, 0).plusSeconds(23 * 3600 + 59 * 60 + 59);
        if (start.isAfter(end)) {
            throw Errors.badRequest("The start date must be before the end date.");
        }
        ReportTable report = reports.build(type, auth.farmId(), new Range(start, end, target));
        if ("json".equals(format)) {
            return ApiResponse.ok(report);
        }
        String farmName = farms.findById(auth.farmId()).orElseThrow(Errors::recordNotFound).getName();
        String filename = "report-" + type + "-" + target;
        if ("pdf".equals(format)) {
            return ApiResponse.file(renderer.toPdf(report, farmName, orientation), "application/pdf", filename + ".pdf", false);
        }
        return ApiResponse.file(renderer.toExcel(report, farmName),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", filename + ".xlsx", false);
    }
}
