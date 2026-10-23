
package com.example.shoppingcart.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.List;

@Document("orders")
public class ShopOrder {

    @Id
    private String id;

    private String customerName;
    private List<OrderLine> items;
    private double total;
    private String status;
    private Instant createdAt;

    private String deliveryName;
    private String phone;
    private String address;
    private String city;
    private String postalCode;
    private boolean stockDeducted;

    public ShopOrder() {
    }

    public ShopOrder(String id, String customerName,
                     List<OrderLine> items, double total,
                     String status, Instant createdAt) {
        this.id = id;
        this.customerName = customerName;
        this.items = items;
        this.total = total;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String v) {
        id = v;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String v) {
        customerName = v;
    }

    public List<OrderLine> getItems() {
        return items;
    }

    public void setItems(List<OrderLine> v) {
        items = v;
    }

    public double getTotal() {
        return total;
    }

    public void setTotal(double v) {
        total = v;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        status = v;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant v) {
        createdAt = v;
    }

    public String getDeliveryName() {
        return deliveryName;
    }

    public void setDeliveryName(String v) {
        deliveryName = v;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String v) {
        phone = v;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String v) {
        address = v;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String v) {
        city = v;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String v) {
        postalCode = v;
    }

    public boolean isStockDeducted() {
        return stockDeducted;
    }

    public void setStockDeducted(boolean v) {
        stockDeducted = v;
    }

    public String getFullAddress() {
        return String.join(", ",
            java.util.stream.Stream.of(address, city, postalCode)
                .filter(v -> v != null && !v.isBlank())
                .toList()
        );
    }

    public static class OrderLine {

        private String productId;
        private String name;
        private int quantity;
        private double price;

        public OrderLine() {
        }

        public OrderLine(String id, String name,
                         int quantity, double price) {
            this.productId = id;
            this.name = name;
            this.quantity = quantity;
            this.price = price;
        }

        public String getProductId() {
            return productId;
        }

        public void setProductId(String v) {
            productId = v;
        }

        public String getName() {
            return name;
        }

        public void setName(String v) {
            name = v;
        }

        public int getQuantity() {
            return quantity;
        }

        public void setQuantity(int v) {
            quantity = v;
        }

        public double getPrice() {
            return price;
        }

        public void setPrice(double v) {
            price = v;
        }

        public double getLineTotal() {
            return price * quantity;
        }
    }
}
