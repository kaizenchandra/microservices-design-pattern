package com.synechis.fulfillment.orderservice;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class OrderAggregateTest {
 @Test void upcastsHistoricalEvents(){var a=new OrderAggregate();a.apply("Placed",0,Map.of("customerId","alice"));assertThat(a.data).containsEntry("customer","alice");assertThat(a.version).isEqualTo(1);}
 @Test void confirmationCannotFollowAbort(){var a=new OrderAggregate();a.apply("Placed",1,Map.of());a.apply("Aborting",1,Map.of("reason","CANCELLED"));assertThatThrownBy(()->a.apply("Confirmed",1,Map.of())).isInstanceOf(IllegalStateException.class);}
 @Test void unknownSchemasFailClosed(){assertThatThrownBy(()->new OrderAggregate().apply("Placed",99,Map.of())).isInstanceOf(IllegalArgumentException.class);}
}
