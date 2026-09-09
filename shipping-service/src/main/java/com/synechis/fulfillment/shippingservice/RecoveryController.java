package com.synechis.fulfillment.shippingservice;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
public class RecoveryController {private final ShippingParticipant participant;public RecoveryController(ShippingParticipant participant){this.participant=participant;}
 @PostMapping("/admin/operations/{id}/retry") void retry(@PathVariable UUID id){participant.retry(id);}
}
