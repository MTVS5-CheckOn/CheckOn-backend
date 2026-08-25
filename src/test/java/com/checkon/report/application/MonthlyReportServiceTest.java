package com.checkon.report.application;
import static org.assertj.core.api.Assertions.*; import static org.mockito.ArgumentMatchers.*; import static org.mockito.Mockito.*;
import java.time.*; import java.util.*; import org.junit.jupiter.api.*;
import com.checkon.counsel.application.AiGuardianAliasService; import com.checkon.detection.infrastructure.kafka.AiTenantAliasService;
import com.checkon.report.infrastructure.MonthlyReportRepository; import com.checkon.report.infrastructure.MonthlyReportRepository.*; import tools.jackson.databind.json.JsonMapper;
import com.checkon.report.integration.ai.MonthlyReportRevisionClient;
class MonthlyReportServiceTest {
	private final MonthlyReportRepository repository=mock(MonthlyReportRepository.class); private final MonthlyReportIdGenerator ids=mock(MonthlyReportIdGenerator.class);
	private final AiGuardianAliasService guardians=mock(AiGuardianAliasService.class); private final AiTenantAliasService tenants=mock(AiTenantAliasService.class);
	private final MonthlyReportRevisionClient revisions=mock(MonthlyReportRevisionClient.class);
	private final UUID teacher=UUID.randomUUID(),student=UUID.randomUUID(),report=UUID.randomUUID(),event=UUID.randomUUID();
	private MonthlyReportService service;
	@BeforeEach void setUp(){service=new MonthlyReportService(repository,ids,guardians,tenants,JsonMapper.builder().findAndAddModules().build(),Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"),ZoneOffset.UTC),revisions);}
	@Test @DisplayName("Given 생성된 진단과 채점 결과가 있을 때 When 월간 리포트를 요청하면 Then 가명 source와 Outbox를 원자 저장한다")
	void givenEvidence_whenCreating_thenPersistsReportAndOutbox(){
		when(repository.findStudentContext(teacher,student)).thenReturn(Optional.of(new StudentContext(student,"김학생",null,null,"pa_"+"a".repeat(32),null)));
		when(guardians.getOrCreate(teacher,student)).thenReturn("pa_"+"a".repeat(32)); when(tenants.getOrCreate(teacher)).thenReturn("tn_"+"b".repeat(32));
		when(repository.latestDiagnosis(eq(teacher),eq(student),any())).thenReturn(Optional.of(new Diagnosis("{\"data\":{\"weakness_map\":{},\"misconceptions\":[]}}","sha256:"+"c".repeat(64))));
		when(repository.itemResults(eq(teacher),eq(student),any(),any())).thenReturn(List.of(new ItemResult(UUID.randomUUID(),"language","concept","node.1",2,1,false,"misconception",Instant.parse("2026-08-10T00:00:00Z"))));
		when(repository.findByClientKey(teacher,"report-key-001")).thenReturn(Optional.empty()); when(ids.nextIds(2)).thenReturn(List.of(report,event));
		var result=service.create(teacher,student,YearMonth.of(2026,8),"MONTHLY","report-key-001");
		assertThat(result.reportId()).isEqualTo(report); assertThat(result.replayed()).isFalse(); verify(repository).insert(argThat(saved->saved.source().contains("\"item_results\"")&&saved.source().contains("\"metrics\"")),eq(event),contains("monthly-report-request-1"));
	}
	@Test @DisplayName("Given 같은 멱등키의 다른 스냅샷이 있을 때 When 재요청하면 Then 중복 AI 호출을 거절한다")
	void givenDifferentSnapshot_whenReplaying_thenRejects(){
		when(repository.findStudentContext(teacher,student)).thenReturn(Optional.of(new StudentContext(student,"김학생",null,null,"pa_"+"a".repeat(32),null)));when(guardians.getOrCreate(any(),any())).thenReturn("pa_"+"a".repeat(32));
		when(repository.latestDiagnosis(any(),any(),any())).thenReturn(Optional.of(new Diagnosis("{\"data\":{\"weakness_map\":{}}}","sha256:"+"c".repeat(64))));when(repository.itemResults(any(),any(),any(),any())).thenReturn(List.of(new ItemResult(UUID.randomUUID(),"language","concept","node.1",1,1,true,null,Instant.now())));
		when(repository.findByClientKey(teacher,"report-key-001")).thenReturn(Optional.of(new ReportRow(report,"sha256:"+"f".repeat(64),"REQUESTED")));
		assertThatThrownBy(()->service.create(teacher,student,YearMonth.of(2026,8),"MONTHLY","report-key-001")).isInstanceOf(MonthlyReportException.class).hasMessageContaining("different report snapshot");
	}
	@Test @DisplayName("Given 수신자와 PDF가 없는 리포트 When 일괄 발송하면 Then 해당 행만 거절 결과로 반환한다")
	void givenMissingRecipientAndPdf_whenBulkDelivery_thenReturnsPartialFailure(){
		when(ids.nextIds(1)).thenReturn(List.of(UUID.randomUUID())); when(repository.deliveryTarget(teacher,report)).thenReturn(new DeliveryTarget(report,"ready",null,null,null));
		var results=service.queueDeliveries(teacher,List.of(report),"delivery-key-001"); assertThat(results).singleElement().satisfies(value->{assertThat(value.status()).isEqualTo("REJECTED");assertThat(value.reason()).contains("parent");});
	}
}
