package io.capture.agent.fixtures;

import java.io.Serializable;

public class Customer implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private String email;
    private LoyaltyTier loyaltyTier = LoyaltyTier.NONE;
    private Address address;

    public Customer() {
    }

    public Customer(String name, String email, LoyaltyTier loyaltyTier, Address address) {
        this.name = name;
        this.email = email;
        this.loyaltyTier = loyaltyTier;
        this.address = address;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public LoyaltyTier getLoyaltyTier() {
        return loyaltyTier;
    }

    public void setLoyaltyTier(LoyaltyTier loyaltyTier) {
        this.loyaltyTier = loyaltyTier;
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }
}
