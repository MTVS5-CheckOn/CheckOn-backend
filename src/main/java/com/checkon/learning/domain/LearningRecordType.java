package com.checkon.learning.domain;

public enum LearningRecordType {
	SOLVE,
	SUBMIT;

	public String aiValue() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}
