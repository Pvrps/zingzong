import com.acme.common.model.Order;
import com.acme.orders.api.OrderServiceRemote;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.naming.Context;
import javax.naming.InitialContext;
import java.io.File;
import java.util.Hashtable;

/**
 * Phase 6 JNDI proof, groundwork for Phase 7's replay check: read the first jndiNames entry
 * and the result id from a captured createOrder span, look the name up over T3 and call
 * findOrder with the id. Run by scripts/jndi-proof.sh.
 *
 *   java JndiProof <trace-file.json> <t3-url> <user> <password>
 */
public class JndiProof {
    public static void main(String[] args) throws Exception {
        JsonNode trace = new ObjectMapper().readTree(new File(args[0]));
        JsonNode span = null;
        for (JsonNode s : trace.get("spans")) {
            if (s.path("ejb").path("method").asText().equals("createOrder")) {
                span = s;
                break;
            }
        }
        if (span == null) {
            throw new IllegalArgumentException("no createOrder span in " + args[0]);
        }
        String jndiName = span.get("ejb").get("jndiNames").get(0).asText();
        long orderId = span.get("result").get("value").get("id").asLong();
        System.out.println("span " + span.get("id").asText() + ": " + span.get("ejb").get("interface").asText()
                + " " + span.get("ejb").get("invocation").asText() + ", jndiNames[0]=" + jndiName + ", result.id=" + orderId);

        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "weblogic.jndi.WLInitialContextFactory");
        env.put(Context.PROVIDER_URL, args[1]);
        env.put(Context.SECURITY_PRINCIPAL, args[2]);
        env.put(Context.SECURITY_CREDENTIALS, args[3]);
        Context ctx = new InitialContext(env);
        try {
            Object found = ctx.lookup(jndiName);
            System.out.println("lookup " + jndiName + " -> " + found.getClass().getName());
            Order order = ((OrderServiceRemote) found).findOrder(orderId);
            System.out.println("findOrder(" + orderId + ") -> status=" + order.getStatus() + " customer=" + order.getCustomerName()
                    + " total=" + order.getTotal());
        } finally {
            ctx.close();
        }
    }
}
