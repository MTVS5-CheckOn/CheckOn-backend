package com.checkon.member.report.presentation;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.member.report.application.ReportFileDownloadService;
import com.checkon.member.report.application.ReportFileDownloadService.Download;

/**
 * 서명 URL 의 착지점. 🔴 <b>이 경로에는 세션이 없다</b> —
 * {@code MemberSecurityConfiguration} 에서 {@code permitAll} 이다.
 *
 * <p>🔴 <b>왜 permitAll 인가</b> — 브라우저의 PDF 뷰어와 새 탭 열기는
 * {@code Authorization} 헤더를 붙일 방법이 없다. 헤더를 요구하면 학부모가 PDF 를 볼 수 없다.
 * 그래서 인증을 URL 안으로 옮겼고, 보안은 <b>HMAC 서명 + 짧은 TTL + 다운로드 시점 관계
 * 재검증</b> 셋이 진다({@link ReportFileDownloadService}).</p>
 *
 * <p>🔴 {@code @CurrentMember} 를 받지 않는다. 주체는 <b>토큰 안에만</b> 있고, 서명이 검증된
 * 값만 DB 컨텍스트가 된다. 쿼리 파라미터의 어떤 값도 컨텍스트가 되지 않는다.</p>
 *
 * <p>🔴 만료·위조·관계종료·부재를 전부 {@code 404} 로 통일한다. 구분하면 토큰 유효성을
 * 탐색당한다(분기표 §4).</p>
 */
@RestController
@RequestMapping("/api/v1/member/files/reports")
public class MemberReportFileController {

	private final ReportFileDownloadService downloadService;

	public MemberReportFileController(ReportFileDownloadService downloadService) {
		this.downloadService = downloadService;
	}

	@GetMapping("/{token}")
	public ResponseEntity<InputStreamResource> download(@PathVariable String token) {
		Download file = downloadService.open(token);
		// 🔴 inline — 학부모는 앱 안에서 바로 본다. 파일명에 실명·별칭을 넣지 않는다.
		ContentDisposition disposition = ContentDisposition.inline()
			.filename(file.downloadName())
			.build();
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
			.contentType(MediaType.parseMediaType(file.contentType()))
			.contentLength(file.sizeBytes())
			.body(new InputStreamResource(file.body()));
	}
}
