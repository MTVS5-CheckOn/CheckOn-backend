package com.checkon.report.application;
import java.util.List; import java.util.UUID;
public interface MonthlyReportIdGenerator { List<UUID> nextIds(int count); }
