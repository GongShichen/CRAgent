package com.cragent.model;

import java.util.ArrayList;
import java.util.List;

public class ReviewResult {
    public String summary = "";
    public List<ReviewIssue> issues = new ArrayList<>();
    public boolean shouldComment = true;
    public boolean shouldCreateFixPr;
    public boolean shouldUpdateMemory = true;

    public ReviewResult() {
    }
}

