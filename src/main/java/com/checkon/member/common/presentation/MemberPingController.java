// TODO(PR3): 삭제. PR1 보안 체인 증명용 임시 엔드포인트다.
package com.checkon.member.common.presentation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkon.member.common.security.CurrentMember;
import com.checkon.member.common.security.MemberSubject;

/**
 * member 보안 체인·주체 해석·응답 봉투가 실제로 도는지 확인하는 유일한 엔드포인트다.
 * 계약({@code member-api.yaml})에 없는 경로이므로 PR3 이 실제 API 를 넣을 때 지운다.
 */
@RestController
@RequestMapping("/api/v1/member")
class MemberPingController {

	@GetMapping("/ping")
	MemberResponse<MemberPingResult> ping(@CurrentMember MemberSubject subject) {
		return MemberResponse.of(new MemberPingResult(subject.role().name(), subject.accountId()));
	}
}
