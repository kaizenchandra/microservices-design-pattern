package com.synechis.fulfillment.inventoryservice;
import jakarta.persistence.*;
@Entity @Table(name="stock")
public class Stock {
 @Id public String sku;
 public int available;
 @Version public long version;
 protected Stock(){}
}
