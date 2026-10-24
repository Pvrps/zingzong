package com.acme.tax.api;

import javax.ejb.CreateException;
import javax.ejb.EJBHome;
import java.rmi.RemoteException;

/** EJB 2.x home of the rate lookup: the old component contract, declared in ejb-jar.xml, still common at work. */
public interface RateLookupHome extends EJBHome {

    String JNDI_NAME = "tax/RateLookupHome";

    RateLookup create() throws CreateException, RemoteException;
}
