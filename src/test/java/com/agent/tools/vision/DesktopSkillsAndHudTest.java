package com.agent.tools.vision;

import com.agent.tools.terminal.security.SecurityInterceptor;
import com.agent.tools.vision.hud.CuaHudOverlay;
import com.agent.tools.vision.model.DesktopSkill;
import com.agent.tools.vision.model.GuiActionStep;
import com.agent.tools.vision.model.WindowInfo;
import com.agent.tools.vision.services.CoordinateConverter;
import com.agent.tools.vision.services.DesktopSkillRegistry;
import com.agent.tools.vision.services.DesktopWindowService;
import com.agent.tools.vision.services.ScreenDiffService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class DesktopSkillsAndHudTest {

    private SecurityInterceptor mockSecurity;
    private Robot mockRobot;
    private ChatLanguageModel mockVisionModel;
    private Clipboard mockClipboard;
    private DesktopWindowService mockDesktopWindowService;
    private ScreenDiffService screenDiffService;
    private DesktopSkillRegistry skillRegistry;
    private CuaHudOverlay hudOverlay;
    private CoordinateConverter converter;
    private Dimension testScreenSize;
    private VisionToolsFacade facade;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        mockSecurity = mock(SecurityInterceptor.class);
        mockRobot = mock(Robot.class);
        mockVisionModel = mock(ChatLanguageModel.class);
        mockClipboard = mock(Clipboard.class);
        testScreenSize = new Dimension(1920, 1080);
        converter = new CoordinateConverter(testScreenSize);
        mockDesktopWindowService = mock(DesktopWindowService.class);
        screenDiffService = new ScreenDiffService();
        skillRegistry = new DesktopSkillRegistry(tempDir);
        hudOverlay = new CuaHudOverlay(false);
        objectMapper = new ObjectMapper();

        when(mockSecurity.requestPermission(anyString(), anyString())).thenReturn(true);

        facade = new VisionToolsFacade(
                mockSecurity,
                mockRobot,
                mockVisionModel,
                testScreenSize,
                mockClipboard,
                mockDesktopWindowService,
                screenDiffService,
                skillRegistry,
                hudOverlay
        );
    }

    @Test
    void testBuiltinSkillsDiscoveryAndCatalogFormatting() {
        List<DesktopSkill> skills = skillRegistry.listSkills();
        assertTrue(skills.size() >= 3, "Expected at least 3 built-in skills, found " + skills.size());

        Optional<DesktopSkill> browserSkill = skillRegistry.getSkill("browser_navigate_url");
        assertTrue(browserSkill.isPresent());
        assertEquals("Browser Navigate URL", browserSkill.get().name());
        assertTrue(browserSkill.get().parameterNames().contains("url"));
        assertTrue(browserSkill.get().steps().size() >= 4);

        Optional<DesktopSkill> notepadSkill = skillRegistry.getSkill("notepad_write_save");
        assertTrue(notepadSkill.isPresent());
        assertEquals("Notepad Write and Save", notepadSkill.get().name());
        assertTrue(notepadSkill.get().parameterNames().contains("content"));

        Optional<DesktopSkill> quickFindSkill = skillRegistry.getSkill("system_quick_find");
        assertTrue(quickFindSkill.isPresent());
        assertEquals("System Quick Find", quickFindSkill.get().name());
        assertTrue(quickFindSkill.get().parameterNames().contains("query"));

        String catalog = facade.listDesktopSkills();
        assertTrue(catalog.contains("Desktop CUA Skills Catalog ("));
        assertTrue(catalog.contains("browser_navigate_url"));
        assertTrue(catalog.contains("notepad_write_save"));
        assertTrue(catalog.contains("system_quick_find"));

        String description = skillRegistry.describeSkill("browser_navigate_url");
        assertTrue(description.contains("Skill: browser_navigate_url"));
        assertTrue(description.contains("Required Parameters: [url]"));
    }

    @Test
    void testSkillParameterSubstitutionAndValidation() {
        DesktopSkill browserSkill = skillRegistry.getSkill("browser_navigate_url").orElseThrow();

        // 1. Valid parameter resolution
        List<GuiActionStep> resolved = browserSkill.resolveSteps(Map.of("url", "https://github.com/agent"));
        assertEquals(browserSkill.steps().size(), resolved.size());

        GuiActionStep typeStep = resolved.stream()
                .filter(s -> "type".equalsIgnoreCase(s.action()))
                .findFirst()
                .orElseThrow();
        assertEquals("https://github.com/agent", typeStep.text());

        // 2. Missing required parameter validation
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            browserSkill.resolveSteps(Map.of());
        });
        assertTrue(ex.getMessage().contains("Missing required parameter 'url'"));

        // 3. Null parameter value validation
        IllegalArgumentException exNull = assertThrows(IllegalArgumentException.class, () -> {
            Map<String, String> nullMap = new java.util.HashMap<>();
            nullMap.put("url", null);
            browserSkill.resolveSteps(nullMap);
        });
        assertTrue(exNull.getMessage().contains("Missing required parameter 'url'"));
    }

    @Test
    void testExecuteDesktopSkillEndToEnd() {
        when(mockDesktopWindowService.focusWindow(anyString())).thenReturn("Brought window to front: Chrome");

        BufferedImage before = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        BufferedImage after = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        after.setRGB(500, 500, Color.BLUE.getRGB());
        when(mockRobot.createScreenCapture(any(Rectangle.class))).thenReturn(before, after);

        String result = facade.executeDesktopSkill("browser_navigate_url", "{\"url\": \"https://news.ycombinator.com\"}");

        // Single security prompt
        verify(mockSecurity, times(1)).requestPermission(eq("execute desktop skill"), contains("browser_navigate_url"));

        assertTrue(result.contains("Desktop skill 'browser_navigate_url' executed successfully"));
        assertTrue(result.contains("Typed text"));
        assertTrue(result.contains("https://news.ycombinator.com"));

        // HUD state should be recorded
        assertEquals("execute_skill:browser_navigate_url", hudOverlay.getLastAction());
        assertTrue(hudOverlay.getUpdateCount() > 0);
    }

    @Test
    void testExecuteDesktopSkillSecurityRejection() {
        when(mockSecurity.requestPermission(eq("execute desktop skill"), anyString())).thenReturn(false);

        String result = facade.executeDesktopSkill("browser_navigate_url", "{\"url\": \"https://example.com\"}");

        assertEquals("ACTION REJECTED: User explicitly denied desktop skill execution. Do not retry this action.", result);
        verify(mockRobot, never()).mouseMove(anyInt(), anyInt());
        verify(mockRobot, never()).keyPress(anyInt());
    }

    @Test
    void testExecuteDesktopSkillErrors() {
        // Unknown skill
        String unknownResult = facade.executeDesktopSkill("non_existent_skill", "{}");
        assertTrue(unknownResult.contains("Error: Skill 'non_existent_skill' not found."));

        // Malformed JSON parameters
        String malformedResult = facade.executeDesktopSkill("browser_navigate_url", "{ invalid json }");
        assertTrue(malformedResult.contains("Error: Failed to parse parametersJson:"));

        // Missing required parameter
        String missingParamResult = facade.executeDesktopSkill("browser_navigate_url", "{}");
        assertTrue(missingParamResult.contains("Error: Missing required parameter 'url'"));
    }

    @Test
    void testSaveAndPersistCustomDesktopSkill(@TempDir Path tempDir) throws Exception {
        DesktopSkillRegistry customRegistry = new DesktopSkillRegistry(tempDir);
        VisionToolsFacade customFacade = new VisionToolsFacade(
                mockSecurity,
                mockRobot,
                mockVisionModel,
                testScreenSize,
                mockClipboard,
                mockDesktopWindowService,
                screenDiffService,
                customRegistry,
                hudOverlay
        );

        String customSkillJson = """
                {
                  "skillId": "custom_editor_open",
                  "name": "Custom Editor Open",
                  "description": "Opens editor and opens a file path",
                  "osPlatform": "ALL",
                  "parameterNames": ["filePath"],
                  "steps": [
                    { "action": "focus_window", "windowQuery": "Code" },
                    { "action": "hotkey", "hotkey": "ctrl+o" },
                    { "action": "type", "text": "{{filePath}}", "pressEnter": true }
                  ]
                }
                """;

        String saveResult = customFacade.saveDesktopSkill(customSkillJson);
        assertTrue(saveResult.contains("Desktop skill 'custom_editor_open' registered and saved successfully."));
        verify(mockSecurity, times(1)).requestPermission(eq("save desktop skill"), contains("custom_editor_open"));

        // Verify persisted to disk
        Path expectedFile = tempDir.resolve("custom_editor_open.json");
        assertTrue(Files.exists(expectedFile), "Expected skill file to be created at: " + expectedFile);

        // Verify retrieval from registry
        Optional<DesktopSkill> retrieved = customRegistry.getSkill("custom_editor_open");
        assertTrue(retrieved.isPresent());
        assertEquals("Custom Editor Open", retrieved.get().name());
        assertEquals(List.of("filePath"), retrieved.get().parameterNames());
        assertEquals(3, retrieved.get().steps().size());

        // Verify execution of the newly saved custom skill
        String execResult = customFacade.executeDesktopSkill("custom_editor_open", "{\"filePath\": \"src/main/App.java\"}");
        assertTrue(execResult.contains("Desktop skill 'custom_editor_open' executed successfully"));
        assertTrue(execResult.contains("src/main/App.java"));
    }

    @Test
    void testSaveDesktopSkillSecurityRejection() {
        when(mockSecurity.requestPermission(eq("save desktop skill"), anyString())).thenReturn(false);

        String skillJson = """
                {
                  "skillId": "blocked_skill",
                  "name": "Blocked Skill",
                  "description": "Will be blocked",
                  "steps": [{ "action": "hotkey", "hotkey": "ctrl+c" }]
                }
                """;

        String result = facade.saveDesktopSkill(skillJson);
        assertEquals("ACTION REJECTED: User explicitly denied saving desktop skill. Do not retry this action.", result);
        assertTrue(skillRegistry.getSkill("blocked_skill").isEmpty());
    }

    @Test
    void testCuaHudOverlayStateAndConfiguration() {
        // Initial state
        assertFalse(hudOverlay.isEnabled());
        assertEquals("IDLE", hudOverlay.getLastAction());
        assertEquals(0, hudOverlay.getUpdateCount());

        // Recording action
        hudOverlay.recordAction("click_coordinates", "(500, 500)", "[VISUAL VERIFICATION: 1.50% screen change]");
        assertEquals("click_coordinates", hudOverlay.getLastAction());
        assertEquals("(500, 500)", hudOverlay.getLastTargetCoord());
        assertTrue(hudOverlay.getLastVerificationStatus().contains("1.50%"));
        assertEquals(1, hudOverlay.getUpdateCount());

        // Configure HUD tool
        String enableRes = facade.configureCuaHud(true);
        assertTrue(enableRes.contains("CUA HUD Overlay configured: ENABLED"));
        assertTrue(hudOverlay.isEnabled());

        String toggleRes = facade.configureCuaHud(null);
        assertTrue(toggleRes.contains("CUA HUD Overlay configured: DISABLED"));
        assertFalse(hudOverlay.isEnabled());

        // Close failsafe
        hudOverlay.close();
        assertFalse(hudOverlay.isEnabled());
    }
}
