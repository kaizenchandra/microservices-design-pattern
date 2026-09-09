package com.synechis.fulfillment.inventoryservice;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "stock")
public class Stock {
    @Id
    public String sku;
    public int available;
    @Version
    public long version;

    protected Stock() {
    }
}
