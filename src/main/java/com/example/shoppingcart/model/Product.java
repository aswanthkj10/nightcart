package com.example.shoppingcart.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("products")
public class Product {
    @Id private String id;
    private String name;
    private String category;
    private String description;
    private String icon;
    private double price;
    private int stock;
    public Product() {}
    public Product(String id,String name,String category,String description,String icon,double price,int stock){this.id=id;this.name=name;this.category=category;this.description=description;this.icon=icon;this.price=price;this.stock=stock;}
    public String getId(){return id;} public void setId(String v){id=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getCategory(){return category;} public void setCategory(String v){category=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public String getIcon(){return icon;} public void setIcon(String v){icon=v;}
    public double getPrice(){return price;} public void setPrice(double v){price=v;}
    public int getStock(){return stock;} public void setStock(int v){stock=v;}
}
