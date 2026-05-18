package com.cragent.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AgentRunResult {
    public String sessionId;
    public String repo;
    public int pr;
    public boolean dryRun;
    public String status;
    public String summary;
    public List<ReviewIssue> issues = new ArrayList<>();
    public List<Map<String, Object>> actions = new ArrayList<>();
    public Path tracePath;
}

