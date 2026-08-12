package com.yingdao.aicommerce.automation.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationCommandTest {

    @Test
    void pauseAndResumeKeepTheSameInputSnapshot() {
        String input = "{\"parameters\":{\"startDate\":\"2026-07-01\"}}";
        AutomationCommand command = command(input);

        command.claim();
        command.requestControl("PAUSE");
        command.acknowledgeControl("PAUSE");

        assertThat(command.getStatus()).isEqualTo("PAUSED");
        assertThat(command.getInputPayload()).isEqualTo(input);

        command.resume();

        assertThat(command.getStatus()).isEqualTo("QUEUED");
        assertThat(command.getInputPayload()).isEqualTo(input);
    }

    @Test
    void cancellationIsTerminal() {
        AutomationCommand command = command("{}");
        command.claim();
        command.requestControl("CANCEL");
        command.acknowledgeControl("CANCEL");

        assertThat(command.getStatus()).isEqualTo("CANCELLED");
        assertThat(command.getCompletedAt()).isNotNull();
    }

    private AutomationCommand command(String input) {
        return new AutomationCommand(
                "organization", "agent", "session",
                "YUE_BILL_EXPORT", "YUE_BILL_EXPORT",
                input, "user"
        );
    }
}
