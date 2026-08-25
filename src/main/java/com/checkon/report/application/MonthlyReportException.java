package com.checkon.report.application;

public final class MonthlyReportException extends RuntimeException {
	private final String code;
	private final int status;
	private MonthlyReportException(String code, int status, String message) { super(message); this.code=code; this.status=status; }
	public static MonthlyReportException invalid(String message) { return new MonthlyReportException("INVALID_REQUEST",400,message); }
	public static MonthlyReportException notFound() { return new MonthlyReportException("REPORT_NOT_FOUND",404,"Monthly report was not found"); }
	public static MonthlyReportException conflict(String message) { return new MonthlyReportException("REPORT_CONFLICT",409,message); }
	public String code() { return code; }
	public int status() { return status; }
}
