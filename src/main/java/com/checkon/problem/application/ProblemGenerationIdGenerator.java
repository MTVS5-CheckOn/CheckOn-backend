package com.checkon.problem.application;

import java.util.List;
import java.util.UUID;

public interface ProblemGenerationIdGenerator {
	List<UUID> nextIds(int count);
}
