package com.agent.tools.vision.services;

import com.agent.tools.vision.model.DesktopSkill;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry and discovery manager for built-in and runtime-custom Desktop CUA Skills.
 */
public class DesktopSkillRegistry {

    private static final List<String> BUILTIN_SKILL_RESOURCES = List.of(
            "browser_navigate_url.json",
            "notepad_write_save.json",
            "system_quick_find.json"
    );

    private final Path customSkillsDir;
    private final ObjectMapper objectMapper;
    private final Map<String, DesktopSkill> skills = new ConcurrentHashMap<>();

    public DesktopSkillRegistry() {
        this(Path.of("skills"));
    }

    public DesktopSkillRegistry(Path customSkillsDir) {
        this.customSkillsDir = customSkillsDir != null ? customSkillsDir : Path.of("skills");
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        loadBuiltinSkills();
        loadCustomSkills();
    }

    private void loadBuiltinSkills() {
        // 1. Load from Classpath
        for (String resourceName : BUILTIN_SKILL_RESOURCES) {
            String path = "/skills/" + resourceName;
            try (InputStream is = getClass().getResourceAsStream(path)) {
                if (is != null) {
                    DesktopSkill skill = objectMapper.readValue(is, DesktopSkill.class);
                    if (skill != null && skill.skillId() != null) {
                        skills.put(skill.skillId(), skill);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 2. Fallback to src/main/resources/skills if running directly from working directory
        Path devSkillsPath = Path.of("src", "main", "resources", "skills");
        if (Files.isDirectory(devSkillsPath)) {
            scanDirectory(devSkillsPath);
        }
    }

    private void loadCustomSkills() {
        if (Files.isDirectory(customSkillsDir)) {
            scanDirectory(customSkillsDir);
        }
    }

    private void scanDirectory(Path directory) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : stream) {
                try {
                    DesktopSkill skill = objectMapper.readValue(file.toFile(), DesktopSkill.class);
                    if (skill != null && skill.skillId() != null) {
                        skills.put(skill.skillId(), skill);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Registers a skill in memory, optionally persisting it to the custom skills directory.
     *
     * @param skill The desktop skill to register
     * @param persistToDisk If true, saves the skill manifest as JSON under customSkillsDir/<skillId>.json
     */
    public void registerSkill(DesktopSkill skill, boolean persistToDisk) {
        if (skill == null) {
            throw new IllegalArgumentException("Skill cannot be null");
        }
        if (skill.skillId() == null || skill.skillId().isBlank()) {
            throw new IllegalArgumentException("Skill ID cannot be empty");
        }
        if (skill.steps() == null || skill.steps().isEmpty()) {
            throw new IllegalArgumentException("Skill must contain at least one step");
        }

        skills.put(skill.skillId(), skill);

        if (persistToDisk) {
            try {
                if (!Files.exists(customSkillsDir)) {
                    Files.createDirectories(customSkillsDir);
                }
                Path target = customSkillsDir.resolve(skill.skillId() + ".json");
                objectMapper.writeValue(target.toFile(), skill);
            } catch (IOException e) {
                throw new RuntimeException("Failed to persist desktop skill '" + skill.skillId() + "' to disk: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Retrieves a skill by ID.
     */
    public Optional<DesktopSkill> getSkill(String skillId) {
        if (skillId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(skills.get(skillId));
    }

    /**
     * Returns an unmodifiable list of all registered skills sorted by ID.
     */
    public List<DesktopSkill> listSkills() {
        return skills.values().stream()
                .sorted(Comparator.comparing(DesktopSkill::skillId))
                .toList();
    }

    /**
     * Returns a formatted text catalog of all available skills.
     */
    public String formatCatalog() {
        List<DesktopSkill> all = listSkills();
        if (all.isEmpty()) {
            return "No desktop CUA skills currently registered.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Desktop CUA Skills Catalog (").append(all.size()).append(" available):\n");
        for (DesktopSkill skill : all) {
            sb.append("- ").append(skill.skillId()).append(": \"").append(skill.name()).append("\" [OS: ").append(skill.osPlatform()).append("]\n");
            sb.append("  Description: ").append(skill.description()).append("\n");
            sb.append("  Parameters: ").append(skill.parameterNames()).append("\n");
            sb.append("  Steps: ").append(skill.steps().size()).append(" action(s)\n");
        }
        return sb.toString().trim();
    }

    /**
     * Returns a detailed description of a specific skill.
     */
    public String describeSkill(String skillId) {
        Optional<DesktopSkill> opt = getSkill(skillId);
        if (opt.isEmpty()) {
            return "Skill not found: '" + skillId + "'.";
        }
        DesktopSkill s = opt.get();
        return String.format("Skill: %s (\"%s\")\nPlatform: %s\nDescription: %s\nRequired Parameters: %s\nStep Count: %d",
                s.skillId(), s.name(), s.osPlatform(), s.description(), s.parameterNames(), s.steps().size());
    }

    public Path getCustomSkillsDir() {
        return customSkillsDir;
    }
}
