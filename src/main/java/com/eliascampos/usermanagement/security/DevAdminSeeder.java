package com.eliascampos.usermanagement.security;

import com.eliascampos.usermanagement.user.User;
import com.eliascampos.usermanagement.user.UserRepository;
import com.eliascampos.usermanagement.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Cria um admin de desenvolvimento quando o banco esta vazio, para que o login
 * funcione logo apos subir a aplicacao. Fica fora dos perfis prod e test.
 */
@Component
@Profile("dev")
public class DevAdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevAdminSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public DevAdminSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder,
                          @org.springframework.beans.factory.annotation.Value("${app.bootstrap.admin.email:}") String email,
                          @org.springframework.beans.factory.annotation.Value("${app.bootstrap.admin.password:}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email == null ? "" : email.trim();
        this.password = password == null ? "" : password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isEmpty() || password.isEmpty() || password.length() < 8) {
            return;
        }
        if (userRepository.count() > 0) {
            return;
        }
        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        try {
            userRepository.save(new User("Admin Dev", normalizedEmail, UserRole.ADMIN,
                    passwordEncoder.encode(password)));
            log.warn("Admin de desenvolvimento criado: {}", normalizedEmail);
        } catch (DuplicateKeyException ex) {
            log.info("Admin de desenvolvimento ja existe; ignorado");
        }
    }
}
