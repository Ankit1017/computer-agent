package com.agent.tools.terminal.security;

import java.util.Objects;
import java.util.Scanner;

/**
 * Human-In-The-Loop (HITL) security gate intercepting destructive or mutating agent actions.
 */
public class SecurityInterceptor {

    private final Scanner scanner;

    public SecurityInterceptor(Scanner scanner) {
        this.scanner = Objects.requireNonNull(scanner, "scanner must not be null");
    }

    /**
     * Intercepts an agent action and requests interactive console confirmation from the user.
     *
     * @param actionName name or type of action (e.g. "execute command", "write file at")
     * @param details    contextual details (e.g. command string or file path)
     * @return true if approved by the user with 'Y', false otherwise
     */
    public boolean requestPermission(String actionName, String details) {
        System.out.println("\n[SECURITY] Agent requested to " + actionName + ": " + details);
        System.out.print("Allow? (Y/N): ");
        System.out.flush();

        String response = "";
        if (scanner.hasNextLine()) {
            response = scanner.nextLine().trim();
        }

        boolean approved = "Y".equalsIgnoreCase(response);
        if (approved) {
            System.out.println("[SECURITY] " + actionName + " approved. Executing...");
        } else {
            System.out.println("[SECURITY] " + actionName + " denied by user.");
        }
        return approved;
    }
}
