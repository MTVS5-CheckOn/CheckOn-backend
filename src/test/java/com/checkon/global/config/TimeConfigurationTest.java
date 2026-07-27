package com.checkon.global.config;

import static org.assertj.core.api.AssertionsForClassTypes.*;

import java.time.Clock;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(TimeConfiguration.class)
public class TimeConfigurationTest {

	@Autowired
	Clock clock;

	@Test
	void trueTimeZone(){
		assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
	}

}
