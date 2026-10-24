package com.acme.orders.web;

import com.acme.orders.api.CartService;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.Map;

/**
 * The stateful conversation from the web tier: the CartService instance lives in the HTTP
 * session, so two requests in sequence reach the same bean instance.
 */
@Path("/cart")
@Produces(MediaType.APPLICATION_JSON)
public class CartResource {

    private static final String ATTRIBUTE = "cart";

    @Context
    private HttpServletRequest httpRequest;

    @POST
    @Path("/items")
    @Consumes(MediaType.APPLICATION_JSON)
    public String addItem(String body) {
        JsonObject json = Json.createReader(new java.io.StringReader(body)).readObject();
        int count = cart(true).addItem(json.getString("sku"), json.getInt("quantity", 1));
        return Json.createObjectBuilder().add("items", count).build().toString();
    }

    @GET
    @Path("/total")
    public String total() {
        CartService cart = cart(false);
        if (cart == null) {
            return Json.createObjectBuilder().add("error", "no cart in this session").build().toString();
        }
        JsonObjectBuilder items = Json.createObjectBuilder();
        for (Map.Entry<String, Integer> e : cart.items().entrySet()) {
            items.add(e.getKey(), e.getValue());
        }
        return Json.createObjectBuilder().add("items", items).add("total", cart.total()).build().toString();
    }

    @DELETE
    public String checkout() {
        CartService cart = cart(false);
        if (cart != null) {
            cart.checkout();
            httpRequest.getSession().removeAttribute(ATTRIBUTE);
        }
        return Json.createObjectBuilder().add("checkedOut", cart != null).build().toString();
    }

    private CartService cart(boolean create) {
        HttpSession session = httpRequest.getSession(true);
        CartService cart = (CartService) session.getAttribute(ATTRIBUTE);
        if (cart == null && create) {
            try {
                cart = (CartService) new InitialContext().lookup("java:module/CartService!com.acme.orders.api.CartService");
            } catch (NamingException e) {
                // the web module and the EJB module are in one EAR: java:app always resolves
                try {
                    cart = (CartService) new InitialContext().lookup("java:app/orders-ejb/CartService!com.acme.orders.api.CartService");
                } catch (NamingException inner) {
                    throw new IllegalStateException(inner);
                }
            }
            session.setAttribute(ATTRIBUTE, cart);
        }
        return cart;
    }
}
