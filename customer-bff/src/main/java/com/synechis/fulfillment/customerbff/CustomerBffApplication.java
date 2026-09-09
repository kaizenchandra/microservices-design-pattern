package com.synechis.fulfillment.customerbff;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages={"com.synechis.fulfillment.customerbff","com.synechis.fulfillment.runtime"})
public class CustomerBffApplication { public static void main(String[] args){SpringApplication.run(CustomerBffApplication.class,args);} }
