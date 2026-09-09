package com.synechis.fulfillment.inventoryservice;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.Optional;
public interface StockRepository extends JpaRepository<Stock,String> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from Stock s where s.sku=:sku") Optional<Stock> locked(String sku);
}
