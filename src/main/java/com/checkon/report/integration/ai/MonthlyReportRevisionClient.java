package com.checkon.report.integration.ai;
import java.util.UUID; public interface MonthlyReportRevisionClient { String update(String tenantAlias,UUID reportId,String blockId,int baseRevisionNo,String content); String restore(String tenantAlias,UUID reportId,String blockId,int baseRevisionNo,int revertToRevisionNo,String teacherRef); }
