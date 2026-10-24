package com.acme.tax.api;

import javax.ejb.EJBObject;
import java.math.BigDecimal;
import java.rmi.RemoteException;

/** EJB 2.x remote component interface. */
public interface RateLookup extends EJBObject {

    BigDecimal rateFor(String country, String region) throws RemoteException;
}
