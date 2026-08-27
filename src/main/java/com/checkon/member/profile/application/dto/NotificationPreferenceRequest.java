package com.checkon.member.profile.application.dto;

/** {@code PATCH /profile/notification-preference} 요청. */
public record NotificationPreferenceRequest(Boolean enabled) {
}
