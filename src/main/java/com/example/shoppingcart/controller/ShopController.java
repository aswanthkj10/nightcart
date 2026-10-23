package com.example.shoppingcart.controller;

import com.example.shoppingcart.model.Product;
import com.example.shoppingcart.model.ShopOrder;
import com.example.shoppingcart.service.DatabaseService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@Controller
public class ShopController {
    private final DatabaseService db;
    public ShopController(DatabaseService db) { this.db = db; }

    private void base(Model model, String customer) {
        String who = customer == null || customer.isBlank() ? "Aswanth" : customer.trim();
        model.addAttribute("customer", who);
        model.addAttribute("monetEnabled", db.monetEnabled());
        model.addAttribute("products", db.products());
        model.addAttribute("cartRows", db.cartRows(who));
        model.addAttribute("cartTotal", db.cartTotal(who));
        model.addAttribute("statuses", db.status());
        model.addAttribute("customerCount", db.customerCount());
        model.addAttribute("orders", db.orders());
        model.addAttribute("orderCount", db.orderCount());
        model.addAttribute("totalSales", db.totalSales());
        model.addAttribute("productsCount", db.products().size());
        model.addAttribute("activityCount", db.activityLog().size());
        model.addAttribute("recentActivity", db.recentActivity());
        model.addAttribute("categories", db.products().stream().map(Product::getCategory).distinct().sorted().toList());
    }
    private String page(Model model, String customer, String page) { base(model, customer); model.addAttribute("page", page); return "index"; }
    @GetMapping({"/", "/dashboard"}) public String dashboard(@RequestParam(defaultValue="Aswanth") String customer, @RequestParam(required=false) String message, @RequestParam(required=false) String error, Model model) { page(model,customer,"dashboard"); model.addAttribute("message",message); model.addAttribute("error",error); return "index"; }
    @GetMapping("/products") public String products(@RequestParam(defaultValue="Aswanth") String customer, @RequestParam(required=false) String q, @RequestParam(required=false) String category, Model model) {
        page(model,customer,"products"); List<Product> list=db.products();
        if(category!=null&&!category.isBlank()) list=list.stream().filter(p->p.getCategory().equalsIgnoreCase(category)).toList();
        if(q!=null&&!q.isBlank()){String needle=q.toLowerCase(Locale.ROOT);list=list.stream().filter(p->p.getName().toLowerCase(Locale.ROOT).contains(needle)||p.getDescription().toLowerCase(Locale.ROOT).contains(needle)).toList();}
        model.addAttribute("products",list); model.addAttribute("q",q==null?"":q); model.addAttribute("category",category==null?"":category); return "index";
    }
    @GetMapping("/cart") public String cart(@RequestParam(defaultValue="Aswanth") String customer,Model model){return page(model,customer,"cart");}
    @GetMapping("/orders") public String orders(@RequestParam(defaultValue="Aswanth") String customer,Model model){return page(model,customer,"orders");}
    @GetMapping("/analytics") public String analytics(@RequestParam(defaultValue="Aswanth") String customer,Model model){page(model,customer,"analytics");model.addAttribute("analyticsRows",db.analytics());return "index";}
    @GetMapping("/recommendations") public String recommendations(@RequestParam(defaultValue="Aswanth") String customer,@RequestParam(defaultValue="P100") String productId,Model model){page(model,customer,"recommendations");model.addAttribute("sourceProduct",db.product(productId).orElse(null));model.addAttribute("recommendations",db.recommendations(productId));return "index";}
    @GetMapping("/databases") public String databases(@RequestParam(defaultValue="Aswanth") String customer,Model model){return page(model,customer,"databases");}
    @GetMapping("/activity") public String activity(@RequestParam(defaultValue="Aswanth") String customer,@RequestParam(required=false) String database,@RequestParam(required=false) String status,Model model){page(model,customer,"activity");var events=db.activityLog();if(database!=null&&!database.isBlank())events=events.stream().filter(e->e.database().equalsIgnoreCase(database)).toList();if(status!=null&&!status.isBlank())events=events.stream().filter(e->e.status().equalsIgnoreCase(status)).toList();model.addAttribute("activityEvents",events);model.addAttribute("selectedDatabase",database==null?"":database);model.addAttribute("selectedStatus",status==null?"":status);return "index";}
    @PostMapping("/cart/add") public String add(@RequestParam(defaultValue="Aswanth") String customer,@RequestParam String productId,@RequestParam(defaultValue="1") int quantity){try{db.addToCart(customer,productId,quantity);return redirect("/products",customer,"Added to cart",false);}catch(Exception ex){return redirect("/products",customer,ex.getMessage(),true);}}
    @PostMapping("/cart/remove") public String remove(@RequestParam(defaultValue="Aswanth") String customer,@RequestParam String productId){try{db.removeFromCart(customer,productId);return redirect("/cart",customer,"Item removed",false);}catch(Exception ex){return redirect("/cart",customer,ex.getMessage(),true);}}
    @PostMapping("/checkout") public String checkout(@RequestParam(defaultValue="Aswanth") String customer){try{ShopOrder order=db.checkout(customer);return redirect("/orders",customer,"Order "+order.getId()+" placed",false);}catch(Exception ex){return redirect("/cart",customer,ex.getMessage(),true);}}
    @PostMapping("/orders/cancel") public String cancelOrder(@RequestParam(defaultValue="Aswanth") String customer,@RequestParam String orderId){try{ShopOrder order=db.cancelOrder(orderId);return redirect("/orders",customer,"Order "+order.getId()+" cancelled.",false);}catch(Exception ex){return redirect("/orders",customer,ex.getMessage(),true);}}
    @PostMapping("/products/add") public String addProduct(@RequestParam(defaultValue="Aswanth") String customer,@RequestParam(required=false) String id,@RequestParam String name,@RequestParam String category,@RequestParam(defaultValue="New catalogue item") String description,@RequestParam(defaultValue="📦") String icon,@RequestParam double price,@RequestParam int stock){try{if(name.isBlank()||price<=0||stock<0)throw new IllegalArgumentException("Enter a name, positive price and non-negative stock.");db.addProduct(new Product(id,name,category,description,icon,price,stock));return redirect("/products",customer,"Product saved",false);}catch(Exception ex){return redirect("/products",customer,ex.getMessage(),true);}}
    private String redirect(String path,String customer,String message,boolean isError){return "redirect:"+path+"?customer="+enc(customer)+"&"+(isError?"error":"message")+"="+enc(message==null?"Operation failed":message);}
    private String enc(String value){return URLEncoder.encode(value==null?"Aswanth":value,StandardCharsets.UTF_8);}
}
