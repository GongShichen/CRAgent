package com.cragent.model;

public class ReviewIssue {
    public Severity severity = Severity.medium;
    public String category = "general";
    public String file;
    public Integer line;
    public String body;
    public String evidence;
    public String impact;
    public String suggestion;
    public boolean autoFixable;
    public String fixCode;
    public double confidence = 0.5;

    public ReviewIssue() {
    }
}
