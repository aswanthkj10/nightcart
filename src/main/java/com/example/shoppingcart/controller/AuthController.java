package com.example.shoppingcart.controller;

import com.example.shoppingcart.model.AppUser;
import com.example.shoppingcart.repository.AppUserRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class AuthController {
    private final AppUserRepository users; private final PasswordEncoder encoder;
    public AuthController(AppUserRepository users, PasswordEncoder encoder) { this.users = users; this.encoder = encoder; }
    @GetMapping("/login") public String login() { return "login"; }
    @GetMapping("/register") public String register(Model model) { model.addAttribute("form", new RegisterForm()); return "register"; }
    @PostMapping("/register") public String createAccount(@ModelAttribute("form") RegisterForm form, BindingResult result, Model model) {
        String username = form.getUsername() == null ? "" : form.getUsername().trim().toLowerCase();
        String display = form.getDisplayName() == null ? "" : form.getDisplayName().trim();
        if (display.isBlank() || display.length() > 60) result.rejectValue("displayName", "invalid", "Enter your name (up to 60 characters). ");
        if (!username.matches("^[a-z0-9._-]{3,40}$")) result.rejectValue("username", "invalid", "Use 3–40 letters, numbers, dots, underscores or hyphens.");
        if (users.existsByUsernameIgnoreCase(username)) result.rejectValue("username", "duplicate", "That username is already registered.");
        if (form.getPassword() == null || form.getPassword().length() < 8 || form.getPassword().length() > 72) result.rejectValue("password", "invalid", "Password must be 8–72 characters long.");
        if (!java.util.Objects.equals(form.getPassword(), form.getConfirmPassword())) result.rejectValue("confirmPassword", "mismatch", "Passwords do not match.");
        if (result.hasErrors()) return "register";
        users.save(new AppUser(username, display, encoder.encode(form.getPassword()), "CUSTOMER"));
        return "redirect:/login?registered";
    }
    public static class RegisterForm {
        @NotBlank @Size(max=60) private String displayName;
        @NotBlank private String username;
        @NotBlank @Size(min=8,max=72) private String password;
        @NotBlank private String confirmPassword;
        public String getDisplayName(){return displayName;} public void setDisplayName(String v){displayName=v;}
        public String getUsername(){return username;} public void setUsername(String v){username=v;}
        public String getPassword(){return password;} public void setPassword(String v){password=v;}
        public String getConfirmPassword(){return confirmPassword;} public void setConfirmPassword(String v){confirmPassword=v;}
    }
}
