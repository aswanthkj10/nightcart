package com.example.shoppingcart.config;

import com.example.shoppingcart.model.AppUser;
import com.example.shoppingcart.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AdminAccountInitializer {
    @Bean CommandLineRunner createConfiguredAdmin(AppUserRepository users, PasswordEncoder encoder,
            @Value("${ADMIN_USERNAME:}") String username, @Value("${ADMIN_PASSWORD:}") String password) {
        return args -> {
            if (username == null || username.isBlank() || password == null || password.length() < 12) return;
            var existing = users.findByUsernameIgnoreCase(username.trim().toLowerCase(java.util.Locale.ROOT));
            AppUser admin = existing.orElseGet(AppUser::new);
            admin.setUsername(username.trim().toLowerCase(java.util.Locale.ROOT)); admin.setDisplayName("Administrator");
            admin.setPassword(encoder.encode(password)); admin.setRole("ADMIN"); users.save(admin);
        };
    }
}
