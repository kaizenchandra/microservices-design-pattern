# Integration contract

See envelope-v1.schema.json. Integration events use the order ID as Kafka key. Schema version 1 requires all envelope fields, including empty causationId for a root command and a traceContext object. Monetary amounts are JSON numbers decoded as BigDecimal in commands; never use binary floating-point arithmetic for payment decisions.

| Producer | Events | Business consumers |
|---|---|---|
| Order | OrderPlaced | Inventory |
| Inventory | InventoryReserved | Payment, Order |
| Payment | PaymentAuthorized | Shipping, Order |
| Shipping | ShipmentBooked | Inventory, Order |
| Inventory | InventoryCommitted | Order |
| Inventory / Payment / Shipping | InventoryRejected, InventoryExpired / PaymentRejected / ShipmentRejected | Order |
| Order | OrderAborting | Inventory, Payment, Shipping |
| Inventory / Payment / Shipping | InventoryReleased / PaymentVoided / ShipmentCancelled | Order |
| Payment / Shipping | RecoveryRequired | Order |
| Order | OrderState | Query |
| Order | OrderConfirmed | External notification consumers (none required locally) |

Payload carries the order's immutable SKU, quantity, monetary amount, currency, customer subject and simulator modes. OrderState additionally carries orderId, status, version and progress facts; abort carries reason. Participants pass these integration facts without sharing Java domain models. Versions from different producers are not comparable. Consumer groups subscribe to one topic and ignore event types they do not own.

Compatibility: additive optional fields may remain schemaVersion 1; meaning changes, required field changes or removals require a new schema/topic and explicit consumer migration. Consumers must deploy readers before producers. Unknown schema versions quarantine and block rather than silently discard. Internal Order event schemas evolve through upcasters independently of this envelope. Stable IDs are required when re-delivering the same logical event; a corrected replacement after operator review must have a new eventId and preserve the intended aggregate version. Conflicting contents for the same OrderState version are a poison event requiring investigation.
