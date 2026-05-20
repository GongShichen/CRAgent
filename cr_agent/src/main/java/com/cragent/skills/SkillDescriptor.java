package com.cragent.skills;

import java.util.List;
import java.util.Map;

public record SkillDescriptor(
        String name,
        String description,
        List<String> languages,
        List<String> filePatterns,
        List<String> riskTriggers,
        List<String> modes
) {
    public Map<String, Object> toMap() {
        return Map.of(
                "name", name,
                "description", description,
                "languages", languages,
                "file_patterns", filePatterns,
                "risk_triggers", riskTriggers,
                "modes", modes
        );
    }
}
