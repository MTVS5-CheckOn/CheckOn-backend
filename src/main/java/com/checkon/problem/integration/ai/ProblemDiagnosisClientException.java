package com.checkon.problem.integration.ai;

public class ProblemDiagnosisClientException extends RuntimeException {
	private final Integer httpStatus;

	private ProblemDiagnosisClientException(String message, Integer httpStatus, Throwable cause) {
		super(message, cause);
		this.httpStatus = httpStatus;
	}

	public static ProblemDiagnosisClientException http(int status, Throwable cause) {
		return new ProblemDiagnosisClientException("problem diagnosis adapter returned HTTP " + status, status, cause);
	}

	public static ProblemDiagnosisClientException network(Throwable cause) {
		return new ProblemDiagnosisClientException("problem diagnosis adapter is unavailable", null, cause);
	}

	public Integer httpStatus() { return httpStatus; }
}
