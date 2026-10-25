package org.example.web;

import javax.ws.rs.Path;

/** A JAX-RS resource class outside the agent's own packages; instrumented only when an application loader defines it. */
@Path("/orders")
public class OrdersResource {

    public String create(String body) {
        return "created " + body;
    }
}
