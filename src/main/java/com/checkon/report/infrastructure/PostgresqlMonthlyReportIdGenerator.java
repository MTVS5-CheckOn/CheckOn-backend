package com.checkon.report.infrastructure;
import java.util.List; import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient; import org.springframework.stereotype.Component;
import com.checkon.report.application.MonthlyReportIdGenerator;
@Component public class PostgresqlMonthlyReportIdGenerator implements MonthlyReportIdGenerator {
	private final JdbcClient jdbc; public PostgresqlMonthlyReportIdGenerator(JdbcClient jdbc){this.jdbc=jdbc;}
	public List<UUID> nextIds(int count){ if(count<1||count>100)throw new IllegalArgumentException("count must be 1..100");
		return jdbc.sql("SELECT uuidv7() FROM generate_series(1,:count)").param("count",count).query(UUID.class).list(); }
}
