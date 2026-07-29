package com.checkon.detection.application;

import java.util.List;
import java.util.UUID;

public interface DetectionIdGenerator {

	List<UUID> nextIds(int count);
}
