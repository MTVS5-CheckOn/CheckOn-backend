package com.checkon.report.presentation;

import java.net.URI; import java.time.YearMonth; import java.util.List; import java.util.UUID;
import org.springframework.http.ResponseEntity; import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.checkon.account.infrastructure.security.AuthenticatedAccount; import com.checkon.report.application.MonthlyReportService;
import jakarta.validation.Valid; import jakarta.validation.constraints.*;

@RestController @RequestMapping("/api/v1/report-studio")
public class MonthlyReportController {
	private final MonthlyReportService reports; public MonthlyReportController(MonthlyReportService reports){this.reports=reports;}
	@PostMapping("/reports") public ResponseEntity<?> create(@AuthenticationPrincipal AuthenticatedAccount principal,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CreateRequest request){
		var result=reports.create(teacher(principal),request.studentId(),request.reportMonth(),request.reportKind(),key);
		return ResponseEntity.accepted().location(URI.create("/api/v1/report-studio/reports/"+result.reportId())).body(result); }
	@GetMapping("/reports") public List<?> list(@AuthenticationPrincipal AuthenticatedAccount principal,@RequestParam(required=false) YearMonth month,
		@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return reports.list(teacher(principal),month,status,query,page,size);}
	@GetMapping("/reports/{reportId}") public Object get(@AuthenticationPrincipal AuthenticatedAccount principal,@PathVariable UUID reportId){return reports.get(teacher(principal),reportId);}
	@PostMapping("/reports/{reportId}/artifacts") public Object artifact(@AuthenticationPrincipal AuthenticatedAccount principal,@PathVariable UUID reportId,@Valid @RequestBody ArtifactRequest request){return reports.registerArtifact(teacher(principal),reportId,request.revisionNo(),request.storageKey(),request.sha256(),request.pageCount());}
	@PatchMapping("/reports/{reportId}/blocks/{blockId}") public Object updateBlock(@AuthenticationPrincipal AuthenticatedAccount principal,@PathVariable UUID reportId,@PathVariable String blockId,@Valid @RequestBody UpdateBlockRequest request){return reports.updateBlock(teacher(principal),reportId,blockId,request.baseRevisionNo(),request.content());}
	@PostMapping("/reports/{reportId}/blocks/{blockId}/restore") public Object restoreBlock(@AuthenticationPrincipal AuthenticatedAccount principal,@PathVariable UUID reportId,@PathVariable String blockId,@Valid @RequestBody RestoreBlockRequest request){return reports.restoreBlock(teacher(principal),reportId,blockId,request.baseRevisionNo(),request.revertToRevisionNo());}
	@PostMapping("/deliveries:bulk") public Object deliver(@AuthenticationPrincipal AuthenticatedAccount principal,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody DeliveryRequest request){return reports.queueDeliveries(teacher(principal),request.reportIds(),key);}
	public record CreateRequest(@NotNull UUID studentId,@NotNull YearMonth reportMonth,@Size(max=20) String reportKind){}
	public record ArtifactRequest(@Min(0) int revisionNo,@NotBlank @Size(max=500) String storageKey,@NotBlank String sha256,@Min(1)@Max(100) int pageCount){}
	public record UpdateBlockRequest(@Min(0) int baseRevisionNo,@NotBlank @Size(max=10000) String content){}
	public record RestoreBlockRequest(@Min(0) int baseRevisionNo,@Min(0) int revertToRevisionNo){}
	public record DeliveryRequest(@NotEmpty @Size(max=100) List<@NotNull UUID> reportIds){}
	private static UUID teacher(AuthenticatedAccount principal){return principal==null?null:principal.teacherProfileId();}
}
