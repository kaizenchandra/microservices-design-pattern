package com.synechis.fulfillment.paymentservice;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
public class RecoveryController {private final PaymentParticipant participant;public RecoveryController(PaymentParticipant participant){this.participant=participant;}
 @PostMapping("/admin/operations/{id}/retry") void retry(@PathVariable UUID id){participant.retry(id);}
}
