package com.cragent.model;

import java.util.ArrayList;
import java.util.List;

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
    public double candidateScore = 0.0;
    public String validationVerdict = "UNVERIFIED";
    public String validationReason;
    public Integer correctedLine;
    public List<String> riskProbeIds = new ArrayList<>();

    public ReviewIssue() {
    }
}
