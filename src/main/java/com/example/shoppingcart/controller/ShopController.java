package com.example.shoppingcart.controller;

import com.example.shoppingcart.model.Product;
import com.example.shoppingcart.model.ShopOrder;
import com.example.shoppingcart.service.DatabaseService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@Controller
public class ShopController {
    private final DatabaseService db;
    public ShopController(DatabaseService db) { this.db = db; }

    @ModelAttribute
    public void flashMessages(HttpServletRequest request, Model model) {
        String message = request.getParameter("message");
        String error = request.getParameter("error");
        if (message != null) model.addAttribute("message", message);
        if (error != null) model.addAttribute("error", error);
    }

    private boolean isAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
    private void base(Model model, Authentication auth) {
        String username = auth.getName();
        model.addAttribute("customer", username);
        model.addAttribute("isAdmin", isAdmin(auth));
        model.addAttribute("products", db.products());
        model.addAttribute("cartRows", db.cartRows(username));
        model.addAttribute("cartTotal", db.cartTotal(username));
        model.addAttribute("categories", db.products().stream().map(Product::getCategory).distinct().sorted().toList());
        if (isAdmin(auth)) {
            model.addAttribute("statuses", db.status());
            model.addAttribute("customerCount", db.customerCount());
            model.addAttribute("orders", db.orders());
            model.addAttribute("orderCount", db.orderCount());
            model.addAttribute("totalSales", db.totalSales());
            model.addAttribute("productsCount", db.products().size());
            model.addAttribute("activityCount", db.activityLog().size());
            model.addAttribute("recentActivity", db.recentActivity());
        } else {
            model.addAttribute("orders", db.orders().stream().filter(o -> username.equalsIgnoreCase(o.getCustomerName())).toList());
        }
    }
    private String page(Model model, Authentication auth, String page) { base(model, auth); model.addAttribute("page", page); return "index"; }

    @GetMapping({"/", "/dashboard"}) public String dashboard(Authentication auth, @RequestParam(required=false) String message, @RequestParam(required=false) String error, Model model) {
        page(model, auth, "dashboard"); model.addAttribute("message", message); model.addAttribute("error", error); return "index";
    }
    @GetMapping("/products") public String products(Authentication auth, @RequestParam(required=false) String q, @RequestParam(required=false) String category, Model model) {
        page(model, auth, "products"); List<Product> list = db.products();
        if (category != null && !category.isBlank()) list = list.stream().filter(p -> p.getCategory().equalsIgnoreCase(category)).toList();
        if (q != null && !q.isBlank()) { String needle = q.toLowerCase(Locale.ROOT); list = list.stream().filter(p -> p.getName().toLowerCase(Locale.ROOT).contains(needle) || p.getDescription().toLowerCase(Locale.ROOT).contains(needle)).toList(); }
        model.addAttribute("products", list); model.addAttribute("q", q == null ? "" : q); model.addAttribute("category", category == null ? "" : category); return "index";
    }
    @GetMapping("/cart") public String cart(Authentication auth, Model model) { return page(model, auth, "cart"); }
    @GetMapping("/orders") public String orders(Authentication auth, Model model) { return page(model, auth, "orders"); }
    @GetMapping("/analytics") public String analytics(Authentication auth, Model model) { page(model, auth, "analytics"); model.addAttribute("analyticsRows", db.analytics()); return "index"; }
    @GetMapping("/recommendations") public String recommendations(Authentication auth, @RequestParam(defaultValue="P100") String productId, Model model) { page(model, auth, "recommendations"); model.addAttribute("sourceProduct", db.product(productId).orElse(null)); model.addAttribute("recommendations", db.recommendations(productId)); return "index"; }
    @GetMapping("/databases") public String databases(Authentication auth, Model model) { return page(model, auth, "databases"); }
    @GetMapping("/activity") public String activity(Authentication auth, @RequestParam(required=false) String database, @RequestParam(required=false) String status, Model model) {
        page(model, auth, "activity"); var events = db.activityLog();
        if (database != null && !database.isBlank()) events = events.stream().filter(e -> e.database().equalsIgnoreCase(database)).toList();
        if (status != null && !status.isBlank()) events = events.stream().filter(e -> e.status().equalsIgnoreCase(status)).toList();
        model.addAttribute("activityEvents", events); model.addAttribute("selectedDatabase", database == null ? "" : database); model.addAttribute("selectedStatus", status == null ? "" : status); return "index";
    }
    @PostMapping("/cart/add") public String add(Authentication auth, @RequestParam String productId, @RequestParam(defaultValue="1") int quantity) { try { db.addToCart(auth.getName(), productId, quantity); return redirect("/products", "Added to cart", false); } catch (Exception ex) { return redirect("/products", ex.getMessage(), true); } }
    @PostMapping("/cart/remove") public String remove(Authentication auth, @RequestParam String productId) { try { db.removeFromCart(auth.getName(), productId); return redirect("/cart", "Item removed", false); } catch (Exception ex) { return redirect("/cart", ex.getMessage(), true); } }
    @PostMapping("/checkout") public String checkout(Authentication auth,
            @RequestParam String deliveryName, @RequestParam String phone,
            @RequestParam String address, @RequestParam String city, @RequestParam String postalCode) {
        try {
            if (deliveryName.isBlank() || phone.replaceAll("\\D", "").length() < 10 ||
                    address.isBlank() || city.isBlank() || !postalCode.matches("[0-9]{5,6}")) {
                throw new IllegalArgumentException("Enter a recipient name, valid phone number, complete address, city and valid PIN/postal code.");
            }
            ShopOrder order = db.checkout(auth.getName(), deliveryName.trim(), phone.trim(), address.trim(), city.trim(), postalCode.trim());
            return redirect("/orders", "Order " + order.getId() + " placed successfully. Delivery to " + order.getFullAddress(), false);
        } catch (Exception ex) { return redirect("/cart", ex.getMessage(), true); }
    }
    @PostMapping("/orders/status") public String updateOrderStatus(Authentication auth, @RequestParam String orderId, @RequestParam String status) {
        if (!isAdmin(auth)) return redirect("/orders", "Only admins can update order status.", true);
        try { ShopOrder order = db.updateOrderStatus(orderId, status); return redirect("/orders", "Order " + order.getId() + " updated to " + status + ".", false); }
        catch (Exception ex) { return redirect("/orders", ex.getMessage(), true); }
    }
    @PostMapping("/orders/cancel") public String cancelOrder(Authentication auth, @RequestParam String orderId) {
        try {
            ShopOrder existing = db.orders().stream().filter(o -> orderId.equals(o.getId())).findFirst().orElseThrow(() -> new IllegalArgumentException("Order not found."));
            if (!isAdmin(auth) && !auth.getName().equalsIgnoreCase(existing.getCustomerName())) throw new org.springframework.security.access.AccessDeniedException("You cannot cancel another customer's order.");
            ShopOrder order = db.cancelOrder(orderId); return redirect("/orders", "Order " + order.getId() + " cancelled.", false);
        } catch (Exception ex) { return redirect("/orders", ex.getMessage(), true); }
    }
    @PostMapping("/products/add") public String addProduct(Authentication auth, @RequestParam(required=false) String id, @RequestParam String name, @RequestParam String category, @RequestParam(defaultValue="New catalogue item") String description, @RequestParam(defaultValue="📦") String icon, @RequestParam double price, @RequestParam int stock) {
        try { if (name.isBlank() || price <= 0 || stock < 0) throw new IllegalArgumentException("Enter a name, positive price and non-negative stock."); db.addProduct(new Product(id, name, category, description, icon, price, stock)); return redirect("/products", "Product saved", false); }
        catch (Exception ex) { return redirect("/products", ex.getMessage(), true); }
    }
    private String redirect(String path, String message, boolean isError) { return "redirect:" + path + "?" + (isError ? "error" : "message") + "=" + enc(message == null ? "Operation failed" : message); }
    private String enc(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }
}
