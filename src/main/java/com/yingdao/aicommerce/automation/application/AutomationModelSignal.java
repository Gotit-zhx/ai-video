package com.yingdao.aicommerce.automation.application;

public record AutomationModelSignal(
        String commandId,
        String kind,
        String trigger
) {
}
