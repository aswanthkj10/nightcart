package com.example.shoppingcart.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

@Document(collection = "users")
public class AppUser {
    @Id private String id;
    @Indexed(unique = true) private String username;
    private String displayName;
    private String password;
    private String role = "CUSTOMER";

    public AppUser() {}
    public AppUser(String username, String displayName, String password, String role) {
        this.username = username; this.displayName = displayName; this.password = password; this.role = role;
    }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
