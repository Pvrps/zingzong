package ca.gc.cra.xz02.devops.wltrace.agent.fixtures;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamField;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ORDERS")
public class Order implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "CUSTOMER_NAME", nullable = false)
    private String customerName;

    @Column(name = "CUSTOMER_EMAIL")
    private String customerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.CREATED;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "DELIVERY_DATE")
    private LocalDate deliveryDate;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal tax;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(name = "PAYMENT_SUMMARY")
    private String paymentSummary;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<OrderLine> lines = new ArrayList<>();

    /**
     * Not persisted (@Transient) and not Serializable, but deliberately not a Java
     * {@code transient} field: reflection-based serializers must see it. Java
     * serialization is handled explicitly below so remote EJB calls still work.
     */
    @Transient
    private AuditTrail audit = new AuditTrail(Instant.now());

    private static final ObjectStreamField[] serialPersistentFields = {
            new ObjectStreamField("id", Long.class),
            new ObjectStreamField("customerName", String.class),
            new ObjectStreamField("customerEmail", String.class),
            new ObjectStreamField("status", OrderStatus.class),
            new ObjectStreamField("createdAt", LocalDateTime.class),
            new ObjectStreamField("deliveryDate", LocalDate.class),
            new ObjectStreamField("currency", String.class),
            new ObjectStreamField("subtotal", BigDecimal.class),
            new ObjectStreamField("tax", BigDecimal.class),
            new ObjectStreamField("total", BigDecimal.class),
            new ObjectStreamField("paymentSummary", String.class),
            new ObjectStreamField("lines", List.class),
    };

    public Order() {
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        out.writeObject(audit == null ? null : audit.getOpenedAt());
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        Instant openedAt = (Instant) in.readObject();
        audit = new AuditTrail(openedAt == null ? Instant.now() : openedAt);
        audit.record("deserialized");
    }

    public Long getId() {
        return id;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerEmail(String customerEmail) {
        this.customerEmail = customerEmail;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
        if (audit != null) {
            audit.record("status -> " + status);
        }
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDate getDeliveryDate() {
        return deliveryDate;
    }

    public void setDeliveryDate(LocalDate deliveryDate) {
        this.deliveryDate = deliveryDate;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }

    public BigDecimal getTax() {
        return tax;
    }

    public void setTax(BigDecimal tax) {
        this.tax = tax;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public String getPaymentSummary() {
        return paymentSummary;
    }

    public void setPaymentSummary(String paymentSummary) {
        this.paymentSummary = paymentSummary;
    }

    /** Lazy; touching this outside a transaction triggers a load. */
    public List<OrderLine> getLines() {
        return lines;
    }

    public void addLine(String sku, int quantity, BigDecimal unitPrice) {
        lines.add(new OrderLine(this, sku, quantity, unitPrice));
    }

    public AuditTrail getAudit() {
        return audit;
    }
}
