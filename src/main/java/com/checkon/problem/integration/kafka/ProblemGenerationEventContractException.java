package com.checkon.problem.integration.kafka;

public class ProblemGenerationEventContractException extends RuntimeException {
	public ProblemGenerationEventContractException(String message) { super(message); }
	public ProblemGenerationEventContractException(String message, Throwable cause) { super(message, cause); }
}
