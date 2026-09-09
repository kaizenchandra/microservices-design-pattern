package com.synechis.fulfillment.providersimulator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages={"com.synechis.fulfillment.providersimulator","com.synechis.fulfillment.runtime"})
public class ProviderSimulatorApplication { public static void main(String[] args){SpringApplication.run(ProviderSimulatorApplication.class,args);} }
