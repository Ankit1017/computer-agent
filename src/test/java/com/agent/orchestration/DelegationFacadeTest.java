package com.agent.orchestration;

import com.agent.orchestration.services.OrchestrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DelegationFacadeTest {

    @Test
    @DisplayName("Should delegate delegateTask to OrchestrationService routeTask")
    void shouldDelegateToOrchestrationService() {
        OrchestrationService service = mock(OrchestrationService.class);
        when(service.routeTask("architect", "design system")).thenReturn("Architecture Plan");

        DelegationFacade facade = new DelegationFacade(service);
        String result = facade.delegateTask("architect", "design system");

        assertThat(result).isEqualTo("Architecture Plan");
        verify(service).routeTask("architect", "design system");
    }
}
