package com.example.shoppingcart.service;

import com.example.shoppingcart.model.Product;
import com.example.shoppingcart.model.ShopOrder;
import com.example.shoppingcart.model.ShopOrder.OrderLine;
import com.mongodb.client.model.ReplaceOptions;
import jakarta.annotation.PreDestroy;
import org.bson.Document;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

@Service
public class DatabaseService {
    private final StringRedisTemplate redis;
    private final MongoTemplate mongo;
    private final String monetUrl, monetUser, monetPassword, neoUri, neoUser, neoPassword;
    private final boolean monetEnabled;
    private volatile Driver neoDriver;
    private volatile boolean productSeedAttempted = false;
    private volatile boolean monetTablesReady = false;
    private final Object monetInitLock = new Object();
    private final Deque<ActivityEvent> events = new ConcurrentLinkedDeque<>();
    private final List<Product> seedProducts = List.of(
        new Product("P100","Wireless Headphones","Electronics","Comfortable everyday headphones","🎧",2499,18),
        new Product("P101","Mechanical Keyboard","Electronics","Compact keyboard for study and work","⌨️",1899,12),
        new Product("P102","Running Shoes","Fashion","Lightweight everyday running shoes","👟",3299,9),
        new Product("P103","Smart Watch","Electronics","Fitness and notification tracking","⌚",4599,7),
        new Product("P104","Backpack","Accessories","Everyday laptop backpack","🎒",1499,22),
        new Product("P105","Water Bottle","Accessories","Reusable stainless steel bottle","🧴",599,30),
        new Product("P106","Desk Lamp","Home","Adjustable LED study lamp","💡",999,14),
        new Product("P107","Coffee Maker","Home","Compact coffee maker","☕",2799,5),
        new Product("P108","Running T-Shirt","Fashion","Lightweight everyday sports t-shirt","👕",799,20),
        new Product("P109","Casual Hoodie","Fashion","Comfortable hoodie for everyday wear","🧥",1599,10),
        new Product("P110","Storage Organizer","Home","Compact organizer for desk and shelves","🗄️",699,16),
        new Product("P111","Smart Plug","Home","Wi-Fi smart plug for home devices","🔌",1199,11),
        new Product("P112","Travel Pouch","Accessories","Small pouch for cables and essentials","🧳",499,25)
    );

    public record ActivityEvent(String timestamp, String database, String operation, String status,
                                long durationMs, String details, String message) {}
    public record CartRow(Product product, int quantity) {
        public double getLineTotal() { return product.getPrice() * quantity; }
    }
    public record AnalyticsRow(String productName, long units, double revenue) {}

    public DatabaseService(StringRedisTemplate redis, MongoTemplate mongo,
            @Value("${monetdb.url}") String monetUrl,
            @Value("${monetdb.username}") String monetUser,
            @Value("${monetdb.password}") String monetPassword,
            @Value("${monetdb.enabled:false}") boolean monetEnabled,
            @Value("${neo4j.uri}") String neoUri,
            @Value("${neo4j.username}") String neoUser,
            @Value("${neo4j.password}") String neoPassword) {
        this.redis = redis; this.mongo = mongo;
        this.monetUrl = monetUrl; this.monetUser = monetUser; this.monetPassword = monetPassword; this.monetEnabled = monetEnabled;
        this.neoUri = neoUri; this.neoUser = neoUser; this.neoPassword = neoPassword;
        for (Product p : seedProducts) {
            // Seed catalogue in the graph when requested; no network call during application startup.
        }
        record("System", "APPLICATION_START", "NightCart started", "INFO", 0, "Startup complete; database checks run on demand", false);
    }

    private void record(String database, String operation, String details, String status, long start, String message) {
        record(database, operation, details, status, Math.max(0, System.currentTimeMillis() - start), message, true);
    }
    private void record(String database, String operation, String details, String status, long durationMs, String message, boolean persist) {
        ActivityEvent e = new ActivityEvent(LocalDateTime.now().toString().replace('T',' '), database, operation,
                status, durationMs, safe(details), safe(message));
        events.addFirst(e);
        while (events.size() > 250) events.pollLast();
        if (persist && !"MongoDB".equals(database)) {
            try {
                mongo.getCollection("activity_logs").insertOne(new Document("timestamp", e.timestamp())
                    .append("database", database).append("operation", operation).append("status", status)
                    .append("durationMs", durationMs).append("details", e.details()).append("message", e.message()));
            } catch (Exception ignored) { /* in-memory log remains available if MongoDB is down */ }
        } else if (persist && "MongoDB".equals(database)) {
            // Persist MongoDB events directly; this write is deliberately not logged again.
            try { mongo.getCollection("activity_logs").insertOne(new Document("timestamp", e.timestamp())
                .append("database", database).append("operation", operation).append("status", status)
                .append("durationMs", durationMs).append("details", e.details()).append("message", e.message())); }
            catch (Exception ignored) { }
        }
    }
    private String safe(String s) { return s == null ? "" : s.length() > 500 ? s.substring(0,500) : s; }
    private String error(Exception ex) { String s = ex.getMessage(); return ex.getClass().getSimpleName() + (s == null ? "" : ": " + s); }
    public List<ActivityEvent> activityLog() { return new ArrayList<>(events); }
    public boolean monetEnabled() { return monetEnabled; }

    private void seedMongoIfNeeded() {
        if (productSeedAttempted) return;
        synchronized (this) {
            if (productSeedAttempted) return;
            long t = System.currentTimeMillis();
            try {
                int inserted = 0;
                for (Product p : seedProducts) {
                    if (!mongo.exists(org.springframework.data.mongodb.core.query.Query.query(
                            org.springframework.data.mongodb.core.query.Criteria.where("id").is(p.getId())), Product.class)) {
                        mongo.save(p);
                        inserted++;
                    }
                }
                if (inserted > 0) record("MongoDB", "SEED missing products", inserted + " starter product(s)", "SUCCESS", t, "Added missing starter products without overwriting existing catalogue items");
                productSeedAttempted = true;
            } catch (Exception ex) {
                record("MongoDB", "SEED products", "Starter catalogue", "FAILED", t, error(ex));
                // Keep retrying on later requests; fallback catalogue remains usable.
            }
        }
    }

    public List<Product> products() {
        seedMongoIfNeeded(); long t = System.currentTimeMillis();
        try {
            List<Product> result = mongo.findAll(Product.class);
            record("MongoDB", "FIND products", result.size() + " product document(s)", "SUCCESS", t, "Catalogue read from MongoDB");
            if (!result.isEmpty()) return result;
        } catch (Exception ex) { record("MongoDB", "FIND products", "Catalogue read", "FAILED", t, error(ex)); }
        return new ArrayList<>(seedProducts);
    }
    public Optional<Product> product(String id) { return products().stream().filter(p -> Objects.equals(p.getId(), id)).findFirst(); }

    public String addProduct(Product p) {
        if (p.getId() == null || p.getId().isBlank()) p.setId("P" + (100 + new Random().nextInt(900)));
        long t = System.currentTimeMillis();
        try { mongo.save(p); record("MongoDB", "SAVE product", p.getId() + " · " + p.getName(), "SUCCESS", t, "Product document saved"); return "Product saved in MongoDB."; }
        catch (Exception ex) { record("MongoDB", "SAVE product", p.getId(), "FAILED", t, error(ex)); throw new IllegalStateException("MongoDB is unavailable; product was not saved."); }
    }

    private String cartKey(String customer) { return "cart:" + normalizeCustomer(customer); }
    private String normalizeCustomer(String customer) { String c = customer == null ? "Guest" : customer.trim(); if (c.isBlank()) c = "Guest"; return c.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]", "_"); }
    public Map<String,Integer> cart(String customer) {
        long t = System.currentTimeMillis();
        try {
            Map<Object,Object> raw = redis.opsForHash().entries(cartKey(customer)); Map<String,Integer> out = new LinkedHashMap<>();
            raw.forEach((k,v) -> { try { out.put(k.toString(), Integer.parseInt(v.toString())); } catch (Exception ignored) {} });
            record("Redis", "HGETALL cart", customer + " · " + out.size() + " item(s)", "SUCCESS", t, "Cart loaded from Redis hash"); return out;
        } catch (Exception ex) { record("Redis", "HGETALL cart", customer, "FAILED", t, error(ex)); return new LinkedHashMap<>(); }
    }
    public void addToCart(String customer, String productId, int quantity) {
        if (quantity < 1) throw new IllegalArgumentException("Quantity must be at least 1.");
        long t = System.currentTimeMillis();
        // Atomically decrement inventory so concurrent customers cannot oversell the same units.
        Product updated = mongo.findAndModify(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(productId).and("stock").gte(quantity)),
            new org.springframework.data.mongodb.core.query.Update().inc("stock", -quantity),
            org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),
            Product.class);
        if (updated == null) {
            if (product(productId).isEmpty()) throw new IllegalArgumentException("Product not found.");
            throw new IllegalArgumentException("Not enough stock available for that quantity.");
        }
        try {
            redis.opsForHash().increment(cartKey(customer), productId, quantity);
            redis.expire(cartKey(customer), Duration.ofDays(7));
            redis.opsForSet().add("cart:customers", normalizeCustomer(customer));
            redis.opsForList().leftPush("cart:activity", customer + " added " + updated.getName() + " × " + quantity + " at " + Instant.now());
            redis.opsForList().trim("cart:activity", 0, 49);
            record("Redis", "HINCRBY / EXPIRE / SADD / LPUSH", customer + " · " + updated.getName() + " × " + quantity, "SUCCESS", t, "Cart updated; stock deducted in MongoDB");
            syncActiveCart(customer);
        } catch (Exception ex) {
            // Restore stock if Redis did not accept the cart update.
            mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(productId)),
                new org.springframework.data.mongodb.core.query.Update().inc("stock", quantity), Product.class);
            record("Redis", "ADD_TO_CART", customer + " · " + productId, "FAILED", t, error(ex));
            throw new IllegalStateException("Could not update your cart. Stock has been restored; please retry.");
        }
    }
    public void removeFromCart(String customer, String productId) {
        long t = System.currentTimeMillis();
        try {
            Object value = redis.opsForHash().get(cartKey(customer), productId);
            if (value != null) {
                int quantity = Integer.parseInt(value.toString());
                mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                    org.springframework.data.mongodb.core.query.Criteria.where("id").is(productId)),
                    new org.springframework.data.mongodb.core.query.Update().inc("stock", quantity), Product.class);
            }
            redis.opsForHash().delete(cartKey(customer), productId);
            record("Redis", "HDEL cart", customer + " · " + productId, "SUCCESS", t, "Cart item removed and reserved-by-cart stock restored");
            syncActiveCart(customer);
        } catch (Exception ex) {
            record("Redis", "HDEL cart", customer + " · " + productId, "FAILED", t, error(ex));
            throw new IllegalStateException("Could not remove the item safely. Please retry.");
        }
    }
    public List<CartRow> cartRows(String customer) {
        Map<String,Integer> map = cart(customer); List<CartRow> rows = new ArrayList<>();
        // Use a single catalogue read rather than repeatedly querying MongoDB for each cart item.
        Map<String,Product> byId = new HashMap<>(); products().forEach(p -> byId.put(p.getId(), p));
        map.forEach((id, qty) -> { Product p = byId.get(id); if (p != null) rows.add(new CartRow(p, qty)); }); return rows;
    }
    public double cartTotal(String customer) { return cartRows(customer).stream().mapToDouble(CartRow::getLineTotal).sum(); }
    private void syncActiveCart(String customer) {
        long t = System.currentTimeMillis();
        try {
            List<Document> lines = new ArrayList<>(); double total = 0;
            for (CartRow row : cartRows(customer)) { lines.add(new Document("productId", row.product().getId()).append("name", row.product().getName()).append("quantity", row.quantity()).append("price", row.product().getPrice())); total += row.getLineTotal(); }
            mongo.getCollection("active_carts").replaceOne(new Document("_id", normalizeCustomer(customer)), new Document("customer", customer).append("items", lines).append("total", total).append("updatedAt", new Date()), new ReplaceOptions().upsert(true));
            record("MongoDB", "UPSERT active_carts", customer + " · " + lines.size() + " item(s)", "SUCCESS", t, "MongoDB stores a snapshot; Redis remains the live cart store");
        } catch (Exception ex) { record("MongoDB", "UPSERT active_carts", customer, "FAILED", t, error(ex)); }
    }

    public ShopOrder checkout(String customer, String deliveryName, String phone, String address, String city, String postalCode) {
        List<CartRow> rows = cartRows(customer); if (rows.isEmpty()) throw new IllegalArgumentException("Your cart is empty.");
        List<OrderLine> lines = rows.stream().map(r -> new OrderLine(r.product().getId(), r.product().getName(), r.quantity(), r.product().getPrice())).toList();
        double total = rows.stream().mapToDouble(CartRow::getLineTotal).sum();
        ShopOrder order = new ShopOrder("ORD-" + UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT), customer, lines, total, "Placed", Instant.now());
        order.setDeliveryName(deliveryName);
        order.setPhone(phone);
        order.setAddress(address);
        order.setCity(city);
        order.setPostalCode(postalCode);
        order.setStockDeducted(true);
        long t = System.currentTimeMillis();
        try { mongo.save(order); record("MongoDB", "INSERT order", order.getId() + " · ₹" + String.format(Locale.ROOT,"%.2f",total), "SUCCESS", t, "Order document saved; MongoDB is the order source of truth"); }
        catch (Exception ex) { record("MongoDB", "INSERT order", order.getId(), "FAILED", t, error(ex)); throw new IllegalStateException("MongoDB is unavailable, so checkout was not completed."); }
        long rt = System.currentTimeMillis();
        try { redis.opsForZSet().add("cart:amounts", normalizeCustomer(customer), total); redis.opsForList().leftPush("cart:activity", customer + " checked out " + order.getId()); redis.opsForList().trim("cart:activity",0,49); redis.delete(cartKey(customer)); redis.opsForSet().add("cart:customers", normalizeCustomer(customer)); record("Redis", "ZADD / LPUSH / DEL", order.getId(), "SUCCESS", rt, "Checkout total ranked, activity added and cart cleared"); }
        catch (Exception ex) { record("Redis", "CHECKOUT cleanup", order.getId(), "FAILED", rt, error(ex)); }
        if (monetEnabled) recordSales(order); else record("MonetDB", "ANALYTICS_DISABLED", order.getId(), "INFO", System.currentTimeMillis(), "MonetDB disabled; analytics are calculated from MongoDB orders");
        recordGraphOrder(order); return order;
    }

    /**
     * Cancels an order without deleting its history. MonetDB rows are removed first so the
     * recorded revenue changes immediately; if the MongoDB status write fails, we attempt
     * to restore those rows before returning an error. The synchronized method also prevents
     * duplicate cancellation requests from subtracting revenue twice in this application.
     */
    public synchronized ShopOrder updateOrderStatus(String orderId, String status) {
        Set<String> allowed = Set.of("Placed", "Confirmed", "Packed", "Shipped", "Delivered");
        if (!allowed.contains(status)) throw new IllegalArgumentException("Invalid order status.");
        ShopOrder order = mongo.findById(orderId, ShopOrder.class);
        if (order == null) throw new IllegalArgumentException("Order not found.");
        if ("Cancelled".equalsIgnoreCase(order.getStatus())) throw new IllegalArgumentException("Cancelled orders cannot be updated.");
        order.setStatus(status);
        mongo.save(order);
        updateGraphOrderStatus(order);
        record("MongoDB", "UPDATE order status", orderId + " → " + status, "SUCCESS", System.currentTimeMillis(), "Admin updated order lifecycle");
        return order;
    }

    public synchronized ShopOrder cancelOrder(String orderId) {
        long mongoReadStart = System.currentTimeMillis();
        ShopOrder order;
        try {
            order = mongo.findById(orderId, ShopOrder.class);
            if (order == null) throw new IllegalArgumentException("Order not found.");
            record("MongoDB", "FIND order for cancellation", orderId, "SUCCESS", mongoReadStart,
                    "Loaded order document before cancellation");
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            record("MongoDB", "FIND order for cancellation", orderId, "FAILED", mongoReadStart, error(ex));
            throw new IllegalStateException("MongoDB is unavailable; the order was not cancelled.");
        }
        if ("Cancelled".equalsIgnoreCase(order.getStatus())) {
            record("MongoDB", "CANCEL order", orderId, "INFO", System.currentTimeMillis(), "Order was already cancelled; no revenue was changed");
            throw new IllegalArgumentException("This order is already cancelled.");
        }

        int deletedRows = monetEnabled ? deleteSalesForOrder(orderId) : 0; // MonetDB is optional in free hosting mode
        long mongoWriteStart = System.currentTimeMillis();
        order.setStatus("Cancelled");
        try {
            mongo.save(order);
            record("MongoDB", "UPDATE order status", orderId + " → Cancelled", "SUCCESS", mongoWriteStart,
                    "Order document retained with Cancelled status");
        } catch (Exception ex) {
            record("MongoDB", "UPDATE order status", orderId, "FAILED", mongoWriteStart, error(ex));
            if (deletedRows > 0) restoreSales(order);
            throw new IllegalStateException(monetEnabled ? "MongoDB could not save the cancellation. The app attempted to restore the MonetDB sales rows." : "MongoDB could not save the cancellation.");
        }
        updateGraphOrderStatus(order);
        // Older orders predate cart-time stock deduction; do not inflate stock when cancelling them.
        if (order.isStockDeducted()) {
            for (OrderLine line : order.getItems()) {
                mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                    org.springframework.data.mongodb.core.query.Criteria.where("id").is(line.getProductId())),
                    new org.springframework.data.mongodb.core.query.Update().inc("stock", line.getQuantity()), Product.class);
            }
        }
        return order;
    }

    private int deleteSalesForOrder(String orderId) {
        long t = System.currentTimeMillis();
        try (Connection c = monetConnection()) {
            ensureMonetTables(c);
            int deleted;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM sales_items WHERE order_id = ?")) {
                ps.setString(1, orderId);
                deleted = ps.executeUpdate();
            }
            record("MonetDB", "DELETE sales_items", orderId + " · " + deleted + " row(s)", "SUCCESS", t,
                    "Cancelled order's sales rows removed; MonetDB revenue aggregates now exclude this order");
            return deleted;
        } catch (Exception ex) {
            record("MonetDB", "DELETE sales_items", orderId, "FAILED", t, error(ex));
            throw new IllegalStateException("MonetDB is unavailable, so cancellation was stopped to keep order status and revenue consistent.");
        }
    }

    private void restoreSales(ShopOrder order) {
        long t = System.currentTimeMillis();
        try (Connection c = monetConnection()) {
            ensureMonetTables(c);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO sales_items (order_id, product_id, product_name, quantity, unit_price, customer_name, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                for (OrderLine line : order.getItems()) {
                    ps.setString(1, order.getId()); ps.setString(2, line.getProductId());
                    ps.setString(3, line.getName()); ps.setInt(4, line.getQuantity());
                    ps.setBigDecimal(5, java.math.BigDecimal.valueOf(line.getPrice()));
                    ps.setString(6, order.getCustomerName());
                    ps.setTimestamp(7, java.sql.Timestamp.from(order.getCreatedAt())); ps.addBatch();
                }
                ps.executeBatch();
            }
            record("MonetDB", "RESTORE sales_items", order.getId(), "SUCCESS", t,
                    "Sales rows restored after MongoDB cancellation update failed");
        } catch (Exception ex) {
            record("MonetDB", "RESTORE sales_items", order.getId(), "FAILED", t,
                    "CRITICAL: MongoDB status update failed and sales restoration also failed: " + error(ex));
        }
    }

    private void updateGraphOrderStatus(ShopOrder order) {
        long t = System.currentTimeMillis();
        try (Session session = neoDriver().session()) {
            session.run("MATCH (o:Order {id:$id}) SET o.status=$status", Map.of("id", order.getId(), "status", "Cancelled")).consume();
            record("Neo4j", "SET order status", order.getId() + " → Cancelled", "SUCCESS", t,
                    "Order graph node marked cancelled; product relationship history retained");
        } catch (Exception ex) {
            record("Neo4j", "SET order status", order.getId(), "FAILED", t, error(ex));
        }
    }

    private Connection monetConnection() throws Exception {
        DriverManager.setLoginTimeout(3);
        return DriverManager.getConnection(monetUrl, monetUser, monetPassword);
    }
    private void ensureMonetTables(Connection c) throws Exception {
        if (monetTablesReady) return;
        synchronized (monetInitLock) {
            if (monetTablesReady) return;
            try (Statement s = c.createStatement()) {
                try { s.executeUpdate("CREATE TABLE sales_items (order_id VARCHAR(40), product_id VARCHAR(40), product_name VARCHAR(120), quantity INTEGER, unit_price DECIMAL(12,2), customer_name VARCHAR(120), created_at TIMESTAMP)"); }
                catch (Exception ex) { if (ex.getMessage() == null || !ex.getMessage().toLowerCase(Locale.ROOT).contains("already exists")) {
                    // A second connection may race with initialization; verify table before surfacing the error.
                    try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM sales_items")) { rs.next(); }
                }}
                monetTablesReady = true;
            }
        }
    }
    private void recordSales(ShopOrder order) {
        long t = System.currentTimeMillis();
        try (Connection c = monetConnection()) {
            ensureMonetTables(c);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO sales_items (order_id, product_id, product_name, quantity, unit_price, customer_name, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)");) {
                for (OrderLine line : order.getItems()) { ps.setString(1, order.getId()); ps.setString(2, line.getProductId()); ps.setString(3, line.getName()); ps.setInt(4, line.getQuantity()); ps.setBigDecimal(5, java.math.BigDecimal.valueOf(line.getPrice())); ps.setString(6, order.getCustomerName()); ps.setTimestamp(7, java.sql.Timestamp.from(order.getCreatedAt())); ps.addBatch(); }
                ps.executeBatch();
            }
            record("MonetDB", "INSERT sales_items", order.getId() + " · " + order.getItems().size() + " item(s)", "SUCCESS", t, "Relational sales rows inserted for analytics");
        } catch (Exception ex) { record("MonetDB", "INSERT sales_items", order.getId(), "FAILED", t, error(ex)); }
    }
    public List<AnalyticsRow> analytics() {
        if (!monetEnabled) {
            Map<String, long[]> totals = new TreeMap<>();
            for (ShopOrder order : orders()) {
                if (order.getStatus() != null && order.getStatus().equalsIgnoreCase("Cancelled")) continue;
                if (order.getItems() == null) continue;
                for (OrderLine line : order.getItems()) {
                    long[] v = totals.computeIfAbsent(line.getName(), k -> new long[2]);
                    v[0] += line.getQuantity();
                    v[1] += Math.round(line.getQuantity() * line.getPrice() * 100.0);
                }
            }
            List<AnalyticsRow> rows = new ArrayList<>();
            totals.forEach((name, v) -> rows.add(new AnalyticsRow(name, v[0], v[1] / 100.0)));
            rows.sort(Comparator.comparingLong(AnalyticsRow::units).reversed());
            record("MongoDB", "AGGREGATE orders", rows.size() + " product aggregate(s)", "SUCCESS", System.currentTimeMillis(), "Free-hosting analytics calculated from non-cancelled MongoDB orders, ranked by units sold");
            return rows;
        }
        long t = System.currentTimeMillis(); List<AnalyticsRow> out = new ArrayList<>();
        try (Connection c = monetConnection()) {
            ensureMonetTables(c);
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT product_name, SUM(quantity) AS units, SUM(quantity * unit_price) AS revenue FROM sales_items GROUP BY product_name ORDER BY product_name")) {
                while (rs.next()) out.add(new AnalyticsRow(rs.getString("product_name"), rs.getLong("units"), rs.getDouble("revenue")));
            }
            record("MonetDB", "SELECT sales aggregate", out.size() + " product aggregate(s)", "SUCCESS", t, "Analytics queried from MonetDB SQL tables");
        } catch (Exception ex) { record("MonetDB", "SELECT sales aggregate", "sales_items", "FAILED", t, error(ex)); }
        return out;
    }

    private Driver neoDriver() { if (neoDriver == null) synchronized (this) { if (neoDriver == null) neoDriver = GraphDatabase.driver(neoUri, AuthTokens.basic(neoUser, neoPassword), org.neo4j.driver.Config.builder().withConnectionTimeout(2500, TimeUnit.MILLISECONDS).build()); } return neoDriver; }
    private void recordGraphOrder(ShopOrder order) {
        long t = System.currentTimeMillis();
        try (Session session = neoDriver().session()) {
            session.executeWrite(tx -> {
                for (OrderLine line : order.getItems()) tx.run("MERGE (p:Product {id:$id}) SET p.name=$name", Map.of("id",line.getProductId(),"name",line.getName()));
                for (int i=0;i<order.getItems().size();i++) for (int j=i+1;j<order.getItems().size();j++) {
                    OrderLine a=order.getItems().get(i), b=order.getItems().get(j);
                    tx.run("MATCH (a:Product {id:$a}), (b:Product {id:$b}) MERGE (a)-[:BOUGHT_WITH]-(b)", Map.of("a",a.getProductId(),"b",b.getProductId()));
                }
                tx.run("MERGE (o:Order {id:$id}) SET o.customer=$customer, o.total=$total", Map.of("id",order.getId(),"customer",order.getCustomerName(),"total",order.getTotal()));
                for (OrderLine line : order.getItems()) tx.run("MATCH (o:Order {id:$oid}), (p:Product {id:$pid}) MERGE (o)-[:CONTAINS {quantity:$qty}]->(p)", Map.of("oid",order.getId(),"pid",line.getProductId(),"qty",line.getQuantity()));
                return null;
            });
            record("Neo4j", "MERGE order/product graph", order.getId() + " · " + order.getItems().size() + " product(s)", "SUCCESS", t, "Order, Product and BOUGHT_WITH relationships updated");
        } catch (Exception ex) { record("Neo4j", "MERGE order/product graph", order.getId(), "FAILED", t, error(ex)); }
    }
    public List<Product> recommendations(String productId) {
        Product source = product(productId).orElse(null); if (source == null) return List.of();
        long t = System.currentTimeMillis(); List<String> ids = new ArrayList<>();
        try (Session session = neoDriver().session()) {
            // Make catalogue nodes and category relationships available for a useful first-run recommendation demo.
            session.executeWrite(tx -> {
                for (Product p : products()) tx.run("MERGE (n:Product {id:$id}) SET n.name=$name, n.category=$category", Map.of("id",p.getId(),"name",p.getName(),"category",p.getCategory()));
                List<Product> catalogue = products();
                for (Product a : catalogue) for (Product b : catalogue)
                    if (a.getId().compareTo(b.getId()) < 0 && a.getCategory() != null && a.getCategory().equalsIgnoreCase(b.getCategory()))
                        tx.run("MATCH (a:Product {id:$a}), (b:Product {id:$b}) MERGE (a)-[:RELATED_TO]-(b)", Map.of("a",a.getId(),"b",b.getId()));
                return null;
            });
            var result = session.run("MATCH (p:Product {id:$id})-[:BOUGHT_WITH|RELATED_TO]-(r:Product) WHERE r.id <> $id AND toLower(coalesce(r.category, '')) = toLower(coalesce(p.category, '')) RETURN DISTINCT r.id AS id LIMIT 8", Map.of("id",productId));
            while (result.hasNext()) ids.add(result.next().get("id").asString());
            record("Neo4j", "MATCH recommendations", productId + " · " + ids.size() + " result(s)", "SUCCESS", t, "Cypher graph query restricted to the source product category");
        } catch (Exception ex) { record("Neo4j", "MATCH recommendations", productId, "FAILED", t, error(ex)); }
        Map<String,Product> all = new HashMap<>(); products().forEach(p -> all.put(p.getId(),p));
        List<Product> out = new ArrayList<>(); for (String id : ids) if (all.containsKey(id)) out.add(all.get(id));
        List<Product> sameCategory = products().stream()
            .filter(p -> !p.getId().equals(productId) && p.getCategory() != null && source.getCategory() != null
                && p.getCategory().equalsIgnoreCase(source.getCategory()))
            .toList();
        for (Product candidate : sameCategory) {
            if (out.stream().noneMatch(existing -> existing.getId().equals(candidate.getId()))) out.add(candidate);
            if (out.size() >= 8) break;
        }
        return out.stream().limit(8).toList();
    }

    public Map<String,String> status() {
        Map<String,String> out = new LinkedHashMap<>();
        long t=System.currentTimeMillis(); try { var connection=redis.getConnectionFactory().getConnection(); try { String pong=connection.ping(); if (pong != null) { out.put("Redis","Online"); record("Redis","PING health check","PING → "+pong,"SUCCESS",t,"Redis connection verified"); } else throw new IllegalStateException("PING returned null"); } finally { connection.close(); } }
        catch (Exception ex) { out.put("Redis","Offline"); record("Redis","PING health check","Connection check","FAILED",t,error(ex)); }
        t=System.currentTimeMillis(); try { mongo.getDb().runCommand(new Document("ping",1)); out.put("MongoDB","Online"); record("MongoDB","PING health check","MongoDB ping command","SUCCESS",t,"MongoDB connection verified"); }
        catch (Exception ex) { out.put("MongoDB","Offline"); record("MongoDB","PING health check","Connection check","FAILED",t,error(ex)); }
        t=System.currentTimeMillis();
        if (!monetEnabled) { out.put("MonetDB", "Disabled (MongoDB analytics)"); record("MonetDB", "HEALTH CHECK", "Optional database disabled", "INFO", t, "Analytics use MongoDB orders in free-hosting mode"); }
        else try (Connection c=monetConnection(); Statement s=c.createStatement(); ResultSet rs=s.executeQuery("SELECT 1")) { rs.next(); out.put("MonetDB","Online"); record("MonetDB","SELECT 1 health check","SELECT 1","SUCCESS",t,"MonetDB JDBC query succeeded"); }
        catch (Exception ex) { out.put("MonetDB","Offline"); record("MonetDB","SELECT 1 health check","Connection check","FAILED",t,error(ex)); }
        t=System.currentTimeMillis(); try (Session session=neoDriver().session()) { session.run("RETURN 1 AS ok").single().get("ok").asInt(); out.put("Neo4j","Online"); record("Neo4j","RETURN 1 health check","Cypher RETURN 1","SUCCESS",t,"Neo4j query succeeded"); }
        catch (Exception ex) { out.put("Neo4j","Offline"); record("Neo4j","RETURN 1 health check","Connection check","FAILED",t,error(ex)); }
        return out;
    }
    public String recentActivity() {
        long t=System.currentTimeMillis(); try { List<String> a=redis.opsForList().range("cart:activity",0,4); record("Redis","LRANGE cart:activity","Latest "+(a==null?0:a.size())+" event(s)","SUCCESS",t,"Recent Redis activity read"); return a==null?"":String.join("\n",a); }
        catch (Exception ex) { record("Redis","LRANGE cart:activity","Recent activity","FAILED",t,error(ex)); return "Redis activity unavailable"; }
    }
    public List<ShopOrder> orders() {
        long t=System.currentTimeMillis(); try { List<ShopOrder> result=mongo.findAll(ShopOrder.class).stream().sorted(Comparator.comparing(ShopOrder::getCreatedAt,Comparator.nullsLast(Comparator.reverseOrder()))).toList(); record("MongoDB","FIND orders",result.size()+" order(s)","SUCCESS",t,"Order history read from MongoDB"); return result; }
        catch (Exception ex) { record("MongoDB","FIND orders","Order history","FAILED",t,error(ex)); return List.of(); }
    }
    public long customerCount() { long t=System.currentTimeMillis(); try { Long n=redis.opsForSet().size("cart:customers"); record("Redis","SCARD cart:customers",String.valueOf(n),"SUCCESS",t,"Unique customer count read from Redis Set"); return n==null?0:n; } catch(Exception ex) { record("Redis","SCARD cart:customers","Customer count","FAILED",t,error(ex)); return 0; } }
    public long orderCount() { return orders().size(); }
    public double totalSales() {
        if (!monetEnabled) return orders().stream()
                .filter(o -> o.getStatus() == null || !o.getStatus().equalsIgnoreCase("Cancelled"))
                .mapToDouble(ShopOrder::getTotal).sum();
        long t=System.currentTimeMillis();
        try (Connection c=monetConnection()) {
            ensureMonetTables(c);
            try (Statement s=c.createStatement(); ResultSet rs=s.executeQuery("SELECT COALESCE(SUM(quantity * unit_price), 0) FROM sales_items")) {
                rs.next(); double n=rs.getDouble(1);
                record("MonetDB","SELECT SUM sales","Revenue total","SUCCESS",t,"Revenue total queried from MonetDB"); return n;
            }
        } catch(Exception ex) { record("MonetDB","SELECT SUM sales","Revenue total","FAILED",t,error(ex)); return 0; }
    }

    @PreDestroy public void close() { if (neoDriver != null) try { neoDriver.close(); } catch(Exception ignored) {} }
}
