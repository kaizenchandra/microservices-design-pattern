package com.synechis.fulfillment.shippingservice;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class RecoveryController {
    private final ShippingParticipant participant;

    public RecoveryController(ShippingParticipant participant) {
        this.participant = participant;
    }

    @PostMapping("/admin/operations/{id}/retry")
    void retry(@PathVariable UUID id) {
        participant.retry(id);
    }
}
